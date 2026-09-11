package com.maragung.arrowide.ai

import com.maragung.arrowide.github.FakeTransport
import com.maragung.arrowide.github.jsonResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * [OpenCodeService.ensureServer]/[stopServer]/[client] with a fake
 * server process (no spawn) and a scripted health endpoint: Ready
 * transitions, idempotence, process-death and timeout failures with the
 * output tail, and state resets. No network beyond the fake transport.
 */
class OpenCodeServiceServerTest {

    private val root = Files.createTempDirectory("arrow-ai-server").toFile()
    private val binaryDir = File(root, "usr/bin")
    private val homeDir = File(root, "home")
    private val cacheDir = File(root, "cache")

    private lateinit var fake: FakeTransport
    private val createdProcesses = mutableListOf<FakeServerProcess>()
    private val requestedWorkingDirs = mutableListOf<File?>()

    @Before
    fun setUp() {
        fake = FakeTransport()
        // A binary on disk so isInstalled() is true.
        Files.createDirectories(binaryDir.toPath())
        File(binaryDir, "opencode").writeText("fake-binary")
    }

    /** A service whose server process is always the given fake. */
    private fun service(
        port: Int = 43210,
        startTimeoutMs: Long = 25,
        pollIntervalMs: Long = 1,
        tail: String = "",
        makeProcess: () -> Process = { FakeProcess() },
    ): OpenCodeService = OpenCodeService(
        transport = fake,
        binaryDir = binaryDir,
        homeDir = homeDir,
        cacheDir = cacheDir,
        ioDispatcher = Dispatchers.IO,
        processFactory = { binary, home, picked, workingDir ->
            requestedWorkingDirs += workingDir
            // A FRESH process per spawn: a restarted server's predecessor
            // is destroyed during replacement, and the fakes share nothing.
            FakeServerProcess(binary, home, picked, workingDir, makeProcess(), tail)
                .also { createdProcesses += it }
        },
        portPicker = { port },
        startTimeoutMs = startTimeoutMs,
        pollIntervalMs = pollIntervalMs,
    )

    @Test
    fun ensureServerPollsHealthAndBecomesReady() = runBlocking {
        fake.route("http://127.0.0.1:43210/global/health", jsonResponse(200, """{"status":"ok"}"""))
        val service = service()

        val state = service.ensureServer()

        assertTrue(state is OpenCodeServerState.Ready)
        assertEquals(43210, (state as OpenCodeServerState.Ready).port)
        assertEquals(1, createdProcesses.single().startCalls)
        assertTrue(service.serverState.value is OpenCodeServerState.Ready)
        assertNotNull(service.client())
    }

    @Test
    fun ensureServerIsIdempotentWhileReady() = runBlocking {
        fake.route("http://127.0.0.1:43210/global/health", jsonResponse(200, "{}"))
        val service = service()

        val first = service.ensureServer()
        val second = service.ensureServer()

        assertEquals(first, second)
        // The second call reused the running server.
        assertEquals(1, createdProcesses.single().startCalls)
    }

    @Test
    fun ensureServerFailsWhenNotInstalled() = runBlocking {
        File(binaryDir, "opencode").delete()
        val service = service()

        val state = service.ensureServer()

        assertTrue(state is OpenCodeServerState.Failed)
        assertTrue((state as OpenCodeServerState.Failed).reason.contains("not installed"))
        assertEquals(0, createdProcesses.size)
        assertNull(service.client())
    }

    @Test
    fun processDeathDuringStartupFailsWithOutputTail() = runBlocking {
        val dead = FakeProcess()
        dead.kill()
        val service = service(makeProcess = { dead }, tail = "panic: runtime error: index out of range")

        val state = service.ensureServer()

        assertTrue(state is OpenCodeServerState.Failed)
        val reason = (state as OpenCodeServerState.Failed).reason
        assertTrue(reason.contains("exited"))
        assertTrue(reason.contains("panic: runtime error"))
        assertNull(service.client())
        // The dead process was still asked to stop (cleanup path).
        assertEquals(1, createdProcesses.single().stopCalls)
    }

    @Test
    fun healthNeverPassingTimesOut() = runBlocking {
        fake.route("http://127.0.0.1:43210/global/health", jsonResponse(500, "not yet"))
        val service = service(startTimeoutMs = 25, pollIntervalMs = 1)

        val state = service.ensureServer()

        assertTrue(state is OpenCodeServerState.Failed)
        assertTrue((state as OpenCodeServerState.Failed).reason.contains("did not become healthy"))
        assertNull(service.client())
    }

    @Test
    fun stopServerResetsStateAndStopsTheProcess() = runBlocking {
        fake.route("http://127.0.0.1:43210/global/health", jsonResponse(200, "{}"))
        val service = service()
        service.ensureServer()

        service.stopServer()

        assertEquals(OpenCodeServerState.Stopped, service.serverState.value)
        assertNull(service.client())
        assertEquals(1, createdProcesses.single().stopCalls)
    }

    // ---- working-directory selection -------------------------------------

    @Test
    fun ensureServerSpawnsInTheRequestedWorkingDir() = runBlocking {
        fake.route("http://127.0.0.1:43210/global/health", jsonResponse(200, "{}"))
        val service = service()
        val project = File(root, "projects/demo")

        val state = service.ensureServer(workingDir = project)

        assertTrue(state is OpenCodeServerState.Ready)
        assertEquals(project, (state as OpenCodeServerState.Ready).workingDir)
        assertEquals(listOf<File?>(project), requestedWorkingDirs)
        assertNotNull(service.client())
    }

    @Test
    fun ensureServerReusesTheServerForTheSameWorkingDir() = runBlocking {
        fake.route("http://127.0.0.1:43210/global/health", jsonResponse(200, "{}"))
        val service = service()
        val project = File(root, "projects/demo")
        service.ensureServer(workingDir = project)

        // A different File instance for the SAME path is the same workspace.
        val again = service.ensureServer(workingDir = File(root, "projects/demo"))

        assertEquals(1, createdProcesses.size)
        assertEquals(1, createdProcesses.single().startCalls)
        assertEquals(0, createdProcesses.single().stopCalls)
        assertEquals(project, (again as OpenCodeServerState.Ready).workingDir)
    }

    @Test
    fun ensureServerRestartsWhenTheWorkingDirDiffers() = runBlocking {
        fake.route("http://127.0.0.1:43210/global/health", jsonResponse(200, "{}"))
        val service = service()
        service.ensureServer()
        val first = createdProcesses.single()

        val project = File(root, "projects/other")
        val state = service.ensureServer(workingDir = project)

        // The HOME-dir server was replaced by one in the project dir.
        assertEquals(1, first.stopCalls)
        assertEquals(2, createdProcesses.size)
        assertEquals(listOf<File?>(null, project), requestedWorkingDirs)
        assertTrue(state is OpenCodeServerState.Ready)
        assertEquals(project, (state as OpenCodeServerState.Ready).workingDir)
        assertEquals(1, createdProcesses[1].startCalls)
        assertNotNull(service.client())
    }

    @Test
    fun ensureServerRestartsBackToHomeWhenTheDirIsCleared() = runBlocking {
        fake.route("http://127.0.0.1:43210/global/health", jsonResponse(200, "{}"))
        val service = service()
        val project = File(root, "projects/demo")
        service.ensureServer(workingDir = project)

        val state = service.ensureServer()

        assertEquals(2, createdProcesses.size)
        assertEquals(1, createdProcesses.first().stopCalls)
        assertNull((state as OpenCodeServerState.Ready).workingDir)
    }

    @Test
    fun clientDetectsADeadServer() = runBlocking {
        fake.route("http://127.0.0.1:43210/global/health", jsonResponse(200, "{}"))
        val process = FakeProcess()
        val service = service(makeProcess = { process })
        service.ensureServer()

        // The server dies after becoming Ready (no watchdog loop — the
        // next client() call notices and flips the state).
        process.kill()

        assertNull(service.client())
        assertTrue(service.serverState.value is OpenCodeServerState.Failed)
    }
}
