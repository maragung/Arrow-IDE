package com.maragung.arrowide.git

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Result of a git command run through [GitProcess].
 *
 * Raw bytes are kept (paths and commit messages are arbitrary byte
 * sequences); the UTF-8 views cover everything git emits in this app's
 * fixed locale (LC_ALL=C).
 *
 * Note: [equals]/[hashCode] use ByteArray identity semantics inherited
 * from the data class; compare fields individually in tests.
 */
data class GitProcessResult(
    val exitCode: Int,
    val stdout: ByteArray,
    val stderr: ByteArray,
) {
    /** [stdout] decoded as UTF-8. */
    val stdoutText: String
        get() = String(stdout, Charsets.UTF_8)

    /** [stderr] decoded as UTF-8. */
    val stderrText: String
        get() = String(stderr, Charsets.UTF_8)
}

/**
 * Spawns the git binary. Pure JVM (no android.* imports) so the whole
 * git layer stays unit-testable; the real implementation is
 * [AndroidGitProcess].
 */
interface GitProcess {

    /** Absolute path of the git binary this process runs. */
    val gitBinary: File

    /**
     * Runs `gitBinary [args]` in [cwd].
     *
     * @param args      arguments after the binary itself (e.g. `["status", "--porcelain=v1"]`)
     * @param cwd       existing directory to run in
     * @param extraEnv  additional/overriding environment variables
     * @param stdin     bytes piped to the process's stdin when non-null
     *                  (the pipe is closed after writing)
     * @return the process's exit code and captured output
     * @throws java.io.IOException when the binary cannot be started or
     *         [cwd] does not exist
     */
    fun execute(
        args: List<String>,
        cwd: File,
        extraEnv: Map<String, String> = emptyMap(),
        stdin: ByteArray? = null,
    ): GitProcessResult
}

/**
 * Blocking-free wrapper for coroutine call sites: runs [GitProcess.execute]
 * on [kotlinx.coroutines.Dispatchers.IO] and makes cancellation interrupt
 * the waiting thread (the child process is then killed by
 * [AndroidGitProcess] itself, which destroys it on any escaping exception).
 */
suspend fun GitProcess.executeSuspending(
    args: List<String>,
    cwd: File,
    extraEnv: Map<String, String> = emptyMap(),
    stdin: ByteArray? = null,
): GitProcessResult = runInterruptible(Dispatchers.IO) {
    execute(args, cwd, extraEnv, stdin)
}

/**
 * Real [GitProcess]: spawns the toolchain's git binary
 * (`<prefixDir>/bin/git`, installed by the Toolchain Manager) with the
 * same environment the terminal presents, plus git-specific hardening:
 *
 *  - `HOME=<homeDir>`, `PATH=<prefixDir>/bin:<homeDir>/bin:/system/bin`
 *  - `GIT_CONFIG_NOSYSTEM=1` (never read /system/etc/gitconfig)
 *  - `GIT_TERMINAL_PROMPT=0` (never block on an interactive prompt;
 *    credentials come in via GIT_ASKPASS, see [AskpassCredentialsProvider])
 *  - `TERM=dumb`, `LC_ALL=C` (stable, machine-readable output)
 *
 * The child environment is built from scratch — nothing is inherited
 * from the app process. stdout and stderr are drained on dedicated
 * threads so chatty commands cannot deadlock on full pipes. There is no
 * timeout: network operations legitimately run for minutes; cancellation
 * is cooperative (see [executeSuspending]).
 *
 * The class name marks it as the Android-side wiring point, but it is
 * pure JVM (java.lang.ProcessBuilder) and unit-testable anywhere.
 *
 * @param gitBinary git executable to run
 * @param homeDir   HOME for the child (app-private terminal home)
 * @param prefixDir toolchain prefix (`filesDir/usr`)
 */
class AndroidGitProcess(
    gitBinary: File,
    homeDir: File,
    prefixDir: File,
) : GitProcess {

    constructor(homeDir: File, prefixDir: File) : this(
        File(prefixDir, "bin/git"),
        homeDir,
        prefixDir,
    )

    override val gitBinary: File = gitBinary

    private val homeDirPath: String = homeDir.absolutePath
    private val prefixDirPath: String = prefixDir.absolutePath

    /** Child environment (before [extraEnv] overrides). */
    internal val baseEnv: Map<String, String>
        get() = mapOf(
            "HOME" to homeDirPath,
            "PATH" to listOf(
                File(prefixDirPath, "bin"),
                File(homeDirPath, "bin"),
                File("/system/bin"),
            ).joinToString(separator = ":") { it.absolutePath },
            "GIT_CONFIG_NOSYSTEM" to "1",
            "GIT_TERMINAL_PROMPT" to "0",
            "TERM" to "dumb",
            "LC_ALL" to "C",
        )

    override fun execute(
        args: List<String>,
        cwd: File,
        extraEnv: Map<String, String>,
        stdin: ByteArray?,
    ): GitProcessResult {
        if (!cwd.isDirectory) {
            throw IOException("git working directory does not exist: $cwd")
        }
        val process = try {
            ProcessBuilder(listOf(gitBinary.absolutePath) + args)
                .directory(cwd)
                .apply {
                    environment().clear()
                    environment().putAll(baseEnv)
                    environment().putAll(extraEnv)
                }
                .start()
        } catch (e: IOException) {
            throw IOException(
                "Failed to start git (${gitBinary.absolutePath}): ${e.message}",
                e,
            )
        }

        try {
            val stdout = drain(process.inputStream)
            val stderr = drain(process.errorStream)

            // stdin is written on its own thread: the child may exit (or
            // stop reading) while the message is still being piped, and a
            // broken pipe must not lose the captured output.
            stdin?.let { bytes ->
                Thread {
                    try {
                        process.outputStream.use { it.write(bytes) }
                    } catch (e: IOException) {
                        // Child exited early; its exit code and stderr
                        // describe the failure.
                    }
                }.apply { isDaemon = true }.start()
            }

            val exitCode = process.waitFor()
            stdout.join()
            stderr.join()
            return GitProcessResult(
                exitCode = exitCode,
                stdout = stdout.bytes.toByteArray(),
                stderr = stderr.bytes.toByteArray(),
            )
        } catch (t: Throwable) {
            // Interrupted or otherwise aborted: never leave the child running.
            process.destroyForcibly()
            process.waitFor()
            throw t
        }
    }

    /** Reads [stream] fully into [bytes] on a daemon thread. */
    private fun drain(stream: InputStream): Drain {
        val drain = Drain()
        Thread {
            try {
                val buffer = ByteArray(8192)
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    drain.bytes.write(buffer, 0, read)
                }
            } catch (e: IOException) {
                // Stream closed underneath us; keep what was read.
            }
        }.apply { isDaemon = true }.start()
        return drain
    }

    private class Drain {
        val bytes = ByteArrayOutputStream()
    }
}
