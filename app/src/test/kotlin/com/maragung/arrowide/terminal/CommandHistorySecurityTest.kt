package com.maragung.arrowide.terminal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Command-history security (plan #37): sensitive-command heuristics and
 * the environment wiring per [HistoryMode].
 */
class CommandHistorySecurityTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val environment = TerminalEnvironment(
        homeDir = File("/data/local/tmp/home"),
        prefixDir = File("/data/local/tmp/usr"),
        tmpDir = File("/data/local/tmp/tmp"),
    )

    // ---- heuristics ---------------------------------------------------------

    @Test
    fun tokenCommandsLookSensitive() {
        assertTrue(CommandHistorySecurity.looksSensitive("gh auth login --token faketoken_x"))
        assertTrue(CommandHistorySecurity.looksSensitive("export GITHUB_TOKEN=abc"))
        assertTrue(CommandHistorySecurity.looksSensitive("curl -H 'password: x' https://x"))
        assertTrue(CommandHistorySecurity.looksSensitive("kubectl create secret generic s"))
        assertTrue(CommandHistorySecurity.looksSensitive("echo \$API_KEY"))
        assertTrue(CommandHistorySecurity.looksSensitive("docker login --passwd hunter2"))
    }

    @Test
    fun ordinaryCommandsDoNot() {
        assertFalse(CommandHistorySecurity.looksSensitive("npm install"))
        assertFalse(CommandHistorySecurity.looksSensitive("git status"))
        assertFalse(CommandHistorySecurity.looksSensitive("ls -la ~/projects"))
        assertFalse(CommandHistorySecurity.looksSensitive("python3 server.py"))
        assertFalse(CommandHistorySecurity.looksSensitive(""))
    }

    // ---- environment wiring ---------------------------------------------------

    @Test
    fun normalModePointsHistfileAtTheAppPrivateFile() {
        val env = environment.toEnvListWithHistory(HistoryMode.NORMAL)
        val histfile = env.first { it.startsWith("HISTFILE=") }
        assertTrue(histfile.endsWith("/home/.history"))
        assertTrue(histfile.startsWith("/data/local/tmp"))
    }

    @Test
    fun secureModeKeepsHistoryButLimitsIt() {
        val env = environment.toEnvListWithHistory(HistoryMode.SECURE)
        assertTrue(env.any { it.startsWith("HISTFILE=/data/local/tmp/home/.history") })
        assertTrue(env.contains("HISTSIZE=200"))
        assertTrue(env.contains("HISTCONTROL=ignoredups"))
    }

    @Test
    fun disabledModeNeverWritesHistory() {
        val env = environment.toEnvListWithHistory(HistoryMode.DISABLED)
        assertTrue(env.contains("HISTFILE=/dev/null"))
        assertTrue(env.contains("HISTSIZE=0"))
    }

    @Test
    fun historyEnvKeepsTheBaseEnvironment() {
        val base = environment.toEnvList()
        val withHistory = environment.toEnvListWithHistory(HistoryMode.NORMAL)
        // Every base entry survives; only HIST* entries are added.
        assertTrue(withHistory.containsAll(base))
        assertTrue(withHistory.size >= base.size)
    }
}
