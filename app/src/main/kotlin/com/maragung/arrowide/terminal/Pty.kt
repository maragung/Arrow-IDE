package com.maragung.arrowide.terminal

/**
 * JNI bridge to the native PTY implementation in `app/src/main/cpp/pty.c`.
 *
 * All functions are blocking; callers are responsible for threading.
 * The native side of every declaration here lives in pty.c with the
 * matching `Java_com_maragung_arrowide_terminal_Pty_*` symbol name.
 */
object Pty {

    @Volatile
    private var libraryLoaded = false

    init {
        // The object's own lazy initialization already guarantees a single
        // load per class loader; the flag guards against any re-entry.
        synchronized(Pty::class.java) {
            if (!libraryLoaded) {
                System.loadLibrary("pty")
                libraryLoaded = true
            }
        }
    }

    /**
     * Forks and execs [cmd] on a new pseudo terminal.
     *
     * @param cmd    absolute path of the binary to execute
     * @param argv   argument vector; argv[0] should be [cmd]
     * @param env    environment as an array of "KEY=VALUE" strings
     * @param cwd    working directory for the child
     * @param rows   initial terminal height in rows
     * @param cols   initial terminal width in columns
     * @param outFds two-element output array; on success outFds[0] is the
     *               master file descriptor, on failure outFds[0] is -1 and
     *               outFds[1] carries the errno value
     * @return the child pid, or -1 on failure (details in [outFds])
     */
    external fun create(
        cmd: String,
        argv: Array<String>,
        env: Array<String>,
        cwd: String,
        rows: Int,
        cols: Int,
        outFds: IntArray,
    ): Long

    /**
     * Blocking read of up to `buf.size` bytes from [fd] into [buf].
     * EINTR is retried internally.
     *
     * @return number of bytes read (0 on EOF) or -1 on error
     */
    external fun readFd(fd: Int, buf: ByteArray): Int

    /**
     * Full write of `buf[off, off+len)` to [fd] with EINTR handling.
     *
     * @return number of bytes written, or -1 if nothing could be written
     */
    external fun writeFd(fd: Int, buf: ByteArray, off: Int, len: Int): Int

    /** Applies a new window size to the pseudo terminal behind [fd]. */
    external fun resizePty(fd: Int, rows: Int, cols: Int)

    /**
     * Blocking waitpid for [pid].
     *
     * @return the exit status (WEXITSTATUS), 128+signal if the child was
     *         killed by a signal, or -1 on waitpid failure
     */
    external fun waitFor(pid: Long): Int

    /** Closes [fd]. */
    external fun closeFd(fd: Int)

    /** Sends signal [sig] to [pid]. */
    external fun killPid(pid: Long, sig: Int)
}
