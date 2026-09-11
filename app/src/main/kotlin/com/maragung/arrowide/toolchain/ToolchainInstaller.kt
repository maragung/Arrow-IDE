package com.maragung.arrowide.toolchain

import com.maragung.arrowide.workspace.PathSafety
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes

/**
 * Record of one installed package: every path this package added to the
 * toolchain prefix. Persisted as JSON under
 * `prefixDir/var/arrow-ide/manifests/{name}.json` and used for clean
 * removal and status reporting.
 *
 * Paths are stored relative to the prefix, POSIX-style (`bin/node`).
 */
@Serializable
data class PackageManifest(
    val packageName: String,
    val version: String,
    val files: List<String> = emptyList(),
    val symlinks: Map<String, String> = emptyMap(),
)

/**
 * The install/remove engine (plan #7 security model).
 *
 * Termux layout: a `.deb`'s `data.tar` carries everything under `./usr/`,
 * and the toolchain prefix *is* that `/usr` — so `./usr/bin/node` must land
 * at `prefixDir/bin/node`. Installation therefore works in three stages:
 *
 *  1. **Stage** the already-verified `.deb` into a sibling staging
 *     directory (full extraction, preserving permissions and symlinks).
 *  2. **Merge** the staged `usr/` payload into the prefix file by file,
 *     backing up every pre-existing file/symlink the merge overwrites.
 *  3. **Commit** by writing the manifest — or **roll back** on any failure:
 *     newly added files are deleted, overwritten ones are restored from
 *     the backup, and the staging/backup directories are removed. A failed
 *     install never leaves the prefix in a mixed state (plan #46).
 *
 * Staging and backup directories are siblings of the prefix so merges are
 * same-filesystem renames. All operations run on [ioDispatcher]; there is
 * no `android.*` dependency so the class is JVM-unit-testable.
 */
class ToolchainInstaller(
    private val environment: ToolchainEnvironment,
    private val ioDispatcher: CoroutineDispatcher,
) {

    /** Coarse progress stages of a single package install. */
    enum class InstallStage { EXTRACTING, INSTALLING }

    companion object {
        internal const val MANIFESTS_REL_PATH = "var/arrow-ide/manifests"

        private val json = Json {
            ignoreUnknownKeys = true
            prettyPrint = true
        }
    }

    private val prefixDir: File get() = environment.prefixDir

    /** Directory the manifests live in (created on demand). */
    val manifestsDir: File
        get() = File(prefixDir, MANIFESTS_REL_PATH)

    /**
     * Installs the already-verified [debFile] for [repoPackage] into the
     * prefix and returns the written manifest. [onStage] is invoked (from
     * [ioDispatcher]) when the extraction and merge stages begin, so the
     * caller can surface progress.
     *
     * @throws ToolchainException on extraction or merge failure; the prefix
     *         is restored to its previous state before the throw
     */
    suspend fun install(
        repoPackage: RepoPackage,
        debFile: File,
        onStage: (InstallStage) -> Unit = {},
    ): PackageManifest =
        withContext(ioDispatcher) {
            Files.createDirectories(prefixDir.toPath())
            val staging = stagingDirFor(repoPackage.name)
            val backup = backupDirFor(repoPackage.name)
            deleteRecursively(staging)
            deleteRecursively(backup)

            val journal = InstallJournal()
            try {
                // Stage 1: full extraction into the sibling staging dir.
                onStage(InstallStage.EXTRACTING)
                DebArchive.extract(debFile, staging)

                // Stage 2: merge the payload into the prefix.
                onStage(InstallStage.INSTALLING)
                val payloadRoot = resolvePayloadRoot(staging)
                mergeIntoPrefix(payloadRoot.toPath(), payloadRoot.toPath(), backup.toPath(), journal)

                // Stage 3: commit.
                val manifest = PackageManifest(
                    packageName = repoPackage.name,
                    version = repoPackage.version,
                    files = journal.installedFiles().map { relativize(it) },
                    symlinks = journal.installedSymlinks()
                        .associate { relativize(it.first) to it.second },
                )
                writeManifest(manifest)
                deleteRecursively(backup)
                deleteRecursively(staging)
                manifest
            } catch (e: ToolchainException) {
                rollback(journal, backup)
                deleteRecursively(staging)
                throw e
            } catch (e: Exception) {
                rollback(journal, backup)
                deleteRecursively(staging)
                throw ToolchainException(
                    "Failed to install '${repoPackage.name}': ${e.message}", e
                )
            }
        }

    /**
     * Removes [manifest]'s files and symlinks from the prefix, prunes
     * directories the removal emptied (staying inside the prefix) and
     * deletes the manifest file.
     *
     * Paths that another installed package also claims are left in place
     * (dpkg's rule): an overlapping file belongs to whoever installed it
     * last, but removing that package must not punch a hole into a package
     * that still needs the path.
     */
    suspend fun remove(manifest: PackageManifest) = withContext(ioDispatcher) {
        val claimedByOthers = buildSet {
            for ((name, other) in readManifests()) {
                if (name != manifest.packageName) {
                    addAll(other.files)
                    addAll(other.symlinks.keys)
                }
            }
        }
        val prunedDirs = mutableSetOf<File>()
        for (rel in manifest.symlinks.keys) {
            if (rel in claimedByOthers) continue
            val link = resolveInPrefix(rel)
            if (Files.isSymbolicLink(link.toPath())) {
                Files.deleteIfExists(link.toPath())
                prunedDirs += link.parentFile
            }
        }
        for (rel in manifest.files) {
            if (rel in claimedByOthers) continue
            val file = resolveInPrefix(rel)
            if (file.isFile) {
                Files.deleteIfExists(file.toPath())
                prunedDirs += file.parentFile
            }
        }
        pruneEmptyDirs(prunedDirs)
        manifestFileFor(manifest.packageName).delete()
        Unit
    }

    /**
     * Loads every manifest currently present under [manifestsDir], keyed by
     * package name. A manifest that fails to parse is skipped (treated as
     * not installed; `repair` exists for exactly that case).
     */
    suspend fun readManifests(): Map<String, PackageManifest> = withContext(ioDispatcher) {
        val dir = manifestsDir
        if (!dir.isDirectory) return@withContext emptyMap()
        val result = mutableMapOf<String, PackageManifest>()
        for (file in dir.listFiles().orEmpty()) {
            if (!file.name.endsWith(".json")) continue
            try {
                val manifest = json.decodeFromString<PackageManifest>(file.readText())
                result[manifest.packageName] = manifest
            } catch (e: Exception) {
                // Corrupt manifest: ignore, the UI surfaces "not installed".
            }
        }
        result
    }

    /** Deletes the manifest file of [packageName] without touching files. */
    suspend fun deleteManifest(packageName: String) = withContext(ioDispatcher) {
        manifestFileFor(packageName).delete()
        Unit
    }

    /**
     * The `.deb` payload lives under `./usr`; if the staged tree has that
     * directory it is the payload root, otherwise the staging root itself
     * is (defensive for non-Termux layouts).
     */
    private fun resolvePayloadRoot(staging: File): File {
        val usr = File(staging, "usr")
        return if (usr.isDirectory) usr else staging
    }

    /**
     * Recursive, symlink-aware merge of [source] (inside staging) into the
     * prefix. Every mutation is journaled so [rollback] can undo it.
     * [backup] receives copies of pre-existing files that get overwritten.
     */
    private fun mergeIntoPrefix(
        source: Path,
        payloadRoot: Path,
        backup: Path,
        journal: InstallJournal,
    ) {
        Files.newDirectoryStream(source).use { stream ->
            for (entry in stream) {
                mergeEntry(entry, payloadRoot, backup, journal)
            }
        }
    }

    private fun mergeEntry(
        entry: Path,
        payloadRoot: Path,
        backup: Path,
        journal: InstallJournal,
    ) {
        // Paths are always taken relative to the payload root so recursion
        // into subdirectories keeps the full prefix-relative path.
        val rel = payloadRoot.relativize(entry)
        val target = prefixDir.toPath().resolve(rel)

        // Defensive: whatever the staged tree contains must stay in prefix.
        PathSafety.resolveWithin(prefixDir, rel.toString())

        val attrs = try {
            Files.readAttributes(entry, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        } catch (e: IOException) {
            throw ToolchainException("Staged entry disappeared: $entry", e)
        }

        when {
            attrs.isDirectory -> {
                val existed = Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)
                if (!existed) journal.dirsCreated += target.toFile()
                Files.createDirectories(target)
                mergeIntoPrefix(entry, payloadRoot, backup, journal)
            }

            attrs.isSymbolicLink -> {
                val linkTarget = Files.readSymbolicLink(entry)
                recordExisting(target, rel, backup, journal)
                Files.deleteIfExists(target)
                Files.createSymbolicLink(target, linkTarget)
                journal.symlinksAdded += target.toFile() to linkTarget.toString()
            }

            attrs.isRegularFile -> {
                recordExisting(target, rel, backup, journal)
                Files.move(entry, target, StandardCopyOption.REPLACE_EXISTING)
                journal.filesAdded += target.toFile()
            }

            else -> {
                // Devices/FIFOs never appear in a staged tree (DebArchive
                // skips them); nothing to do.
            }
        }
    }

    /**
     * Journals whatever currently sits at [target] so it can be restored on
     * rollback: a file is copied into [backup], a symlink is recorded with
     * its target, a pre-existing directory is left alone.
     */
    private fun recordExisting(target: Path, rel: Path, backup: Path, journal: InstallJournal) {
        when {
            Files.isSymbolicLink(target) ->
                journal.symlinksReplaced += target.toFile() to
                    Files.readSymbolicLink(target).toString()

            Files.isRegularFile(target) -> {
                val backupPath = backup.resolve(rel)
                Files.createDirectories(backupPath.parent)
                Files.copy(target, backupPath, StandardCopyOption.REPLACE_EXISTING)
                journal.backedUpFiles += backupPath.toFile() to target.toFile()
            }
        }
    }

    /** Undoes every journaled change and restores the backup. */
    private fun rollback(journal: InstallJournal, backup: File) {
        try {
            // 1. Remove everything this install added.
            for (file in journal.filesAdded) {
                Files.deleteIfExists(file.toPath())
            }
            for ((link, _) in journal.symlinksAdded) {
                Files.deleteIfExists(link.toPath())
            }
            // 2. Restore overwritten originals.
            for ((backupFile, original) in journal.backedUpFiles) {
                Files.createDirectories(original.parentFile.toPath())
                Files.move(
                    backupFile.toPath(),
                    original.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
            for ((original, linkTarget) in journal.symlinksReplaced) {
                Files.deleteIfExists(original.toPath())
                Files.createSymbolicLink(original.toPath(), Paths.get(linkTarget))
            }
            // 3. Drop directories the merge created (deepest first); the
            //    delete is a no-op on non-empty ones.
            for (dir in journal.dirsCreated.asReversed()) {
                dir.delete()
            }
        } catch (e: IOException) {
            // Rollback is best-effort; the original failure is what gets
            // reported. A leftover -backup- directory is recoverable by hand.
        }
        deleteRecursively(backup)
    }

    private fun writeManifest(manifest: PackageManifest) {
        Files.createDirectories(manifestsDir.toPath())
        manifestFileFor(manifest.packageName).writeText(json.encodeToString(manifest))
    }

    private fun manifestFileFor(packageName: String): File =
        File(manifestsDir, "$packageName.json")

    /**
     * Resolves a manifest-relative path inside the prefix, lexically (no
     * canonicalization: a recorded symlink must be deleted as itself, not
     * as its target). Traversal outside the prefix throws.
     */
    private fun resolveInPrefix(relPath: String): File {
        val prefix = prefixDir.canonicalFile.toPath()
        val resolved = prefix.resolve(relPath).normalize()
        if (resolved != prefix && !resolved.startsWith(prefix)) {
            throw ToolchainException("Manifest path escapes the toolchain prefix: $relPath")
        }
        return resolved.toFile()
    }

    /** Prefix-relative POSIX path of [file] (lexical, symlink-safe). */
    private fun relativize(file: File): String {
        val prefixPath = prefixDir.canonicalFile.toPath()
        return prefixPath.relativize(file.canonicalFile.parentFile.toPath()
            .resolve(file.name)).toString()
    }

    /** Deletes empty directories from [dirs] upward, never leaving the prefix. */
    private fun pruneEmptyDirs(dirs: Collection<File>) {
        val prefixPath = prefixDir.canonicalFile.toPath()
        val sorted = dirs.filterNotNull().distinct()
            .map { it.canonicalFile }
            .sortedByDescending { it.path.length }
        for (dir in sorted) {
            var current: File? = dir
            while (current != null) {
                val path = current.toPath()
                if (path == prefixPath || !path.startsWith(prefixPath)) break
                if (!current.isDirectory || current.list()?.isNotEmpty() == true) break
                current.delete()
                current = current.parentFile
            }
        }
    }

    /**
     * Deletes [file] without ever following symlinks: a staged tree from a
     * hostile archive may contain links pointing outside the app's storage
     * (plan #50), and this cleanup must not touch their targets.
     */
    private fun deleteRecursively(file: File) {
        deleteTree(file.toPath())
    }

    private fun deleteTree(path: Path) {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
        val attrs = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        if (attrs.isDirectory) {
            Files.newDirectoryStream(path).use { stream ->
                for (child in stream) {
                    deleteTree(child)
                }
            }
        }
        Files.deleteIfExists(path)
    }

    // Staging/backup directories are siblings of the prefix so renames
    // between them and the prefix stay on the same filesystem.

    internal fun stagingDirFor(packageName: String): File =
        File(prefixDir.parentFile, prefixDir.name + "-staging-" + safeDirSuffix(packageName))

    internal fun backupDirFor(packageName: String): File =
        File(prefixDir.parentFile, prefixDir.name + "-backup-" + safeDirSuffix(packageName))

    private fun safeDirSuffix(packageName: String): String =
        packageName.replace(Regex("[^A-Za-z0-9._+-]"), "_")

    /**
     * Tracks every prefix mutation of one install so a failure can be
     * rolled back completely.
     */
    private class InstallJournal {
        val filesAdded = mutableListOf<File>()
        val symlinksAdded = mutableListOf<Pair<File, String>>() // path -> link target
        val backedUpFiles = mutableListOf<Pair<File, File>>() // backup copy, original
        val symlinksReplaced = mutableListOf<Pair<File, String>>() // path, old target
        val dirsCreated = mutableListOf<File>()

        /** All regular files present after a committed install. */
        fun installedFiles(): List<File> =
            (filesAdded + backedUpFiles.map { it.second }).distinct()

        /** All symlinks present after a committed install. */
        fun installedSymlinks(): List<Pair<File, String>> =
            (symlinksAdded + symlinksReplaced.map { it.first to it.second })
                .distinctBy { it.first }
    }
}
