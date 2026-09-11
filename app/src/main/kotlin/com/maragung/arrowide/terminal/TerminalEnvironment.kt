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
