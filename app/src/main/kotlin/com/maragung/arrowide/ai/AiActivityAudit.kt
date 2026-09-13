package com.maragung.arrowide.ai

import java.io.File

/**
 * One agent activity record (plan #97 AI Activity Audit): what the agent
 * did, when, and in which session — file edits, tool runs, permission
 * grants/denials. Appended-only; the UI shows it as a timeline.
 *
 * @param timestamp wall-clock millis
 * @param sessionId the OpenCode session the activity belongs to
 * @param kind      activity kind (see [AiActivityKind])
 * @param detail    human-readable summary, e.g. "Edited src/main.kt"
 */
data class AiActivityEntry(
    val timestamp: Long,
    val sessionId: String,
    val kind: AiActivityKind,
    val detail: String,
)

/** What kind of agent activity happened. */
enum class AiActivityKind {
    /** A file was created/modified/deleted by the agent. */
    FILE_EDIT,

    /** The agent ran a tool (terminal command, search, ...). */
    TOOL_RUN,

    /** The user approved a permission request. */
    PERMISSION_GRANTED,

    /** The user denied a permission request. */
    PERMISSION_DENIED,

    /** A user message was sent to the agent. */
    USER_MESSAGE,
}

/**
 * Append-only audit trail of agent activity (plan #97), persisted as one
 * line per entry in `<dir>/ai-activity.log` so it survives restarts:
 * `<iso-timestamp>\t<sessionId>\t<kind>\t<detail>`.
 *
 * All writes go through [record] which never throws — an audit store that
 * crashes the agent would be worse than a missing line.
 *
 * @param dir       directory for the log file (created on first write)
 * @param maxBytes  when the file exceeds this size the OLDEST half of the
 *                  lines are dropped (bounded growth on device)
 */
class AiActivityAudit(
    private val dir: File,
    private val maxBytes: Long = 512 * 1024,
) {

    /** Appends one entry; returns false when persisting failed. */
    fun record(sessionId: String, kind: AiActivityKind, detail: String): Boolean {
        val entry = AiActivityEntry(
            timestamp = System.currentTimeMillis(),
            sessionId = sessionId,
            kind = kind,
            detail = detail.replace('\t', ' ').replace('\n', ' '),
        )
        return try {
            synchronized(this) {
                dir.mkdirs()
                trimIfNeeded()
                logFile().appendText(format(entry) + "\n")
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /** All persisted entries, oldest first; empty when nothing is stored. */
    fun entries(): List<AiActivityEntry> = try {
        val file = logFile()
        if (!file.isFile) return emptyList()
        synchronized(this) {
            file.readLines().mapNotNull(::parse)
        }
    } catch (_: Exception) {
        emptyList()
    }

    /** Removes every persisted entry. */
    fun clear() {
        try {
            synchronized(this) { logFile().delete() }
        } catch (_: Exception) {
            // Never throws for the same reason record() doesn't.
        }
    }

    private fun logFile(): File = File(dir, "ai-activity.log")

    /** Drops the oldest half of the file once it passes [maxBytes]. */
    private fun trimIfNeeded() {
        val file = logFile()
        if (!file.isFile || file.length() <= maxBytes) return
        val lines = file.readLines()
        if (lines.isEmpty()) return
        file.writeText(lines.drop(lines.size / 2).joinToString("\n", postfix = "\n"))
    }

    private fun format(entry: AiActivityEntry): String =
        "${entry.timestamp}\t${entry.sessionId}\t${entry.kind.name}\t${entry.detail}"

    private fun parse(line: String): AiActivityEntry? {
        val columns = line.split('\t', limit = 4)
        if (columns.size != 4) return null
        val timestamp = columns[0].toLongOrNull() ?: return null
        val kind = runCatching { AiActivityKind.valueOf(columns[2]) }.getOrNull() ?: return null
        return AiActivityEntry(
            timestamp = timestamp,
            sessionId = columns[1],
            kind = kind,
            detail = columns[3],
        )
    }
}
