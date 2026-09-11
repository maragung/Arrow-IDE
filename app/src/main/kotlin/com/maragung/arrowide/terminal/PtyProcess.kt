package com.maragung.arrowide.terminal

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import java.io.IOException
import kotlin.concurrent.thread

/**
 * One live child process attached to a pseudo terminal.
 *
 * The interface exists so that [TerminalSessionManager] and
 * [TerminalSession] can be exercised in pure-JVM unit tests with a fake
 * process (see `FakePtyProcess` in the test sources); the real
 * implementation is [UnixPtyProcess].
 */
interface PtyProcess {

    /** Process id of the child. */
    val pid: Long

    /**
     * Bytes read from the PTY master. One element per read, in order.
     * The channel is closed by the producer once no more output can be
     * read (child exited and the kernel buffer drained).
     */
    val onOutput: ReceiveChannel<ByteArray>

    /** Completes with the child's exit status once it has been reaped. */
    val onExit: CompletableDeferred<Int>

    /** Whether the child has not exited yet. */
    val isAlive: Boolean

    /** Thread-safe write to the child's standard input. */
    fun write(bytes: ByteArray)

    /** Applies a new window size to the terminal. */
    fun resize(rows: Int, cols: Int)

    /** Unconditionally kills the child (SIGKILL). */
    fun kill()

    /**
     * Gracefully closes the terminal: hangs up (SIGHUP) the child and
     * releases the master fd once all output has been drained. Safe to call
     * more than once.
     */
    fun close()
}

/**
 * Real PTY process backed by the native code in `app/src/main/cpp/pty.c`.
 *
 * Owns three resources:
 *  - the master file descriptor,
 *  - a daemon reader thread pumping master -> [onOutput],
 *  - a daemon waiter thread reaping the child -> [onExit].
 */
class UnixPtyProcess(
    cmd: String,
    argv: Array<String>,
    env: Array<String>,
    cwd: String,
    rows: Int,
    cols: Int,
) : PtyProcess {

    companion object {
        private const val READ_CHUNK_BYTES = 8192

        /** POSIX signal numbers (bionic values). */
        private const val SIGHUP = 1
        private const val SIGKILL = 9
    }

    private val masterFd: Int
    override val pid: Long

    /**
     * Unbounded so the reader thread (plain thread, not a coroutine) can
     * hand over chunks with non-blocking [Channel.trySend] without ever
     * dropping terminal output; the consumer drains quickly and the reader
     * stops at EOF/EIO anyway.
     */
    private val outputChannel = Channel<ByteArray>(capacity = Channel.UNLIMITED)
    override val onOutput: ReceiveChannel<ByteArray> = outputChannel

    override val onExit = CompletableDeferred<Int>()

    private val writeLock = Any()
    private val fdLock = Any()
    private var fdOpen = true

    @Volatile
    private var closed = false

    init {
        val fds = IntArray(2)
        val childPid = Pty.create(cmd, argv, env, cwd, rows, cols, fds)
        if (childPid < 0) {
            throw IOException(
                "Failed to start process '$cmd' in '$cwd' (errno ${fds[1]})"
            )
        }
        masterFd = fds[0]
        pid = childPid

        thread(name = "pty-reader-$pid", isDaemon = true) { readLoop() }
        thread(name = "pty-waiter-$pid", isDaemon = true) { waitLoop() }
    }

    override val isAlive: Boolean
        get() = !onExit.isCompleted

    override fun write(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        synchronized(writeLock) {
            val open = synchronized(fdLock) { fdOpen }
            if (!open) return
            Pty.writeFd(masterFd, bytes, 0, bytes.size)
        }
    }

    override fun resize(rows: Int, cols: Int) {
        val open = synchronized(fdLock) { fdOpen }
        if (open) {
            Pty.resizePty(masterFd, rows, cols)
        }
    }

    override fun kill() {
        if (!onExit.isCompleted) {
            Pty.killPid(pid, SIGKILL)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        if (!onExit.isCompleted) {
            // Hang up the child; its death makes the blocked read on the
            // master return EIO, which lets the reader thread drain, close
            // the channel and release the fd.
            Pty.killPid(pid, SIGHUP)
            // Escalate if the child ignores SIGHUP. It must die so the
            // reader thread is guaranteed to unblock.
            thread(name = "pty-close-escalator-$pid", isDaemon = true) {
                repeat(50) {
                    if (onExit.isCompleted) return@thread
                    Thread.sleep(10)
                }
                if (!onExit.isCompleted) {
                    Pty.killPid(pid, SIGKILL)
                }
            }
        }
    }

    private fun readLoop() {
        val buffer = ByteArray(READ_CHUNK_BYTES)
        try {
            while (true) {
                val n = Pty.readFd(masterFd, buffer)
                if (n <= 0) {
                    // 0 = EOF, -1 = error (EIO once the child side is gone).
                    break
                }
                val result = outputChannel.trySend(buffer.copyOf(n))
                if (result.isClosed) {
                    // Consumer went away; stop reading and release the fd.
                    break
                }
            }
        } catch (t: Throwable) {
            // The consumer went away (channel closed) or the fd broke.
        } finally {
            outputChannel.close()
            closeMasterOnce()
        }
    }

    private fun waitLoop() {
        val status = Pty.waitFor(pid)
        onExit.complete(status)
    }

    private fun closeMasterOnce() {
        synchronized(fdLock) {
            if (fdOpen) {
                Pty.closeFd(masterFd)
                fdOpen = false
            }
        }
    }
}
