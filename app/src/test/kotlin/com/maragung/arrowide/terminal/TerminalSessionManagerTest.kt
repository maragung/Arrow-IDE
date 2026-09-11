package com.maragung.arrowide.terminal

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class TerminalSessionManagerTest {

    private class FakePtyFactory(root: File) : PtyFactory {
        override val defaultCwd: File = root
        private val counter = AtomicInteger(0)
        val processes = CopyOnWriteArrayList<FakePtyProcess>()

        override fun createSession(cwd: File, rows: Int, cols: Int): TerminalSession {
            val process = FakePtyProcess()
            processes += process
            return TerminalSession(counter.incrementAndGet(), process, cwd)
        }
    }

    private fun newManager(): Pair<TerminalSessionManager, FakePtyFactory> {
        val root = Files.createTempDirectory("arrow-term-mgr").toFile()
        val factory = FakePtyFactory(root)
        return TerminalSessionManager(factory) to factory
    }

    @Test
    fun createSessionAddsToTheList() {
        val (manager, _) = newManager()
        val cwd = Files.createTempDirectory("arrow-term-cwd").toFile()

        val session = manager.createSession(cwd, maxSessions = 4)

        assertEquals(listOf(session), manager.sessions.value)
        assertEquals(cwd, session.cwd)
    }

    @Test
    fun sessionLimitIsEnforced() {
        val (manager, _) = newManager()
        val cwd = Files.createTempDirectory("arrow-term-cwd").toFile()

        manager.createSession(cwd, maxSessions = 2)
        manager.createSession(cwd, maxSessions = 2)
        try {
            manager.createSession(cwd, maxSessions = 2)
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertEquals("Terminal session limit reached (2)", e.message)
        }
        assertEquals(2, manager.sessions.value.size)
    }

    @Test
    fun closingASessionFreesLimitSpace() {
        val (manager, _) = newManager()
        val cwd = Files.createTempDirectory("arrow-term-cwd").toFile()

        val first = manager.createSession(cwd, maxSessions = 2)
        val second = manager.createSession(cwd, maxSessions = 2)
        manager.closeSession(first)

        assertEquals(listOf(second), manager.sessions.value)
        // Space was freed, so a third session is allowed.
        manager.createSession(cwd, maxSessions = 2)
        assertEquals(2, manager.sessions.value.size)
    }

    @Test
    fun closeSessionRemovesAndClosesIt() {
        val (manager, factory) = newManager()
        val cwd = Files.createTempDirectory("arrow-term-cwd").toFile()
        val session = manager.createSession(cwd, maxSessions = 4)

        manager.closeSession(session)

        assertTrue(manager.sessions.value.isEmpty())
        assertTrue(factory.processes.single().closed)
    }

    @Test
    fun killSessionRemovesAndKillsIt() {
        val (manager, factory) = newManager()
        val cwd = Files.createTempDirectory("arrow-term-cwd").toFile()
        val session = manager.createSession(cwd, maxSessions = 4)

        manager.killSession(session)

        assertTrue(manager.sessions.value.isEmpty())
        val process = factory.processes.single()
        assertTrue(process.killed)
        assertTrue(process.closed)
    }

    @Test
    fun killAllClearsEverything() {
        val (manager, factory) = newManager()
        val cwd = Files.createTempDirectory("arrow-term-cwd").toFile()
        repeat(3) { manager.createSession(cwd, maxSessions = 8) }

        manager.killAll()

        assertTrue(manager.sessions.value.isEmpty())
        assertTrue(factory.processes.all { it.killed && it.closed })
    }

    @Test
    fun naturallyExitedSessionsDropOutOfTheList() {
        val (manager, factory) = newManager()
        val cwd = Files.createTempDirectory("arrow-term-cwd").toFile()
        val session = manager.createSession(cwd, maxSessions = 4)
        manager.createSession(cwd, maxSessions = 4)
        assertEquals(2, manager.sessions.value.size)

        // The process exits on its own (e.g. the user typed "exit").
        factory.processes.first { it === session.process }.onExit.complete(0)

        runBlocking {
            withTimeout(5_000) {
                while (manager.sessions.value.size != 1) {
                    delay(10)
                }
            }
        }
        assertEquals(1, manager.sessions.value.size)
        assertTrue(session !in manager.sessions.value)
    }

    @Test
    fun defaultCwdComesFromTheFactory() {
        val (manager, factory) = newManager()
        assertEquals(factory.defaultCwd, manager.defaultCwd)
    }
}
