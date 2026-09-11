package com.maragung.arrowide.terminal

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

class TerminalEnvironmentTest {

    private val environment = TerminalEnvironment(
        homeDir = File("/data/local/home"),
        prefixDir = File("/data/local/usr"),
        tmpDir = File("/data/local/tmp"),
    )

    @Test
    fun envListContainsExpectedVariables() {
        assertEquals(
            listOf(
                "HOME=/data/local/home",
                "PATH=/data/local/home/bin:/data/local/usr/bin:/system/bin:/system/xbin",
                "TERM=xterm-256color",
                "LANG=en_US.UTF-8",
                "TMPDIR=/data/local/tmp",
            ),
            environment.toEnvList(),
        )
    }

    @Test
    fun pathStartsWithHomeThenPrefixThenSystemDirectories() {
        val parts = environment.path.split(":")
        assertEquals(
            listOf(
                "/data/local/home/bin",
                "/data/local/usr/bin",
                "/system/bin",
                "/system/xbin",
            ),
            parts,
        )
    }

    @Test
    fun envArrayMatchesEnvList() {
        assertEquals(environment.toEnvList(), environment.toEnvArray().toList())
    }

    @Test
    fun ensureDirectoriesCreatesHomeAndTmp() {
        val base = Files.createTempDirectory("arrow-term-env").toFile()
        try {
            val env = TerminalEnvironment(
                homeDir = File(base, "home"),
                prefixDir = File(base, "usr"),
                tmpDir = File(base, "tmp"),
            )
            env.ensureDirectories()
            assert(File(base, "home").isDirectory)
            assert(File(base, "tmp").isDirectory)
        } finally {
            base.deleteRecursively()
        }
    }
}
