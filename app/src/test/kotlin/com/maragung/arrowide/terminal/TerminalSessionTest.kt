package com.maragung.arrowide.terminal

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pure-JVM wiring test for [TerminalSession] with a fake PTY process.
 *
 * The emulator is driven directly (feedRawBytes) because the reader-thread
 * -> main-thread-handler path does not exist on the JVM.
 */
class TerminalSessionTest {

    private fun newSession(): Pair<TerminalSession, FakePtyProcess> {
        val process = FakePtyProcess()
        val session = TerminalSession(1, process, File("/tmp/work"))
        session.resize(rows = 24, cols = 80)
        return session to process
    }

    @Test
    fun emulatorIsInitializedWithTheRequestedGeometry() {
        val (session, process) = newSession()
        try {
            assertEquals(24 to 80, process.lastResize)
        } finally {
            session.close()
        }
    }

    @Test
    fun fedBytesAppearOnTheScreen() {
        val (session, _) = newSession()
        try {
            session.feedRawBytes("hello".toByteArray())
            assertTrue(session.getTranscriptText().contains("hello"))
        } finally {
            session.close()
        }
    }

    @Test
    fun resizePropagatesToTheProcess() {
        val (session, process) = newSession()
        try {
            session.resize(rows = 30, cols = 100)
            assertEquals(30 to 100, process.lastResize)
        } finally {
            session.close()
        }
    }

    @Test
    fun writesGoStraightToTheProcess() {
        val (session, process) = newSession()
        try {
            session.write("ls -l\r".toByteArray())
            assertEquals("ls -l\r", process.writtenAsString())
        } finally {
            session.close()
        }
    }

    @Test
    fun ctrlModifierMapsTheNextKey() {
        val (session, process) = newSession()
        try {
            session.setCtrlModifier(true)
            session.write('c'.code)
            assertArrayEquals(byteArrayOf(3), process.written.single())

            // The modifier is consumed by the next key press.
            session.setCtrlModifier(true)
            session.write("x")
            assertEquals("x", process.writtenAsString().substring(1))
        } finally {
            session.close()
        }
    }

    @Test
    fun oscTitleUpdatesTheTitleFlow() {
        val (session, _) = newSession()
        try {
            session.feedRawBytes("\u001b]0;my-shell\u0007".toByteArray())
            assertEquals("my-shell", session.title.value)
            assertEquals("my-shell", session.getTitle())
        } finally {
            session.close()
        }
    }

    @Test
    fun exitCodeIsExposed() {
        val (session, process) = newSession()
        try {
            process.onExit.complete(42)
            runBlocking {
                withTimeout(5_000) {
                    while (session.exitCode.value == null) {
                        delay(10)
                    }
                }
            }
            assertEquals(42, session.exitCode.value)
        } finally {
            session.close()
        }
    }

    @Test
    fun closeIsIdempotentAndClosesTheProcess() {
        val (session, process) = newSession()
        session.close()
        session.close()
        assertTrue(process.closed)
    }
}
