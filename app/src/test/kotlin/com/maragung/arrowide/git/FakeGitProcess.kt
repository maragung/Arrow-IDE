package com.maragung.arrowide.git

import java.io.File

/**
 * Scripted [GitProcess] for unit tests: records every call (args, cwd,
 * env, stdin) and answers with queued responses in order — success with
 * empty output when the queue runs dry. Never spawns a real process.
 */
class FakeGitProcess(
    override val gitBinary: File = File("/nonexistent/git"),
) : GitProcess {

    /** One recorded invocation. */
    class Call(
        val args: List<String>,
        val cwd: File,
        val env: Map<String, String>,
        val stdin: ByteArray?,
    )

    private val lock = Any()
    private val responses = ArrayDeque<GitProcessResult>()

    /** Every execute so far, in order. */
    val calls = mutableListOf<Call>()

    /** Invoked while the (fake) process is "running" — lets tests inspect
     *  transient state such as a temporary askpass script. */
    var onCall: ((Call) -> Unit)? = null

    /** When set, every execute throws it (spawn failure). */
    var failure: Exception? = null

    fun enqueue(result: GitProcessResult) {
        synchronized(lock) { responses += result }
    }

    fun enqueueSuccess(stdout: String = "", stderr: String = "") =
        enqueue(GitProcessResult(0, stdout.toByteArray(), stderr.toByteArray()))

    fun enqueueError(exitCode: Int, stderr: String, stdout: String = "") =
        enqueue(GitProcessResult(exitCode, stdout.toByteArray(), stderr.toByteArray()))

    override fun execute(
        args: List<String>,
        cwd: File,
        extraEnv: Map<String, String>,
        stdin: ByteArray?,
    ): GitProcessResult {
        val call = Call(args.toList(), cwd, extraEnv.toMap(), stdin?.copyOf())
        val result: GitProcessResult = synchronized(lock) {
            calls += call
            failure?.let { throw it }
            responses.removeFirstOrNull()
                ?: GitProcessResult(0, ByteArray(0), ByteArray(0))
        }
        onCall?.invoke(call)
        return result
    }
}
