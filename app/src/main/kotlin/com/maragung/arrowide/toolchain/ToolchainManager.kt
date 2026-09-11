package com.maragung.arrowide.toolchain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** Phases of a running tool operation, in order. */
enum class ToolPhase { RESOLVING, DOWNLOADING, VERIFYING, EXTRACTING, INSTALLING, DONE }

/** Lifecycle status of a catalog tool. */
enum class ToolStatus { NOT_INSTALLED, INSTALLED, UPDATING_OR_INSTALLING, FAILED }

/** UI-facing snapshot of one catalog tool. */
data class ToolInfo(
    val id: String,
    val displayName: String,
    val description: String,
    val status: ToolStatus,
    val installedVersion: String?,
    val availableVersion: String?,
    val error: String?,
    val optional: Boolean,
)

/** Progress of a running tool operation. */
data class ToolOperation(
    val toolId: String,
    val phase: ToolPhase,
    val progress: Float?,
    val message: String?,
)

/**
 * Drives the whole toolchain workflow (plan #4-#7): resolve dependencies
 * against the repository index, download, verify the SHA-256 (nothing is
 * extracted — let alone executed — before the checksum matches), extract
 * and install with rollback protection.
 *
 * The UI contract is reactive: [tools] holds the catalog state (built from
 * [ToolCatalog], the installed manifests and the index) and [operations]
 * holds one live [ToolOperation] per tool currently installing. Both flows
 * are updated from any thread and are safe to collect anywhere.
 *
 * Constructing the manager performs no I/O; call [refresh] first (the app
 * layer does this from a coroutine scope after the screen appears).
 * Operations on the same tool are serialized through a per-tool mutex.
 */
class ToolchainManager(
    private val environment: ToolchainEnvironment,
    private val repoClient: PackageRepoClient,
    private val downloader: PackageDownloader,
    private val ioDispatcher: CoroutineDispatcher,
) {

    private val installer = ToolchainInstaller(environment, ioDispatcher)

    private val lock = Any()

    /** Package index by name; null until the first successful load. */
    private var indexByName: Map<String, RepoPackage>? = null

    /** Installed manifests by package name. */
    private var manifests: Map<String, PackageManifest> = emptyMap()

    /** Last error per tool id; drives the FAILED status. */
    private val errors = mutableMapOf<String, String>()

    /** Tool ids with a running operation. */
    private val activeOps = mutableSetOf<String>()

    private val _tools = MutableStateFlow(computeTools())

    /** Catalog tools with their current status. */
    val tools: StateFlow<List<ToolInfo>> = _tools.asStateFlow()

    private val _operations = MutableStateFlow<Map<String, ToolOperation>>(emptyMap())

    /** toolId -> the currently running operation for that tool. */
    val operations: StateFlow<Map<String, ToolOperation>> = _operations.asStateFlow()

    private val toolMutexes = ConcurrentHashMap<String, Mutex>()

    /**
     * Reloads the package index and the installed manifests and recomputes
     * [tools]. A failed index load keeps the previously loaded index (the
     * app stays usable offline, plan #40) and leaves
     * [isAvailable] answering null.
     */
    suspend fun refresh() {
        val fresh = try {
            repoClient.loadIndex(environment.arch).associateBy { it.name }
        } catch (e: IOException) {
            null
        }
        val freshManifests = installer.readManifests()
        synchronized(lock) {
            if (fresh != null) {
                indexByName = fresh
                errors.clear()
            }
            manifests = freshManifests
        }
        publishTools()
    }

    /**
     * Installs [toolId] and all its dependencies.
     *
     * Failures are swallowed into the FAILED status (with [ToolInfo.error]
     * set) rather than thrown, except cancellation; the prefix is rolled
     * back to its previous state by the installer either way.
     */
    suspend fun install(toolId: String) {
        val tool = catalogToolOrThrow(toolId)
        mutexFor(toolId).withLock {
            performInstall(tool)
        }
    }

    /**
     * Updates [toolId] to the repository version. Implemented as a
     * reinstall over the existing files; the installer's backup/rollback
     * machinery restores the previous version if anything fails (plan #46).
     */
    suspend fun update(toolId: String) {
        install(toolId)
    }

    /**
     * Removes [toolId]'s package (files, symlinks, manifest). Dependencies
     * that were pulled in by this tool are intentionally left in place —
     * other installed tools may share them (the same rule apt uses without
     * `autoremove`).
     */
    suspend fun remove(toolId: String) {
        val tool = catalogToolOrThrow(toolId)
        mutexFor(toolId).withLock {
            val manifest = synchronized(lock) { manifests[tool.packageName] }
            beginOperation(toolId, ToolPhase.INSTALLING, "Removing…")
            try {
                if (manifest != null) {
                    installer.remove(manifest)
                }
                val freshManifests = installer.readManifests()
                synchronized(lock) {
                    manifests = freshManifests
                    errors.remove(toolId)
                }
                completeOperation(toolId, "Removed")
            } catch (e: CancellationException) {
                abortOperation(toolId)
                throw e
            } catch (e: Exception) {
                abortOperation(toolId)
                fail(toolId, "Remove failed: ${e.message}")
            }
        }
    }

    /**
     * Repairs [toolId]: cleans up whatever manifest/files are recorded for
     * it (or just the corrupt manifest file if it no longer parses) and
     * reinstalls from the repository.
     */
    suspend fun repair(toolId: String) {
        val tool = catalogToolOrThrow(toolId)
        mutexFor(toolId).withLock {
            try {
                val manifest = synchronized(lock) { manifests[tool.packageName] }
                if (manifest != null) {
                    installer.remove(manifest)
                } else {
                    installer.deleteManifest(tool.packageName)
                }
                val freshManifests = installer.readManifests()
                synchronized(lock) { manifests = freshManifests }
                publishTools()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Best-effort cleanup; the reinstall below is the fix.
            }
            performInstall(tool)
        }
    }

    /**
     * Whether the repository offers the tool for this architecture.
     *
     * @return null when no index has been loaded yet (call [refresh]),
     *         false for optional tools missing from the index (UI greys
     *         them out)
     */
    fun isAvailable(toolId: String): Boolean? {
        val tool = ToolCatalog.byId(toolId) ?: return null
        val index = synchronized(lock) { indexByName } ?: return null
        return index.containsKey(tool.packageName)
    }

    // ------------------------------------------------------------------
    // Install pipeline
    // ------------------------------------------------------------------

    private suspend fun performInstall(tool: CatalogTool) {
        val toolId = tool.id
        beginOperation(toolId, ToolPhase.RESOLVING, "Resolving dependencies…")
        synchronized(lock) {
            errors.remove(toolId)
        }
        publishTools()
        try {
            val index = ensureIndex()
            val packages = DependencyResolver.resolve(
                index,
                listOf(tool.packageName),
            )
            val total = packages.size
            packages.forEachIndexed { i, pkg ->
                val base = (i.toFloat()) / total
                installOnePackage(pkg, base, 1f / total, toolId)
            }
            val freshManifests = installer.readManifests()
            synchronized(lock) {
                manifests = freshManifests
            }
            completeOperation(toolId, "Installed")
        } catch (e: CancellationException) {
            abortOperation(toolId)
            throw e
        } catch (e: Exception) {
            abortOperation(toolId)
            fail(toolId, e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * Downloads, verifies and installs a single package, mapping its
     * progress onto the [base, base + span) slice of the whole operation.
     */
    private suspend fun installOnePackage(
        pkg: RepoPackage,
        base: Float,
        span: Float,
        toolId: String,
    ) {
        val debFile = File(environment.cacheDir, pkg.filename.substringAfterLast('/'))

        setOperation(toolId, ToolPhase.DOWNLOADING, base, "Downloading ${pkg.name}…")
        downloadWithMirrors(pkg, debFile) { read, total ->
            val fraction = if (total != null && total > 0) read.toFloat() / total else null
            val progress = fraction?.let { base + it * span }
            setOperation(toolId, ToolPhase.DOWNLOADING, progress, "Downloading ${pkg.name}…")
        }

        setOperation(toolId, ToolPhase.VERIFYING, base + span * 0.9f, "Verifying ${pkg.name}…")
        try {
            ChecksumVerifier.verify(debFile, pkg.sha256)
        } catch (e: ToolchainException) {
            // Never reuse a file that failed verification.
            debFile.delete()
            throw e
        }

        installer.install(pkg, debFile) { stage ->
            when (stage) {
                ToolchainInstaller.InstallStage.EXTRACTING ->
                    setOperation(
                        toolId, ToolPhase.EXTRACTING, base + span * 0.95f,
                        "Extracting ${pkg.name}…",
                    )
                ToolchainInstaller.InstallStage.INSTALLING ->
                    setOperation(
                        toolId, ToolPhase.INSTALLING, null, "Installing ${pkg.name}…",
                    )
            }
        }
    }

    /** Tries every repository mirror until one serves the package. */
    private suspend fun downloadWithMirrors(
        pkg: RepoPackage,
        dest: File,
        onProgress: suspend (Long, Long?) -> Unit,
    ) {
        val failures = mutableListOf<String>()
        for (baseUrl in repoClient.baseUrls) {
            try {
                downloader.download(
                    "${baseUrl.trimEnd('/')}/${pkg.filename.trimStart('/')}",
                    dest,
                    onProgress,
                )
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                failures += "${baseUrl}: ${e.message}"
            }
        }
        throw ToolchainException(
            "Failed to download ${pkg.name}:\n${failures.joinToString("\n")}"
        )
    }

    /** Returns the loaded index, loading it on demand. */
    private suspend fun ensureIndex(): Map<String, RepoPackage> {
        synchronized(lock) { indexByName }?.let { return it }
        refresh()
        return synchronized(lock) { indexByName }
            ?: throw ToolchainException(
                "Repository index is not available (offline?); cannot resolve packages"
            )
    }

    // ------------------------------------------------------------------
    // State plumbing
    // ------------------------------------------------------------------

    private fun beginOperation(toolId: String, phase: ToolPhase, message: String) {
        synchronized(lock) { activeOps += toolId }
        setOperation(toolId, phase, null, message)
        publishTools()
    }

    private fun setOperation(toolId: String, phase: ToolPhase, progress: Float?, message: String?) {
        _operations.update {
            it + (toolId to ToolOperation(toolId, phase, progress, message))
        }
    }

    /**
     * Ends an operation successfully: the DONE entry stays in [operations]
     * (progress 1.0, the UI can render a finished state) until the next
     * operation for the same tool overwrites it.
     */
    private fun completeOperation(toolId: String, message: String) {
        setOperation(toolId, ToolPhase.DONE, 1f, message)
        synchronized(lock) { activeOps -= toolId }
        publishTools()
    }

    /** Ends an operation without a DONE entry (failure or cancellation). */
    private fun abortOperation(toolId: String) {
        _operations.update { it - toolId }
        synchronized(lock) { activeOps -= toolId }
        publishTools()
    }

    private fun fail(toolId: String, message: String) {
        synchronized(lock) { errors[toolId] = message }
        publishTools()
    }

    private fun publishTools() {
        _tools.value = computeTools()
    }

    private fun computeTools(): List<ToolInfo> = synchronized(lock) {
        val index = indexByName
        ToolCatalog.tools.map { tool ->
            val manifest = manifests[tool.packageName]
            val repoPackage = index?.get(tool.packageName)
            val busy = tool.id in activeOps
            val status = when {
                busy -> ToolStatus.UPDATING_OR_INSTALLING
                errors[tool.id] != null -> ToolStatus.FAILED
                manifest != null -> ToolStatus.INSTALLED
                else -> ToolStatus.NOT_INSTALLED
            }
            ToolInfo(
                id = tool.id,
                displayName = tool.displayName,
                description = tool.description,
                status = status,
                installedVersion = manifest?.version,
                availableVersion = repoPackage?.version,
                error = errors[tool.id],
                optional = tool.optional,
            )
        }
    }

    private fun mutexFor(toolId: String): Mutex =
        toolMutexes.getOrPut(toolId) { Mutex() }

    private fun catalogToolOrThrow(toolId: String): CatalogTool =
        ToolCatalog.byId(toolId)
            ?: throw IllegalArgumentException("Unknown tool id: $toolId")
}
