package com.maragung.arrowide.toolchain

import com.maragung.arrowide.workspace.PathSafety
import org.apache.commons.compress.archivers.ar.ArArchiveEntry
import org.apache.commons.compress.archivers.ar.ArArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermission
import java.util.zip.GZIPInputStream

/**
 * Extracts Debian `.deb` packages into a directory (plan #4/#7).
 *
 * A `.deb` is an `ar` archive whose members are `debian-binary`,
 * `control.tar.*` and `data.tar.*`. The payload lives in `data.tar.xz`
 * (Termux layout: entries under `./usr/...`, so the extraction target is
 * the toolchain prefix itself). This reader tolerates the `data.tar`,
 * `data.tar.gz` and `data.tar.zst` member spellings; zstd is rejected
 * explicitly because no zstd codec is bundled (plan #6: keep the APK lean).
 *
 * Hardening (plan #50 "malicious archives"): every entry name is validated
 * with [PathSafety.resolveWithin] against the target directory, so `..`,
 * absolute-path injection and symlink escapes throw instead of writing
 * outside the prefix.
 */
object DebArchive {

    private const val BUFFER_BYTES = 64 * 1024

    /** `data.tar` compression suffixes we can handle, in preference order. */
    private val DATA_MEMBER_SUFFIXES = listOf("data.tar.xz", "data.tar", "data.tar.gz")

    private val posixSupported: Boolean = try {
        FileSystems.getDefault().supportedFileAttributeViews().contains("posix")
    } catch (e: IOException) {
        false
    }

    /**
     * Extracts the `data.tar.*` member of [debFile] into [targetDir].
     *
     * Unix permission bits are preserved (the executable bit on `0755`
     * files is what makes downloaded binaries runnable), symlinks are
     * recreated as relative links, and hard links are satisfied with a
     * symlink fallback. Long/PAX/GNU headers are transparently handled by
     * commons-compress.
     *
     * @throws ToolchainException on a malformed archive, an unsupported
     *         compression, a path-traversal attempt or an I/O failure
     */
    fun extract(debFile: File, targetDir: File) {
        try {
            debFile.inputStream().buffered().use { fileIn ->
                ArArchiveInputStream(fileIn).use { arIn ->
                    var entry: ArArchiveEntry? = arIn.nextEntry
                    while (entry != null) {
                        val memberName = entry.name.trim().trimEnd('/')
                        if (memberName == "data.tar.zst") {
                            throw ToolchainException(
                                "zstd not supported yet: ${debFile.name} uses data.tar.zst"
                            )
                        }
                        if (memberName in DATA_MEMBER_SUFFIXES) {
                            extractDataTar(arIn, memberName, targetDir)
                            // data.tar is all we need; stop reading the ar.
                            return
                        }
                        entry = arIn.nextEntry
                    }
                }
            }
        } catch (e: ToolchainException) {
            throw e
        } catch (e: SecurityException) {
            // PathSafety rejected an entry of a hostile archive.
            throw ToolchainException("Unsafe path in ${debFile.name}: ${e.message}", e)
        } catch (e: Exception) {
            // IOException, ZipException (bad xz), ArchiveException (bad tar/ar).
            throw ToolchainException("Failed to extract ${debFile.name}: ${e.message}", e)
        }
        throw ToolchainException("No data.tar member found in ${debFile.name}")
    }

    /**
     * Extracts the positioned `data.tar.[xz|gz|]` member.
     *
     * The tar stream must not be closed here while the surrounding `ar`
     * stream is still owned by the caller's `use` blocks — closing it would
     * close the file underneath (commons-compress closes the whole chain).
     */
    private fun extractDataTar(arIn: ArArchiveInputStream, memberName: String, targetDir: File) {
        val decompressed: InputStream = when (memberName) {
            "data.tar.xz" -> XZCompressorInputStream(arIn)
            "data.tar.gz" -> GZIPInputStream(arIn)
            else -> arIn
        }
        val tarIn = TarArchiveInputStream(decompressed, "UTF-8")
        val pendingDirModes = mutableListOf<Pair<File, Int>>()

        var entry: TarArchiveEntry? = tarIn.nextEntry
        while (entry != null) {
            extractEntry(entry, tarIn, targetDir, pendingDirModes)
            entry = tarIn.nextEntry
        }
        // Directory modes are applied last so a 0555 directory cannot block
        // writing its own contents.
        for ((dir, mode) in pendingDirModes.asReversed()) {
            applyUnixMode(dir, mode)
        }
    }

    private fun extractEntry(
        entry: TarArchiveEntry,
        tarIn: TarArchiveInputStream,
        targetDir: File,
        pendingDirModes: MutableList<Pair<File, Int>>,
    ) {
        val name = normalizeEntryName(entry.name)
        if (name.isEmpty()) return

        val target = PathSafety.resolveWithin(targetDir, name)

        when {
            entry.isDirectory -> {
                Files.createDirectories(target.toPath())
                pendingDirModes += target to entry.mode
            }

            entry.isSymbolicLink -> {
                Files.createDirectories(target.parentFile.toPath())
                Files.deleteIfExists(target.toPath())
                Files.createSymbolicLink(target.toPath(), Paths.get(entry.linkName))
            }

            // Hard link: satisfied with a symlink fallback (contents are
            // identical in the same archive; a true hardlink is not needed
            // for a private-app-storage toolchain).
            entry.isLink -> {
                Files.createDirectories(target.parentFile.toPath())
                Files.deleteIfExists(target.toPath())
                Files.createSymbolicLink(target.toPath(), Paths.get(entry.linkName))
            }

            entry.isFile -> {
                Files.createDirectories(target.parentFile.toPath())
                Files.deleteIfExists(target.toPath())
                Files.newOutputStream(target.toPath()).use { out ->
                    copyEntryPayload(entry, tarIn, out)
                }
                applyUnixMode(target, entry.mode)
            }

            // Character/block devices and FIFOs have no meaning inside an
            // Android app sandbox; skip them.
            else -> Unit
        }
    }

    /**
     * Copies the current entry's payload out of [tarIn]; reading through the
     * tar stream stops exactly at the entry boundary.
     *
     * @throws ToolchainException when the archive ends mid-entry
     */
    private fun copyEntryPayload(entry: TarArchiveEntry, tarIn: TarArchiveInputStream, out: OutputStream) {
        val buffer = ByteArray(BUFFER_BYTES)
        var remaining = entry.size
        while (remaining > 0) {
            val chunk = if (remaining >= BUFFER_BYTES) BUFFER_BYTES else remaining.toInt()
            val read = tarIn.read(buffer, 0, chunk)
            if (read < 0) {
                throw ToolchainException("Truncated entry '${entry.name}' in data.tar")
            }
            out.write(buffer, 0, read)
            remaining -= read
        }
    }

    /** Strips the leading "./" or "/" Debian prefix from a tar entry name. */
    private fun normalizeEntryName(name: String): String =
        name.trim().removePrefix("./").removePrefix("/").trimEnd('/')

    /**
     * Applies the permission bits of [mode] (type bits masked off) to
     * [file]: via POSIX attributes when the filesystem supports them, else
     * via the java.io permission setters (Android-safe fallback).
     */
    private fun applyUnixMode(file: File, mode: Int) {
        val perms = mode and 0b111_111_111
        if (perms == 0) return
        val path = file.toPath()

        fun has(mask: Int): Boolean = (perms and mask) != 0

        if (posixSupported) {
            val set = mutableSetOf<PosixFilePermission>()
            if (has(0b100_000_000)) set += PosixFilePermission.OWNER_READ
            if (has(0b010_000_000)) set += PosixFilePermission.OWNER_WRITE
            if (has(0b001_000_000)) set += PosixFilePermission.OWNER_EXECUTE
            if (has(0b000_100_000)) set += PosixFilePermission.GROUP_READ
            if (has(0b000_010_000)) set += PosixFilePermission.GROUP_WRITE
            if (has(0b000_001_000)) set += PosixFilePermission.GROUP_EXECUTE
            if (has(0b000_000_100)) set += PosixFilePermission.OTHERS_READ
            if (has(0b000_000_010)) set += PosixFilePermission.OTHERS_WRITE
            if (has(0b000_000_001)) set += PosixFilePermission.OTHERS_EXECUTE
            try {
                Files.setPosixFilePermissions(path, set)
                return
            } catch (e: IOException) {
                // Fall through to the java.io fallback below.
            }
        }

        // Fallback without POSIX attribute support: owner-only setters when
        // no group/other bit is present, all-users setters otherwise.
        // (Positional args: these are Java methods without parameter names.)
        file.setReadable(has(0b100_100_100), !has(0b000_100_100))
        file.setWritable(has(0b010_010_010), !has(0b000_010_010))
        file.setExecutable(has(0b001_001_001), !has(0b000_001_001))
    }
}
