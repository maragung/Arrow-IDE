package com.maragung.arrowide.terminal

/**
 * Command-history policy (plan #37).
 *
 * NORMAL  every command is kept in the app-private history file;
 * SECURE  commands containing credential-like words (token, password,
 *         api key, secret) are NOT persisted;
 * DISABLED nothing is ever written; the shell runs without history.
 */
enum class HistoryMode {
    NORMAL,
    SECURE,
    DISABLED,
}

/**
 * Credential heuristics for [HistoryMode.SECURE]: a command that mentions
 * any of these substrings is treated as carrying inline credentials and
 * must not be persisted.
 */
object CommandHistorySecurity {

    private val SENSITIVE_MARKERS = listOf(
        "token", "password", "passwd", "api key", "apikey", "api_key", "secret", "credential",
    )

    /** True when [command] looks like it embeds a credential inline. */
    fun looksSensitive(command: String): Boolean {
        val lower = command.lowercase()
        return SENSITIVE_MARKERS.any { it in lower }
    }
}
