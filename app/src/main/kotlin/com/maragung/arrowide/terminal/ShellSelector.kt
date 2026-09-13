package com.maragung.arrowide.terminal

import java.io.File

/**
 * Chooses the shell binary for new terminal sessions (plan #4/#5 userspace).
 *
 * Bash is the preferred shell once installed in the prefix (Termux-style
 * userspace); until then sessions fall back to the system shell so the
 * terminal works out of the box.
 */
object ShellSelector {

    /** Path of the bash binary inside the toolchain prefix. */
    fun bashPath(prefixDir: File): File = File(File(prefixDir, "bin"), "bash")

    /**
     * Default shell for a new session: bash from the prefix when
     * [preferBash] is set and the binary actually exists, otherwise the
     * system shell ([TerminalEnvironment.DEFAULT_SHELL]).
     */
    fun defaultShell(prefixDir: File, preferBash: Boolean): String {
        val bash = bashPath(prefixDir)
        return if (preferBash && bash.isFile) bash.absolutePath
        else TerminalEnvironment.DEFAULT_SHELL
    }
}
