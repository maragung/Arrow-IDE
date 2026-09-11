package com.maragung.arrowide.terminal

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Pure-JVM fake of [PtyProcess] used by the manager/session unit tests.
 */
class FakePtyProcess : PtyProcess {

    private companion object {
        val COUNTER = AtomicInteger(0)
    }

    override val pid: Long = 100_000L + COUNTER.incrementAndGet()

    private val outputChannel = Channel<ByteArray>(capacity = Channel.UNLIMITED)
    override val onOutput: ReceiveChannel<ByteArray> = outputChannel

    override val onExit = CompletableDeferred<Int>()

    override val isAlive: Boolean
        get() = !onExit.isCompleted

    /** Every byte chunk written to the fake process, in order. */
    val written = CopyOnWriteArrayList<ByteArray>()

    @Volatile
    var killed = false
        private set

    @Volatile
    var closed = false
        private set

    @Volatile
    var lastResize: Pair<Int, Int>? = null
        private set

    override fun write(bytes: ByteArray) {
        written += bytes
    }

    override fun resize(rows: Int, cols: Int) {
        lastResize = rows to cols
    }

    override fun kill() {
        killed = true
        onExit.complete(137)
    }

    override fun close() {
        closed = true
        onExit.complete(0)
    }

    /** Bytes written so far, concatenated. */
    fun writtenAsString(): String =
        written.flatMap { it.toList() }.map { it.toInt().toChar() }.joinToString("")
}
