package com.maragung.arrowide.terminal

import jackpal.androidterm.emulatorview.TermSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.io.PipedInputStream
import java.io.PipedOutputStream

/**
 * A live terminal: one PTY child process paired with the vendored
 * jackpal terminal emulator.
 *
 * This class extends the vendored [TermSession] so the vendored
 * `EmulatorView` can attach to it directly (the view pulls the emulator
 * instance out of the session itself). Data flow:
 *
 *   PTY master -> UnixPtyProcess reader thread -> pipe -> TermSession
 *   reader thread -> main-thread handler -> emulator.append()
 *
 * and input:
 *
 *   view/IME -> write(...) overrides -> PTY master (direct, thread-safe)
 *
 * @param id      stable, unique id within the manager
 * @param process the PTY child (interface, so tests can inject a fake)
 * @param cwd     the working directory the process was started in
 */
class TerminalSession(
    val id: Int,
    val process: PtyProcess,
    val cwd: File,
) : TermSession(/* exitOnEOF = */ true) {

    companion object {
        private const val PIPE_BUFFER_BYTES = 64 * 1024
    }

    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val pipeIn = PipedInputStream(PIPE_BUFFER_BYTES)
    private val pipeOut = PipedOutputStream(pipeIn)

    private val _exitCode = MutableStateFlow<Int?>(null)

    /** Exit status of the process, or null while it is running. */
    val exitCode: StateFlow<Int?> = _exitCode

    private val _ctrlModifier = MutableStateFlow(false)

    /**
     * CTRL modifier armed for the next key press. The UI toggles this and
     * the write overrides below consume it, mapping the next letter to its
     * control character.
     */
    val ctrlModifier: StateFlow<Boolean> = _ctrlModifier

    private val _title = MutableStateFlow(defaultTitle())

    /** Session title; updated when the child sets it via OSC 0/2. */
    val title: StateFlow<String> = _title

    @Volatile
    private var closed = false

    /** Completes with the child's exit status. */
    val onExit: CompletableDeferred<Int>
        get() = process.onExit

    /** Whether the underlying process has not exited yet. */
    val isAlive: Boolean
        get() = process.isAlive

    init {
        setDefaultUTF8Mode(true)
        setTermIn(pipeIn)
        // Deliberately no setTermOut(): every write path is overridden below
        // and writes straight to the PTY, which is thread-safe and lower
        // latency than the vendored writer-thread queue.
        setTitle(defaultTitle())

        sessionScope.launch { collectProcessOutput() }
        sessionScope.launch { awaitProcessExit() }
    }

    // ------------------------------------------------------------------
    // Output: PTY -> emulator
    // ------------------------------------------------------------------

    private suspend fun collectProcessOutput() {
        try {
            for (chunk in process.onOutput) {
                // Blocking write: when the emulator (main thread) is slow,
                // backpressure propagates all the way to the child process.
                pipeOut.write(chunk)
            }
        } catch (e: IOException) {
            // The pipe was closed underneath us (session finished).
        }
        closePipeQuietly()
    }

    private suspend fun awaitProcessExit() {
        val code = process.onExit.await()
        _exitCode.value = code
        // Deliver EOF to the TermSession reader so it finishes the session.
        closePipeQuietly()
    }

    private fun closePipeQuietly() {
        try {
            pipeOut.close()
        } catch (e: IOException) {
            // already closed
        }
    }

    // ------------------------------------------------------------------
    // Input: view/IME -> PTY
    // ------------------------------------------------------------------

    /** Raw write to the process. Does not apply the CTRL modifier mapping. */
    fun write(bytes: ByteArray) {
        write(bytes, 0, bytes.size)
    }

    /** Raw write to the process. Does not apply the CTRL modifier mapping. */
    override fun write(data: ByteArray?, offset: Int, count: Int) {
        if (data == null || count <= 0) return
        val bytes = if (offset == 0 && count == data.size) {
            data
        } else {
            data.copyOfRange(offset, offset + count)
        }
        process.write(bytes)
        _ctrlModifier.value = false
    }

    override fun write(data: String?) {
        if (data.isNullOrEmpty()) return
        val mapped = mapCtrlForString(data)
        process.write(mapped.toByteArray(Charsets.UTF_8))
    }

    override fun write(codePoint: Int) {
        val mapped = if (_ctrlModifier.value) {
            _ctrlModifier.value = false
            ctrlCodePoint(codePoint)
        } else {
            codePoint
        }
        if (mapped in 0..127) {
            process.write(byteArrayOf(mapped.toByte()))
        } else {
            process.write(String(Character.toChars(mapped)).toByteArray(Charsets.UTF_8))
        }
    }

    private fun mapCtrlForString(data: String): String {
        if (!_ctrlModifier.value || data.isEmpty()) return data
        _ctrlModifier.value = false
        val first = data.codePointAt(0)
        val mapped = ctrlCodePoint(first)
        if (mapped == first) return data
        return String(Character.toChars(mapped)) + data.substring(Character.charCount(first))
    }

    /** Classic xterm mapping of a code point to its control character. */
    private fun ctrlCodePoint(codePoint: Int): Int = when (codePoint) {
        in 'a'.code..'z'.code -> codePoint - 'a'.code + 1
        in 'A'.code..'Z'.code -> codePoint - 'A'.code + 1
        ' '.code, '@'.code -> 0
        '['.code -> 27
        '\\'.code -> 28
        ']'.code -> 29
        '^'.code -> 30
        '_'.code -> 31
        '?'.code -> 127
        else -> codePoint
    }

    /** Arms or disarms the CTRL modifier (see [ctrlModifier]). */
    fun setCtrlModifier(active: Boolean) {
        _ctrlModifier.value = active
    }

    // ------------------------------------------------------------------
    // Title tracking
    // ------------------------------------------------------------------

    override fun setTitle(title: String?) {
        super.setTitle(title)
        if (!title.isNullOrEmpty()) {
            _title.value = title
        }
    }

    private fun defaultTitle(): String = "sh (${cwd.name.ifEmpty { "/" }})"

    // ------------------------------------------------------------------
    // Resize
    // ------------------------------------------------------------------

    /**
     * Resize both the emulator and the PTY. Must be called on the main
     * thread (the emulator is not thread-safe).
     */
    fun resize(rows: Int, cols: Int) {
        updateSize(cols, rows)
    }

    override fun updateSize(columns: Int, rows: Int) {
        super.updateSize(columns, rows)
        process.resize(rows, columns)
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    /** Unconditionally kills the child process (SIGKILL). */
    fun kill() {
        process.kill()
    }

    /**
     * Gracefully finishes the session: closes the process, the pipes and
     * the emulator buffers. Safe to call more than once.
     */
    fun close() {
        if (closed) return
        closed = true
        process.close()
        // Unblocks the output collector if it is stuck on a full pipe.
        try {
            pipeIn.close()
        } catch (e: IOException) {
            // already closed
        }
        if (isRunning) {
            try {
                finish()
            } catch (t: Throwable) {
                // The emulator was never initialized or already finished.
            }
        }
        sessionScope.cancel()
    }

    // ------------------------------------------------------------------
    // Test seam
    // ------------------------------------------------------------------

    /**
     * Feeds bytes into the emulator directly, bypassing the PTY. Used by
     * pure-JVM unit tests (where no main-thread handler exists).
     */
    internal fun feedRawBytes(data: ByteArray) {
        appendToEmulator(data, 0, data.size)
        notifyUpdate()
    }
}
