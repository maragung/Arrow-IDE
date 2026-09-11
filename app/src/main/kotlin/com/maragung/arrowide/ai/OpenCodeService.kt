package com.maragung.arrowide.ai

import com.maragung.arrowide.github.HttpExchange
import com.maragung.arrowide.github.HttpTransport
import com.maragung.arrowide.github.exchangeSuspending
import com.maragung.arrowide.toolchain.HttpPackageDownloader
import com.maragung.arrowide.toolchain.PackageDownloader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.GZIPInputStream

/**
 * The facade the AI UI codes against: it owns the OpenCode binary's
 * lifecycle (download → extract → verify, mirroring
 * [com.maragung.arrowide.toolchain.ToolchainManager]'s
 * download-to-cache-then-install flow) and the on-device server process,
 * and hands out an [OpenCodeClient] while the server is
 * [OpenCodeServerState.Ready].
 *
 * Pure JVM (no android.* imports): directories arrive as [File]s, the
 * architecture decision arrives as [archAssetSuffix]
 * ("arm64-musl"/"x64-musl") instead of a Build.* read, and the process
 * seam ([commandRunner], [processFactory]) is injectable so the whole
 * class is unit-testable without spawning anything.
 *
 * Construction performs no I/O beyond a file-existence stat. All blocking
 * work runs on [ioDispatcher]. Expected failures are [AiResult] values;
 * only cancellation escapes as an exception.
 *
 * @param transport       HTTP seam shared with the GitHub layer (release
 *                        lookups and server health polling)
 * @param binaryDir       where opencode is installed (filesDir/usr/bin)
 * @param homeDir         HOME for the server process (filesDir/home); the
 *                        XDG roots live under it
 * @param cacheDir        cache for downloaded tarballs (cleaned after
 *                        each install, success or failure)
 * @param ioDispatcher    dispatcher for blocking I/O
 * @param archAssetSuffix musl asset suffix for this device
 *                        ("arm64-musl" or "x64-musl")
 * @param commandRunner   runs a command to completion and returns its exit
 *                        code; the installer's `--version` verification
 *                        seam (default: hermetic ProcessBuilder)
 * @param downloader      streaming downloader for the release tarball
 *                        (default: the toolchain layer's
 *                        [HttpPackageDownloader], with progress)
 * @param processFactory  creates the server process wrapper (binary, home,
 *                        port, workingDir — test seam)
 * @param portPicker      picks the server port (default: a free port via
 *                        [ServerSocket]; there is an inherent — accepted
 *                        and documented — race between closing the probe
 *                        socket and the server binding)
 * @param startTimeoutMs  readiness polling budget
 * @param pollIntervalMs  delay between health polls
 */
class OpenCodeService(
    private val transport: HttpTransport,
    private val binaryDir: File,
    private val homeDir: File,
    private val cacheDir: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val archAssetSuffix: String = "arm64-musl",
    private val commandRunner: (List<String>, Map<String, String>, File) -> Int =
        ::runCommandToCompletion,
    private val downloader: PackageDownloader = HttpPackageDownloader(),
    private val processFactory: (File, File, Int, File?) -> OpenCodeServerProcess =
        { binary, home, port, workingDir ->
            OpenCodeServerProcess(binary, home, port, workingDir)
        },
    private val portPicker: () -> Int = ::pickFreePort,
    private val startTimeoutMs: Long = 30_000,
    private val pollIntervalMs: Long = 500,
) {

    /** The installed binary path (binaryDir/opencode). */
    val binaryFile: File
        get() = File(binaryDir, "opencode")

    private val _installState = MutableStateFlow(initialInstallState())

    /** Coarse install lifecycle for the UI. */
    val installState: StateFlow<OpenCodeInstallState> = _installState.asStateFlow()

    private val _installProgress = MutableStateFlow(OpenCodeInstallProgress(initialInstallState()))

    /** Install lifecycle with message and 0..1 progress. */
    val installProgress: StateFlow<OpenCodeInstallProgress> = _installProgress.asStateFlow()

    private val _serverState = MutableStateFlow<OpenCodeServerState>(OpenCodeServerState.Stopped)

    /** Server lifecycle: Stopped / Starting / Ready(port, workingDir) / Failed(reason). */
    val serverState: StateFlow<OpenCodeServerState> = _serverState.asStateFlow()

    private val installMutex = Mutex()
    private val serverMutex = Mutex()

    @Volatile
    private var serverProcess: OpenCodeServerProcess? = null

    @Volatile
    private var currentClient: OpenCodeClient? = null

    /** Whether the binary is present on disk. */
    fun isInstalled(): Boolean = binaryFile.isFile

    /**
     * Resolves the latest sst/opencode release and picks the musl asset
     * for [archAssetSuffix] via GitHub's releases/latest endpoint (public,
     * no auth). The release is not cached — call before [install] to show
     * the available version, or let [install] resolve it itself.
     */
    suspend fun refreshLatestRelease(): AiResult<OpenCodeReleaseInfo> {
        val request = HttpExchange(
            method = "GET",
            url = RELEASES_LATEST_URL,
            requestHeaders = mapOf("Accept" to "application/vnd.github+json"),
            requestBody = null,
        )
        return try {
            val response = transport.exchangeSuspending(request)
            if (response.statusCode !in 200..299) {
                return AiResult.Error(
                    response.statusCode,
                    "GitHub request failed (HTTP ${response.statusCode})",
                )
            }
            val release = try {
                opencodeJson.decodeFromString<OpenCodeReleaseDto>(response.bodyText)
            } catch (e: IllegalArgumentException) {
                return AiResult.Error(null, "Could not parse GitHub release response")
            }
            val assetName = "opencode-linux-$archAssetSuffix.tar.gz"
            val asset = release.assets.firstOrNull { it.name == assetName }
                ?: return AiResult.Error(
                    null,
                    "Release ${release.tagName} has no asset '$assetName' " +
                        "(architecture unsupported?)",
                )
            AiResult.Ok(
                OpenCodeReleaseInfo(
                    tagName = release.tagName,
                    assetName = asset.name,
                    assetUrl = asset.browserDownloadUrl,
                    assetSizeBytes = asset.size,
                ),
            )
        } catch (e: IOException) {
            AiResult.Error(null, e.message ?: "Network error")
        }
    }

    /**
     * Installs (or updates) the OpenCode binary:
     *
     *  1. resolve the latest release and download the tarball into
     *     [cacheDir] (streamed, with progress on [installProgress]);
     *  2. extract ONLY the `opencode` entry (wherever it sits in the
     *     archive — usually under a top-level directory) into a staging
     *     file inside [binaryDir];
     *  3. verify by running `<staging> --version` (must exit 0) under the
     *     same hermetic environment the server will use;
     *  4. atomically move the staging file over [binaryFile].
     *
     * Rollback: the staging file is deleted on any failure and a previous
     * good binary is never touched (the move in step 4 is the only
     * mutation of [binaryFile]). The cache is cleaned on success and on
     * failure alike. State flow: INSTALLING → INSTALLED / FAILED.
     */
    suspend fun install(): AiResult<Unit> = installMutex.withLock {
        setInstall(OpenCodeInstallState.INSTALLING, "Resolving latest release…", 0f)
        val release = refreshLatestRelease()
        val info = when (release) {
            is AiResult.Error -> {
                failInstall(release.message)
                return release
            }
            is AiResult.Ok -> release.value
        }
        val archive = File(cacheDir, info.assetName)
        val staging = File(binaryDir, "opencode.new")
        try {
            withContext(ioDispatcher) {
                Files.createDirectories(binaryDir.toPath())
                Files.createDirectories(cacheDir.toPath())
                OpenCodeServerProcess(binaryFile, homeDir, port = 0).ensureHomeDirs()

                setInstall(
                    OpenCodeInstallState.INSTALLING,
                    "Downloading ${info.assetName}…",
                    DOWNLOAD_PROGRESS_START,
                )
                downloader.download(info.assetUrl, archive) { read, total ->
                    val fraction = total?.takeIf { it > 0 }?.let { read.toFloat() / it }
                    setInstall(
                        OpenCodeInstallState.INSTALLING,
                        "Downloading ${info.assetName}…",
                        DOWNLOAD_PROGRESS_START +
                            (fraction ?: 0f) * DOWNLOAD_PROGRESS_SPAN,
                    )
                }

                setInstall(OpenCodeInstallState.INSTALLING, "Extracting…", 0.9f)
                extractBinary(archive, staging)

                setInstall(OpenCodeInstallState.INSTALLING, "Verifying ${info.tagName}…", 0.95f)
                val env = OpenCodeServerProcess(staging, homeDir, port = 0).envFor()
                val exitCode = commandRunner(
                    listOf(staging.absolutePath, "--version"),
                    env,
                    homeDir,
                )
                if (exitCode != 0) {
                    throw IOException("opencode --version exited with code $exitCode")
                }

                // Commit: only now does the previous binary (if any) get
                // replaced, and only via an atomic same-directory move.
                Files.move(
                    staging.toPath(),
                    binaryFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
            setInstall(OpenCodeInstallState.INSTALLED, "Installed ${info.tagName}", 1f)
            AiResult.Ok(Unit)
        } catch (e: CancellationException) {
            setInstall(
                initialInstallState(),
                "Install cancelled",
                null,
            )
            throw e
        } catch (e: Exception) {
            val message = "Install failed: ${e.message ?: e.javaClass.simpleName}"
            failInstall(message)
            AiResult.Error(null, message)
        } finally {
            // Never leave a partial download or staging file behind — even
            // while unwinding a cancellation.
            withContext(ioDispatcher + NonCancellable) {
                archive.delete()
                staging.delete()
            }
        }
    }

    /**
     * Returns the current [OpenCodeServerState], spawning the server when
     * it is not already [OpenCodeServerState.Ready] for [workingDir]: picks
     * a free port, starts the process IN [workingDir] (the open project's
     * workspace; null = the server's HOME, preserving the pre-workspace
     * behavior), then polls GET /global/health until it answers 200 (within
     * [startTimeoutMs], one poll every [pollIntervalMs]).
     *
     * The workspace is baked into the process at spawn time, so a server
     * that is Ready for a DIFFERENT directory is stopped and replaced with
     * one in the requested directory; a call for the same directory (or
     * both null) reuses the running server. Directory identity is
     * [File.absoluteFile] equality — same path, different [File] instance,
     * counts as the same directory.
     *
     * Failure modes: not installed, the process exiting during startup,
     * or the health check never passing — each lands in
     * [OpenCodeServerState.Failed] with a truncated output tail, and the
     * process is stopped. Safe to call repeatedly; concurrent calls
     * serialize on a mutex and the loser reuses the winner's server.
     */
    suspend fun ensureServer(workingDir: File? = null): OpenCodeServerState {
        (serverState.value as? OpenCodeServerState.Ready)
            ?.takeIf { sameDirectory(it.workingDir, workingDir) }
            ?.let { return it }
        if (!isInstalled()) {
            val failed = OpenCodeServerState.Failed(
                "OpenCode is not installed — install it first",
            )
            _serverState.value = failed
            return failed
        }
        return serverMutex.withLock {
            (serverState.value as? OpenCodeServerState.Ready)
                ?.takeIf { sameDirectory(it.workingDir, workingDir) }
                ?.let { return it }
            if (serverState.value is OpenCodeServerState.Ready) {
                // Ready for a different directory: the running server
                // cannot be re-pointed at another workspace, so it is
                // replaced. Same-directory reuse returned above.
                stopServerLocked()
            }
            _serverState.value = OpenCodeServerState.Starting

            val port = withContext(ioDispatcher) { runInterruptible { portPicker() } }
            val process = processFactory(binaryFile, homeDir, port, workingDir)
            try {
                withContext(ioDispatcher) { runInterruptible { process.start() } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val failed = OpenCodeServerState.Failed(
                    "Could not start opencode: ${e.message ?: e.javaClass.simpleName}",
                )
                _serverState.value = failed
                return failed
            }
            serverProcess = process

            val state = pollUntilReady(process, port, workingDir)
            _serverState.value = state
            if (state is OpenCodeServerState.Ready) {
                currentClient = OpenCodeClient(
                    transport = transport,
                    baseUrl = "http://127.0.0.1:${state.port}",
                    ioDispatcher = ioDispatcher,
                )
            } else {
                currentClient = null
                serverProcess = null
                withContext(ioDispatcher + NonCancellable) {
                    runInterruptible { process.stop() }
                }
            }
            state
        }
    }

    /** Stops the server process (if any) and resets the state to Stopped. */
    suspend fun stopServer() {
        serverMutex.withLock { stopServerLocked() }
    }

    /**
     * Teardown for a caller that already holds [serverMutex] (the public
     * [stopServer], and the replacement inside [ensureServer]).
     */
    private suspend fun stopServerLocked() {
        val process = serverProcess
        serverProcess = null
        currentClient = null
        _serverState.value = OpenCodeServerState.Stopped
        if (process != null) {
            withContext(ioDispatcher + NonCancellable) {
                runInterruptible { process.stop() }
            }
        }
    }

    /**
     * The typed client for the running server, or null when it is not
     * [OpenCodeServerState.Ready]. Also detects a server that died since
     * the last state update and flips the state to Failed.
     */
    fun client(): OpenCodeClient? {
        val client = currentClient ?: return null
        val process = serverProcess
        if (process != null && !process.isAlive()) {
            currentClient = null
            _serverState.value = OpenCodeServerState.Failed(
                "OpenCode server process exited",
            )
            return null
        }
        return client
    }

    // -------------------------------------------------------------------
    // Install pipeline internals
    // -------------------------------------------------------------------

    /**
     * Extracts the `opencode` entry of the release tarball into [dest].
     * Only that one entry is ever written (to a caller-chosen path), so a
     * hostile archive cannot place files anywhere. The entry is matched by
     * basename to tolerate the top-level directory GitHub wraps it in.
     */
    private fun extractBinary(archive: File, dest: File) {
        GZIPInputStream(archive.inputStream().buffered()).use { gzip ->
            TarArchiveInputStream(gzip, "UTF-8").use { tar ->
                var entry = tar.nextEntry
                while (entry != null) {
                    val name = entry.name.trim().removePrefix("./").removePrefix("/")
                    if (entry.isFile && File(name).name == BINARY_ENTRY_NAME) {
                        Files.createDirectories(dest.parentFile.toPath())
                        Files.deleteIfExists(dest.toPath())
                        Files.newOutputStream(dest.toPath()).use { output ->
                            copyEntryPayload(entry.size, tar, output, archive)
                        }
                        dest.setExecutable(true, false)
                        return
                    }
                    entry = tar.nextEntry
                }
            }
        }
        throw IOException("No 'opencode' entry found in ${archive.name}")
    }

    /** Copies exactly [size] bytes of the current tar entry to [output]. */
    private fun copyEntryPayload(size: Long, tar: TarArchiveInputStream, output: OutputStream, archive: File) {
        val buffer = ByteArray(BUFFER_BYTES)
        var remaining = size
        while (remaining > 0) {
            val chunk = if (remaining >= BUFFER_BYTES) BUFFER_BYTES else remaining.toInt()
            val read = tar.read(buffer, 0, chunk)
            if (read < 0) {
                throw IOException("Truncated 'opencode' entry in ${archive.name}")
            }
            output.write(buffer, 0, read)
            remaining -= read
        }
    }

    // -------------------------------------------------------------------
    // Server readiness polling
    // -------------------------------------------------------------------

    private suspend fun pollUntilReady(
        process: OpenCodeServerProcess,
        port: Int,
        workingDir: File? = null,
    ): OpenCodeServerState {
        val healthUrl = "http://127.0.0.1:$port/global/health"
        val deadline = System.nanoTime() + startTimeoutMs * 1_000_000
        while (true) {
            if (!process.isAlive()) {
                return OpenCodeServerState.Failed(
                    "opencode exited during startup — ${process.outputTail()}",
                )
            }
            try {
                val response = transport.exchangeSuspending(
                    HttpExchange(
                        method = "GET",
                        url = healthUrl,
                        requestHeaders = mapOf("Accept" to "application/json"),
                        requestBody = null,
                    ),
                )
                if (response.statusCode == 200) {
                    return OpenCodeServerState.Ready(port, workingDir)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                // Not listening yet — keep polling.
            }
            if (System.nanoTime() >= deadline) {
                return OpenCodeServerState.Failed(
                    "opencode did not become healthy within ${startTimeoutMs} ms — " +
                        process.outputTail(),
                )
            }
            delay(pollIntervalMs)
        }
    }

    // -------------------------------------------------------------------
    // State plumbing
    // -------------------------------------------------------------------

    private fun initialInstallState(): OpenCodeInstallState =
        if (binaryFile.isFile) {
            OpenCodeInstallState.INSTALLED
        } else {
            OpenCodeInstallState.NOT_INSTALLED
        }

    private fun setInstall(state: OpenCodeInstallState, message: String?, progress: Float?) {
        _installState.value = state
        _installProgress.value = OpenCodeInstallProgress(state, message, progress)
    }

    private fun failInstall(message: String) {
        setInstall(OpenCodeInstallState.FAILED, message, null)
    }

    private companion object {
        const val RELEASES_LATEST_URL = "https://api.github.com/repos/sst/opencode/releases/latest"
        const val BINARY_ENTRY_NAME = "opencode"
        const val BUFFER_BYTES = 64 * 1024

        /** Progress window the download phase maps onto. */
        const val DOWNLOAD_PROGRESS_START = 0.05f
        const val DOWNLOAD_PROGRESS_SPAN = 0.8f
    }
}

/**
 * Default [OpenCodeService.commandRunner]: runs [command] in [cwd] with
 * exactly [env] (hermetic — nothing inherited), merged output drained on
 * a daemon thread so a chatty child cannot deadlock, and returns the exit
 * code. The child is destroyed on any escaping exception.
 */
private fun runCommandToCompletion(
    command: List<String>,
    env: Map<String, String>,
    cwd: File,
): Int {
    val process = ProcessBuilder(command)
        .directory(cwd)
        .apply {
            environment().clear()
            environment().putAll(env)
        }
        .redirectErrorStream(true)
        .start()
    try {
        val output = ByteArrayOutputStream()
        val drain = Thread {
            try {
                process.inputStream.use { input ->
                    output.write(input.readBytes())
                }
            } catch (e: IOException) {
                // Child exited early; its exit code is the verdict.
            }
        }.apply {
            isDaemon = true
            name = "opencode-verify-drain"
        }
        drain.start()
        val exitCode = process.waitFor()
        drain.join()
        return exitCode
    } catch (t: Throwable) {
        process.destroyForcibly()
        process.waitFor()
        throw t
    }
}

/**
 * Picks a free TCP port by opening [ServerSocket] on port 0 and closing
 * it again. There is an inherent (accepted) race: nothing reserves the
 * port between the close and the server binding it — on a single-user
 * Android device the collision probability is negligible, and a bind
 * failure surfaces as a normal startup Failed state.
 */
private fun pickFreePort(): Int = ServerSocket(0).use { socket ->
    socket.reuseAddress = true
    socket.localPort
}

/**
 * Whether two requested working directories denote the same location:
 * both null (the HOME default), or equal absolute paths. Two [File]
 * instances for the same path count as the same directory; the comparison
 * does NOT normalize `..`/symlinks (neither does [File], and the app only
 * ever hands in canonical app-storage or project paths).
 */
private fun sameDirectory(a: File?, b: File?): Boolean =
    a?.absoluteFile == b?.absoluteFile
