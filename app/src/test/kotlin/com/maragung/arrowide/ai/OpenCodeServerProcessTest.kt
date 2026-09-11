package com.maragung.arrowide.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * [OpenCodeServerProcess.envFor] (pure) and [command]: everything the
 * server needs is pinned inside app storage, PATH covers the toolchain
 * prefix and /system/bin, extra env can override. No process spawn.
 */
class OpenCodeServerProcessTest {

    private val root = Files.createTempDirectory("arrow-ai-server").toFile()
    private val binary = File(root, "usr/bin/opencode")
    private val homeDir = File(root, "home")

    @Test
    fun envForPointsEverythingInsideHome() {
        val env = OpenCodeServerProcess(binary, homeDir, port = 8080).envFor()

        assertEquals(homeDir.absolutePath, env["HOME"])
        // PATH: the binary's directory (the toolchain prefix bin) plus
        // Android's /system/bin, nothing inherited from the app process.
        assertEquals(
            listOf(File(root, "usr/bin"), File("/system/bin"))
                .joinToString(":") { it.absolutePath },
            env["PATH"],
        )
        assertEquals(File(homeDir, ".config").absolutePath, env["XDG_CONFIG_HOME"])
        assertEquals(File(homeDir, ".local/share").absolutePath, env["XDG_DATA_HOME"])
        assertEquals(File(homeDir, ".cache").absolutePath, env["XDG_CACHE_HOME"])
        assertEquals(File(homeDir, "tmp").absolutePath, env["TMPDIR"])
        // Hermetic: no incidental inheritance.
        assertTrue(env.keys.none { it.equals("ANDROID_DATA", ignoreCase = true) })
    }

    @Test
    fun envForMergesExtraEnvLast() {
        val env = OpenCodeServerProcess(
            binary,
            homeDir,
            port = 8080,
            extraEnv = mapOf(
                "SSL_CERT_FILE" to "/data/ca-bundle.crt",
                "HOME" to "/somewhere/else",
            ),
        ).envFor()

        assertEquals("/somewhere/else", env["HOME"])
        assertEquals("/data/ca-bundle.crt", env["SSL_CERT_FILE"])
        assertEquals(File(homeDir, ".cache").absolutePath, env["XDG_CACHE_HOME"])
    }

    @Test
    fun commandRunsServeOnLoopbackWithThePort() {
        val command = OpenCodeServerProcess(binary, homeDir, port = 45678).command()

        assertEquals(
            listOf(
                binary.absolutePath,
                "serve",
                "--port", "45678",
                "--hostname", "127.0.0.1",
            ),
            command,
        )
    }

    @Test
    fun processDirectoryDefaultsToHome() {
        val server = OpenCodeServerProcess(binary, homeDir, port = 8080)

        assertEquals(homeDir, server.processDirectory())
    }

    @Test
    fun processDirectoryIsTheWorkingDirWhenSet() {
        val project = File(root, "projects/demo")
        val server = OpenCodeServerProcess(binary, homeDir, port = 8080, workingDir = project)

        assertEquals(project, server.processDirectory())
        // A working dir does not leak into the hermetic environment.
        assertEquals(homeDir.absolutePath, server.envFor()["HOME"])
    }
}
