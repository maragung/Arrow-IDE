package com.maragung.arrowide.terminal

import java.io.File

/**
 * Filesystem locations and environment variables used for terminal sessions.
 *
 * A plain holder class rather than a Kotlin `value class` because value
 * classes can only wrap a single underlying value; this carries three
 * directories.
 *
 * @param homeDir   the terminal's HOME directory (app-private storage)
 * @param prefixDir directory holding the installed command-line prefix
 *                  (toolchain root; its `bin` subdirectory joins PATH)
 * @param tmpDir    the terminal's TMPDIR
 */
class TerminalEnvironment(
    val homeDir: File,
    val prefixDir: File,
    val tmpDir: File,
) {

    /** `PATH` presented to child processes. */
    val path: String
        get() = listOf(
            File(homeDir, "bin"),
            File(prefixDir, "bin"),
            File("/system/bin"),
            File("/system/xbin"),
        ).joinToString(separator = ":") { it.absolutePath }

    /** Environment as a list of "KEY=VALUE" strings, in a stable order. */
    fun toEnvList(): List<String> = listOf(
        "HOME=${homeDir.absolutePath}",
        "PATH=$path",
        "TERM=$TERM_TYPE",
        "LANG=$LANG",
        "TMPDIR=${tmpDir.absolutePath}",
    )

    /** Same as [toEnvList] but as the array shape expected by [Pty.create]. */
    fun toEnvArray(): Array<String> = toEnvList().toTypedArray()

    /**
     * Environment with the shell history wired to the app-private histfile
     * (plan #37): the file lives under HOME so it stays inside the app
     * sandbox; [mode] DISABLED points HISTFILE at /dev/null so nothing is
     * ever written, and SECURE also caps HISTCONTROL and disables history
     * expansion of sensitive words via HISTIGNORE for common token flags.
     */
    fun toEnvListWithHistory(mode: HistoryMode): List<String> {
        val base = toEnvList().toMutableList()
        when (mode) {
            HistoryMode.NORMAL -> base += "HISTFILE=${File(homeDir, ".history").absolutePath}"
            HistoryMode.SECURE -> {
                // History is kept, but obviously credential-bearing lines are
                // never entered into it in the first place (filtered by the
                // UI before writing); HISTSIZE stays small.
                base += "HISTFILE=${File(homeDir, ".history").absolutePath}"
                base += "HISTSIZE=200"
                base += "HISTCONTROL=ignoredups"
            }
            HistoryMode.DISABLED -> {
                base += "HISTFILE=/dev/null"
                base += "HISTSIZE=0"
            }
        }
        return base
    }

    /** Creates the directories the terminal relies on, if missing. */
    fun ensureDirectories() {
        homeDir.mkdirs()
        tmpDir.mkdirs()
    }

    companion object {
        /** Default shell spawned by [ShellPtyFactory]. */
        const val DEFAULT_SHELL: String = "/system/bin/sh"

        const val TERM_TYPE: String = "xterm-256color"
        const val LANG: String = "en_US.UTF-8"
    }
}
