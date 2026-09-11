package com.maragung.arrowide.ai

import com.maragung.arrowide.github.FakeTransport
import com.maragung.arrowide.github.jsonResponse
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * [OpenCodeService.install] against a scripted release response, a
 * [FakeDownloader] serving a real tar.gz built with commons-compress,
 * and a scripted [commandRunner] seam: entry selection, verification,
 * executable bit, cache cleanup, and FAILED + rollback when
 * `--version` fails. No network, no process spawn.
 */
class OpenCodeInstallTest {

    private val root = Files.createTempDirectory("arrow-ai-install").toFile()
    private val binaryDir = File(root, "usr/bin")
    private val homeDir = File(root, "home")
    private val cacheDir = File(root, "cache")

    private lateinit var fake: FakeTransport
    private val commands = mutableListOf<List<String>>()
    private val envs = mutableListOf<Map<String, String>>()
    private val cwds = mutableListOf<File>()
    private var verifyExitCode = 0

    @Before
    fun setUp() {
        fake = FakeTransport()
        fake.route(RELEASES_URL, jsonResponse(200, RELEASE_JSON))
    }

    private fun service(archiveBytes: () -> ByteArray): OpenCodeService =
        OpenCodeService(
            transport = fake,
            binaryDir = binaryDir,
            homeDir = homeDir,
            cacheDir = cacheDir,
            archAssetSuffix = "arm64-musl",
            commandRunner = { command, env, cwd ->
                commands += command
                envs += env
                cwds += cwd
                verifyExitCode
            },
            downloader = FakeDownloader(archiveBytes),
        )

    @Test
    fun installDownloadsExtractsVerifiesAndCleansUp() = runBlocking {
        val service = service {
            tarGz(
                "opencode-linux-arm64-musl/README.md" to "readme",
                "opencode-linux-arm64-musl/opencode" to "opencode-binary-bytes",
            )
        }

        val result = service.install()

        assertTrue(result is AiResult.Ok<*>)
        assertEquals(OpenCodeInstallState.INSTALLED, service.installState.value)
        // The binary landed at binaryDir/opencode with the entry's content.
        val binary = File(binaryDir, "opencode")
        assertTrue(binary.isFile)
        assertEquals("opencode-binary-bytes", binary.readText())
        // Verification ran the staging file with --version under the
        // hermetic environment, in homeDir.
        assertEquals(listOf(File(binaryDir, "opencode.new").absolutePath, "--version"), commands.single())
        assertEquals(homeDir.absolutePath, cwds.single().absolutePath)
        assertEquals(homeDir.absolutePath, envs.single()["HOME"])
        assertTrue(envs.single()["PATH"]!!.contains(binaryDir.absolutePath))
        // Cache and staging area are empty after a successful install.
        assertEquals(0, cacheDir.listFiles()!!.size)
        assertFalse(File(binaryDir, "opencode.new").exists())
    }

    @Test
    fun installSetsTheExecutableBit() = runBlocking {
        val service = service {
            tarGz("opencode-linux-arm64-musl/opencode" to "bytes")
        }

        val result = service.install()

        assertTrue(result is AiResult.Ok<*>)
        assertTrue(File(binaryDir, "opencode").canExecute())
    }

    @Test
    fun installPicksTheOpencodeEntryFromARootLevelLayout() = runBlocking {
        val service = service {
            tarGz(
                "opencode" to "root-level-binary",
                "opencode.txt" to "decoy",
                "README" to "readme",
            )
        }

        val result = service.install()

        assertTrue(result is AiResult.Ok<*>)
        assertEquals("root-level-binary", File(binaryDir, "opencode").readText())
    }

    @Test
    fun verifyFailureFailsWithoutInstalling() = runBlocking {
        verifyExitCode = 1
        val service = service {
            tarGz("opencode-linux-arm64-musl/opencode" to "broken-binary")
        }

        val result = service.install()

        assertTrue(result is AiResult.Error)
        assertTrue((result as AiResult.Error).message.contains("--version"))
        assertEquals(OpenCodeInstallState.FAILED, service.installState.value)
        // Rollback: no binary, no staging file, no cached tarball.
        assertFalse(File(binaryDir, "opencode").exists())
        assertFalse(File(binaryDir, "opencode.new").exists())
        assertEquals(0, cacheDir.listFiles()!!.size)
    }

    @Test
    fun verifyFailureKeepsThePreviousBinary() = runBlocking {
        Files.createDirectories(binaryDir.toPath())
        val previous = File(binaryDir, "opencode")
        previous.writeText("previous-good-binary")
        verifyExitCode = 1
        val service = service {
            tarGz("opencode-linux-arm64-musl/opencode" to "broken-binary")
        }

        val result = service.install()

        assertTrue(result is AiResult.Error)
        // The failed install never touched the working binary.
        assertEquals("previous-good-binary", previous.readText())
        assertEquals(0, cacheDir.listFiles()!!.size)
    }

    @Test
    fun archiveWithoutBinaryIsAnError() = runBlocking {
        val service = service {
            tarGz("opencode-linux-arm64-musl/README.md" to "readme only")
        }

        val result = service.install()

        assertTrue(result is AiResult.Error)
        assertEquals(OpenCodeInstallState.FAILED, service.installState.value)
        assertFalse(File(binaryDir, "opencode").exists())
    }

    @Test
    fun downloadFailureFailsAndCleansUp() = runBlocking {
        val service = service { throw IOException("connection reset") }

        val result = service.install()

        assertTrue(result is AiResult.Error)
        assertTrue((result as AiResult.Error).message.contains("connection reset"))
        assertEquals(OpenCodeInstallState.FAILED, service.installState.value)
        assertFalse(File(binaryDir, "opencode").exists())
        // Verification never ran.
        assertEquals(0, commands.size)
    }

    @Test
    fun installProgressEndsInstalled() = runBlocking {
        val service = service {
            tarGz("opencode-linux-arm64-musl/opencode" to "bytes")
        }

        service.install()

        val progress = service.installProgress.value
        assertEquals(OpenCodeInstallState.INSTALLED, progress.state)
        assertEquals(1f, progress.progress)
        assertTrue(progress.message?.contains("v0.6.5") == true)
    }

    @Test
    fun installStateStartsInstalledWhenBinaryExists() {
        Files.createDirectories(binaryDir.toPath())
        File(binaryDir, "opencode").writeText("already here")

        val service = service { tarGz("opencode" to "x") }

        assertEquals(OpenCodeInstallState.INSTALLED, service.installState.value)
        assertTrue(service.isInstalled())
    }

    private companion object {
        const val RELEASES_URL = "https://api.github.com/repos/sst/opencode/releases/latest"
    }
}
