package com.maragung.arrowide.git

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * [AndroidGitProcess] contract tests that need no git binary: the child
 * environment shape and the working-directory guard. Process spawning is
 * exercised on-device (and via the terminal's PTY machinery).
 */
class GitProcessTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun process(): AndroidGitProcess {
        val home = tmp.newFolder("home")
        val prefix = tmp.newFolder("usr")
        return AndroidGitProcess(home, prefix)
    }

    @Test
    fun defaultBinaryLivesInThePrefixBin() {
        val home = tmp.newFolder("h")
        val prefix = tmp.newFolder("p")
        val process = AndroidGitProcess(home, prefix)

        assertEquals(File(prefix, "bin/git"), process.gitBinary)
    }

    @Test
    fun baseEnvIsHermeticAndHardened() {
        val home = tmp.newFolder("h")
        val prefix = tmp.newFolder("p")
        val env = AndroidGitProcess(home, prefix).baseEnv

        assertEquals(home.absolutePath, env["HOME"])
        assertEquals(
            listOf(
                File(prefix, "bin"),
                File(home, "bin"),
                File("/system/bin"),
            ).joinToString(":") { it.absolutePath },
            env["PATH"],
        )
        assertEquals("1", env["GIT_CONFIG_NOSYSTEM"])
        assertEquals("0", env["GIT_TERMINAL_PROMPT"])
        assertEquals("dumb", env["TERM"])
        assertEquals("C", env["LC_ALL"])
        assertEquals("nothing inherited from the builder", 6, env.size)
    }

    @Test
    fun executeRejectsAMissingWorkingDirectory() {
        val process = process()

        try {
            process.execute(listOf("status"), File(tmp.root, "missing"))
            fail("expected an IOException for a missing cwd")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("working directory"))
        }
    }
}
