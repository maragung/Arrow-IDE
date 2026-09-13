package com.maragung.arrowide.packages

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Installs the userspace `apt` / `apt-get` command shims (plan #4/#5, Tool
 * Manager) for Termux-style shell parity.
 *
 * Package operations (install/remove/update) run in the Kotlin toolchain
 * layer ([com.maragung.arrowide.toolchain.ToolchainManager]), not in the
 * shell, so the shim cannot perform them itself — that would be dishonest.
 * Instead it is an honest queue: every invocation appends one line to
 * `<prefix>/var/apt-queue` and tells the user to open the Tools screen to
 * apply it. The app layer drains the queue with [queuedCommands].
 */
class AptCommandShim(
    private val prefixDir: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private val binDir: File
        get() = File(prefixDir, "bin")

    private val aptFile: File
        get() = File(binDir, "apt")

    private val aptGetFile: File
        get() = File(binDir, "apt-get")

    private val queueFile: File
        get() = File(prefixDir, QUEUE_RELATIVE_PATH)

    /** True once both shims exist and are executable. */
    fun isInstalled(): Boolean =
        aptFile.isFile && aptFile.canExecute() &&
            aptGetFile.isFile && aptGetFile.canExecute()

    /**
     * Writes the `apt` and `apt-get` shims into `<prefixDir>/bin` (both
     * files share the same script body; the prefix is derived from `$0`, so
     * the script works wherever the prefix lives).
     */
    suspend fun ensureInstalled() {
        withContext(ioDispatcher) {
            binDir.mkdirs()
            for (target in listOf(aptFile, aptGetFile)) {
                target.writeText(SCRIPT)
                // Group/other execute for shell parity; the exact mode is
                // approximated by java.io.File (owner-write only).
                target.setExecutable(true, false)
            }
        }
    }

    /**
     * Reads the queued command lines without clearing the queue (a peek, as
     * used by the Tools screen to preview what is pending). An absent queue
     * yields an empty list.
     */
    suspend fun peekCommands(): List<String> {
        return withContext(ioDispatcher) {
            if (queueFile.isFile) {
                queueFile.readLines().filter { it.isNotBlank() }
            } else {
                emptyList()
            }
        }
    }

    /**
     * Appends [lines] back to the queue file, creating parent directories as
     * needed. Used by the app layer to restore commands that could not be
     * applied so nothing is silently lost.
     */
    suspend fun requeue(lines: List<String>) {
        if (lines.isEmpty()) {
            return
        }
        withContext(ioDispatcher) {
            val parent = queueFile.parentFile
            if (parent != null) {
                parent.mkdirs()
            }
            queueFile.appendText(
                lines.joinToString(separator = "\n", prefix = "", postfix = "\n")
            )
        }
    }

    /**
     * Drains the queue: returns every queued command line (e.g.
     * "apt install nodejs") and clears the queue file. An absent queue
     * yields an empty list.
     */
    suspend fun queuedCommands(): List<String> {
        return withContext(ioDispatcher) {
            if (!queueFile.isFile) {
                emptyList()
            } else {
                val lines = queueFile.readLines().filter { it.isNotBlank() }
                queueFile.delete()
                lines
            }
        }
    }

    companion object {
        /** Queue location relative to the prefix directory. */
        const val QUEUE_RELATIVE_PATH: String = "var/apt-queue"

        /**
         * POSIX sh script shared by `apt` and `apt-get`. It only records the
         * invocation and exits 0 when the queue is writable, 1 otherwise.
         * Shell `$` references are escaped so Kotlin keeps them literal
         * instead of parsing them as string templates.
         */
        private const val SCRIPT = """#!/system/bin/sh
# Arrow IDE apt shim (generated - do not edit).
# Package operations run in the Kotlin toolchain layer, so this shim
# honestly just queues the command for the app to apply.
PREFIX=$(dirname "$(dirname "${'$'}0")")
QUEUE="${'$'}PREFIX/""" + QUEUE_RELATIVE_PATH + """"
mkdir -p "${'$'}PREFIX/var" 2>/dev/null
if echo "apt ${'$'}@" >> "${'$'}QUEUE" 2>/dev/null; then
    echo "Queued: apt ${'$'}@ - open Tools in Arrow IDE to apply."
    exit 0
else
    echo "apt: cannot write ${'$'}QUEUE" >&2
    exit 1
fi
"""
    }
}
