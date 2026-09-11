package com.maragung.arrowide.buildsystem

import java.io.File

/** One variable from a `.env` file; [sensitive] flags secret-looking names. */
data class EnvVar(
    val name: String,
    val value: String,
    val sensitive: Boolean,
)

/**
 * `.env` support for projects (plan #22): parse, write, and a name-based
 * secret heuristic.
 *
 * [writeDotEnv] writes a plain file — it never redacts and never encrypts.
 * The caller decides what goes in: the UI must warn before writing, and
 * must not include secret variables unless the user explicitly asks for
 * them.
 */
class ProjectEnvironment {

    /**
     * Parses `KEY=VALUE` lines. Returns null when [file] is absent (the
     * common "no .env yet" case), an empty list for an empty file.
     * Blank lines and `#` comment lines are skipped, surrounding quotes
     * (single or double) are stripped, `\"`/`\\` escapes inside double
     * quotes are unescaped, malformed lines are ignored.
     */
    fun parseDotEnv(file: File): List<EnvVar>? {
        if (!file.isFile) return null
        val vars = mutableListOf<EnvVar>()
        for (raw in file.readText(Charsets.UTF_8).lines()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val eq = line.indexOf('=')
            if (eq <= 0) continue // no '=' or empty key
            val name = line.substring(0, eq).trim()
            val value = unquote(line.substring(eq + 1).trim())
            vars += EnvVar(name, value, looksSensitive(name))
        }
        return vars
    }

    /**
     * Writes [vars] as a plain `KEY=VALUE` file, one variable per line.
     * Values that contain whitespace, quotes or `#` are double-quoted and
     * escaped so [parseDotEnv] round-trips them exactly. Parent
     * directories are the caller's responsibility.
     */
    fun writeDotEnv(file: File, vars: List<EnvVar>) {
        val body = vars.joinToString("\n") { v ->
            v.name + "=" + quote(v.value)
        } + "\n"
        file.writeText(body, Charsets.UTF_8)
    }

    /**
     * Heuristic secret detector: true when the variable name contains
     * KEY, TOKEN, SECRET, PASSWORD, PASSWD, CREDENTIAL or PRIVATE
     * (case-insensitive). Deliberately over-approximates — a false
     * positive only costs a warning, a false negative leaks a secret.
     */
    fun looksSensitive(name: String): Boolean {
        val upper = name.uppercase()
        return SENSITIVE_MARKERS.any { upper.contains(it) }
    }

    /** Strips matching outer quotes and unescapes double-quoted content. */
    private fun unquote(raw: String): String = when {
        raw.length >= 2 && raw.startsWith("\"") && raw.endsWith("\"") ->
            raw.substring(1, raw.length - 1)
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")

        raw.length >= 2 && raw.startsWith("'") && raw.endsWith("'") ->
            raw.substring(1, raw.length - 1)

        else -> raw
    }

    /** Quotes [value] when it would not survive a plain KEY=VALUE line. */
    private fun quote(value: String): String =
        if (value.isEmpty() || value.any { it.isWhitespace() || it == '"' || it == '\'' || it == '#' }) {
            "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        } else {
            value
        }

    private companion object {
        val SENSITIVE_MARKERS = listOf(
            "KEY",
            "TOKEN",
            "SECRET",
            "PASSWORD",
            "PASSWD",
            "CREDENTIAL",
            "PRIVATE",
        )
    }
}
