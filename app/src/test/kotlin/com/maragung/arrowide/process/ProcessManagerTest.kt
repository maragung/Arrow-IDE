package com.maragung.arrowide.process

import com.maragung.arrowide.terminal.FakePtyProcess
import com.maragung.arrowide.terminal.TerminalSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [ProcessManager] behavior: snapshotting the session list, live title and
 * exit-code updates, and observer cleanup when sessions disappear.
 */
class ProcessManagerTest {

    private fun newSession(id: Int, cwd: File = File("/tmp/work")): TerminalSession =
        TerminalSession(id, FakePtyProcess(), cwd)

    @Test
    fun emptySourceYieldsEmptyList() = runBlocking {
        val source = MutableStateFlow<List<TerminalSession>>(emptyList())
        val manager = ProcessManager(source)
        try {
            // The initial collection may not have run yet; give it a turn.
            delay(50)
            assertTrue(manager.processes.value.isEmpty())
        } finally {
            manager.close()
        }
    }

    @Test
    fun sessionsAreSnapshottedWithPidCwdAndAliveState() = runBlocking {
        val source = MutableStateFlow<List<TerminalSession>>(emptyList())
        val manager = ProcessManager(source)
        val session = newSession(7, File("/tmp/proj"))
        source.value = listOf(session)
        try {
            awaitSnapshot(manager) { it.size == 1 }
            val tracked = manager.processes.value.single()
            assertEquals(7, tracked.sessionId)
            assertEquals(session.process.pid, tracked.pid)
            assertEquals("/tmp/proj", tracked.cwdPath)
            assertTrue(tracked.isAlive)
            assertEquals(null, tracked.exitCode)
            assertTrue(tracked.title.contains("proj"))
        } finally {
            manager.close()
            session.close()
        }
    }

    @Test
    fun exitCodeUpdatesPropagate() = runBlocking {
        val source = MutableStateFlow<List<TerminalSession>>(emptyList())
        val manager = ProcessManager(source)
        val session = newSession(3)
        source.value = listOf(session)
        try {
            awaitSnapshot(manager) { it.isNotEmpty() }
            session.process.kill() // FakePtyProcess completes exit with 137
            awaitSnapshot(manager) { it.firstOrNull()?.exitCode != null }
            val tracked = manager.processes.value.single()
            assertEquals(137, tracked.exitCode)
            assertEquals(false, tracked.isAlive)
        } finally {
            manager.close()
            session.close()
        }
    }

    @Test
    fun titleChangesPropagate() = runBlocking {
        val source = MutableStateFlow<List<TerminalSession>>(emptyList())
        val manager = ProcessManager(source)
        val session = newSession(9)
        source.value = listOf(session)
        try {
            awaitSnapshot(manager) { it.isNotEmpty() }
            session.setTitle("npm run dev")
            awaitSnapshot(manager) { it.firstOrNull()?.title == "npm run dev" }
        } finally {
            manager.close()
            session.close()
        }
    }

    @Test
    fun removedSessionsDisappear() = runBlocking {
        val source = MutableStateFlow<List<TerminalSession>>(emptyList())
        val manager = ProcessManager(source)
        val session = newSession(11)
        source.value = listOf(session)
        try {
            awaitSnapshot(manager) { it.size == 1 }
            source.value = emptyList()
            awaitSnapshot(manager) { it.isEmpty() }
        } finally {
            manager.close()
            session.close()
        }
    }

    /** Polls until [condition] holds (bounded), failing the test on timeout. */
    private inline fun awaitSnapshot(
        manager: ProcessManager,
        crossinline condition: (List<TrackedProcess>) -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + 2_000
        while (System.currentTimeMillis() < deadline) {
            if (condition(manager.processes.value)) return
            Thread.sleep(20)
        }
        throw AssertionError(
            "condition not met; last value: ${manager.processes.value}",
        )
    }
}
