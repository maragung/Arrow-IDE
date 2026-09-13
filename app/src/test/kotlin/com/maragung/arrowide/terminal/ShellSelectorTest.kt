package com.maragung.arrowide.terminal

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [ShellSelector] contract tests: bash is preferred only when installed,
 * otherwise the terminal falls back to the system shell.
 */
class ShellSelectorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun prefersBashWhenTheBinaryExists() {
        val prefix = tmp.newFolder("prefix")
        val bash = ShellSelector.bashPath(prefix)
        bash.parentFile!!.mkdirs()
        bash.writeText("#!/system/bin/sh\n")

        assertEquals(
            bash.absolutePath,
            ShellSelector.defaultShell(prefix, preferBash = true),
        )
    }

    @Test
    fun fallsBackToSystemShWhenBashIsMissing() {
        val prefix = tmp.newFolder("prefix")

        assertEquals(
            TerminalEnvironment.DEFAULT_SHELL,
            ShellSelector.defaultShell(prefix, preferBash = true),
        )
    }

    @Test
    fun ignoresBashWhenPreferenceIsOff() {
        val prefix = tmp.newFolder("prefix")
        val bash = ShellSelector.bashPath(prefix)
        bash.parentFile!!.mkdirs()
        bash.writeText("#!/system/bin/sh\n")

        assertEquals(
            TerminalEnvironment.DEFAULT_SHELL,
            ShellSelector.defaultShell(prefix, preferBash = false),
        )
    }
}
