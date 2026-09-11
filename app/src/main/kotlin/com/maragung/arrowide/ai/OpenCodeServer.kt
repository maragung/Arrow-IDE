package com.maragung.arrowide.ai

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * Spawns and supervises the OpenCode server
 * (`opencode serve --port N --hostname 127.0.0.1`).
 *
 * Pure JVM (no android.* imports — plain [ProcessBuilder], same hermetic
 * environment construction as
 * [com.maragung.arrowide.git.AndroidGitProcess]): the child environment is
 * built from scratch, nothing is inherited from the app process, and every
 * writable location is pointed INSIDE app storage:
 *
 *  - `HOME=<homeDir>`, `PATH=<binaryDir>:/system/bin`
 *  - `XDG_CONFIG_HOME=<homeDir>/.config`
 *  - `XDG_DATA_HOME=<homeDir>/.local/share`
 *  - `XDG_CACHE_HOME=<homeDir>/.cache`
 *  - `TMPDIR=<homeDir>/tmp`
 *
 * stdout and stderr are merged and drained on a daemon thread (so a chatty
 * server cannot deadlock on a full pipe) into a bounded tail buffer that
 * [OpenCodeService] surfaces when startup fails. The class is `open` so
 * unit tests can substitute a fake process without spawning anything.
 *
 * @param binary     the installed opencode executable (binaryDir/opencode)
 * @param homeDir    HOME for the child (filesDir/home); XDG roots live under it
 * @param port       TCP port `serve` binds on 127.0.0.1
 * @param workingDir the directory the process runs in — the open project's
 *                   workspace, so the agent sees the user's files; null runs
 *                   it in [homeDir]
 * @param extraEnv   additional/overriding environment variables (e.g. a
 *                   SSL_CERT_FILE pointing at a CA bundle when the device's
 *                   system store is not visible to a musl binary)
 */
open class OpenCodeServerProcess(
    private val binary: File,
    private val homeDir: File,
    private val port: Int,
    private val workingDir: File? = null,
    private val extraEnv: Map<String, String> = emptyMap(),
) {

    /**
     * The child environment (pure, no I/O). [binary]'s directory is the
     * toolchain prefix bin (`filesDir/usr/bin`), so PATH covers both the
     * toolchain and Android's /system/bin.
     */
    fun envFor(): Map<String, String> {
        val env = LinkedHashMap<String, String>()
        env["HOME"] = homeDir.absolutePath
        env["PATH"] = listOfNotNull(
            binary.absoluteFile.parentFile,
            File("/system/bin"),
        ).joinToString(separator = ":") { it.absolutePath }
        env["XDG_CONFIG_HOME"] = File(homeDir, ".config").absolutePath
        env["XDG_DATA_HOME"] = File(homeDir, ".local/share").absolutePath
        env["XDG_CACHE_HOME"] = File(homeDir, ".cache").absolutePath
        env["TMPDIR"] = File(homeDir, "tmp").absolutePath
        env["TERM"] = "dumb"
        env["NO_COLOR"] = "1"
        env.putAll(extraEnv)
        return env
    }

    /** The full `serve` command line. */
    fun command(): List<String> = listOf(
        binary.absolutePath,
        "serve",
        "--port", port.toString(),
        "--hostname", "127.0.0.1",
    )

    /**
     * The directory the server process is spawned in (pure, no I/O):
     * [workingDir] when set, [homeDir] otherwise.
     */
    fun processDirectory(): File = workingDir ?: homeDir

    /**
     * Creates [homeDir] and its XDG subdirectories. Called by [start]; also
     * called by the installer before the `--version` verification run so the
     * binary never sees a missing HOME.
     */
    fun ensureHomeDirs() {
        for (dir in listOf(
            homeDir,
            File(homeDir, ".config"),
            File(homeDir, ".local/share"),
            File(homeDir, ".cache"),
            File(homeDir, "tmp"),
        )) {
            Files.createDirectories(dir.toPath())
        }
    }

    /**
     * Spawns the server with the hermetic environment, merged output on a
     * drain thread, and returns the raw [Process].
     *
     * @throws IOException when the binary cannot be started
     */
    open fun start(): Process {
        ensureHomeDirs()
        val process = try {
            ProcessBuilder(command())
                .directory(processDirectory())
                .apply {
                    environment().clear()
                    environment().putAll(envFor())
                }
                .redirectErrorStream(true)
                .start()
        } catch (e: IOException) {
            throw IOException(
                "Failed to start opencode (${binary.absolutePath}): ${e.message}",
                e,
            )
        }
        synchronized(lock) {
            current = process
            outputBuffer.setLength(0)
        }
        Thread {
            try {
                process.inputStream.bufferedReader().forEachLine { line ->
                    appendTail(line)
                }
            } catch (e: IOException) {
                // Process died underneath us; keep whatever was drained.
            }
        }.apply {
            isDaemon = true
            name = "opencode-server-drain"
        }.start()
        return process
    }

    /** Whether the spawned process is still running. */
    open fun isAlive(): Boolean = synchronized(lock) { current?.isAlive == true }

    /**
     * The last [maxChars] characters of merged stdout/stderr (the startup
     * error tail [OpenCodeService] reports when readiness polling fails).
     */
    open fun outputTail(maxChars: Int = DEFAULT_TAIL_CHARS): String =
        synchronized(lock) {
            val text = outputBuffer.toString()
            if (text.length <= maxChars) text else text.takeLast(maxChars)
        }

    /**
     * Destroys the process: a polite [Process.destroy], then
     * [Process.destroyForcibly] if it ignores it for [STOP_GRACE_MS].
     */
    open fun stop() {
        val process = synchronized(lock) { current } ?: return
        process.destroy()
        try {
            if (!process.waitFor(STOP_GRACE_MS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
            }
            process.waitFor()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            process.destroyForcibly()
        }
    }

    private fun appendTail(line: String) {
        synchronized(lock) {
            outputBuffer.append(line).append('\n')
            if (outputBuffer.length > MAX_TAIL_CHARS) {
                outputBuffer.delete(0, outputBuffer.length - MAX_TAIL_CHARS)
            }
        }
    }

    private val lock = Any()
    private var current: Process? = null
    private val outputBuffer = StringBuilder()

    private companion object {
        /** Drain buffer cap: enough for a startup backtrace, bounded memory. */
        private const val MAX_TAIL_CHARS = 8_192

        /** Default tail length surfaced in failure reasons. */
        private const val DEFAULT_TAIL_CHARS = 400

        private const val STOP_GRACE_MS = 2_000L
    }
}
