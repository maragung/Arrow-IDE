package com.maragung.arrowide.ai

/**
 * One slash command offered by the AI chat input (plan #87): a `/name`,
 * a one-line description for the completion popup, and a prompt template.
 *
 * Templates reference `{file}` (the current file name) and `{selection}`
 * (the active editor selection); [AiCommands.expand] substitutes both.
 */
data class AiCommand(
    val command: String,
    val description: String,
    val promptTemplate: String,
)

/**
 * The built-in slash-command catalogue for the AI chat (plan #87).
 *
 * Pure logic — the UI feeds the raw input line in ([resolve]) and gets a
 * finished prompt out ([expand]); nothing here touches Android.
 */
object AiCommands {

    /** All built-in commands, in menu order. */
    val all: List<AiCommand> = listOf(
        AiCommand(
            command = "/explain",
            description = "Explain the current file or selection",
            promptTemplate = "Explain {selection} in {file}. Describe what it does, " +
                "its key types and functions, and anything surprising.",
        ),
        AiCommand(
            command = "/review",
            description = "Review code for bugs and improvements",
            promptTemplate = "Review {selection} in {file}. Point out bugs, edge " +
                "cases, naming problems and simplifications, each with a concrete fix.",
        ),
        AiCommand(
            command = "/test",
            description = "Generate unit tests",
            promptTemplate = "Write unit tests for {selection} in {file}. Cover the " +
                "happy path, edge cases and error handling.",
        ),
        AiCommand(
            command = "/fix",
            description = "Fix a bug",
            promptTemplate = "Fix the following issue in {file} (context: " +
                "{selection}):",
        ),
        AiCommand(
            command = "/refactor",
            description = "Refactor for readability and structure",
            promptTemplate = "Refactor {selection} in {file}. Keep behavior " +
                "identical; explain each change you make.",
        ),
        AiCommand(
            command = "/docs",
            description = "Document code with KDoc",
            promptTemplate = "Write KDoc for {selection} in {file}, matching the " +
                "documentation style of the surrounding code.",
        ),
        AiCommand(
            command = "/commit",
            description = "Draft a git commit message",
            promptTemplate = "Draft a concise git commit message for the current " +
                "changes in {file}. Use a summary line plus bullet points.",
        ),
    )

    /**
     * Commands whose name starts with [prefix] (case-sensitive, matching
     * how the user typed it); an empty prefix returns everything. Used by
     * the input's completion popup as the user types `/re...`.
     */
    fun complete(prefix: String): List<AiCommand> =
        all.filter { it.command.startsWith(prefix) }

    /**
     * Splits a raw input line into (command, userText): `"  /fix  the " +
     * "null bug  "` → the /fix command and `"the null bug"`. Returns null
     * when the line does not start with a known slash command. [userText]
     * may be empty — [expand] then uses the template as-is.
     */
    fun resolve(input: String): Pair<AiCommand, String>? {
        val trimmed = input.trim()
        if (!trimmed.startsWith("/")) return null
        val tokenEnd = trimmed.indexOfFirst { it.isWhitespace() }
        val (name, rest) = if (tokenEnd < 0) {
            trimmed to ""
        } else {
            trimmed.substring(0, tokenEnd) to trimmed.substring(tokenEnd).trim()
        }
        val command = all.firstOrNull { it.command == name } ?: return null
        return command to rest
    }

    /**
     * Builds the final prompt from [command]'s template: `{file}` becomes
     * [fileName] and `{selection}` becomes [selection]; a missing (null or
     * blank) value substitutes "the current file". The user's [userText],
     * when non-blank, is appended on its own line.
     */
    fun expand(
        command: AiCommand,
        userText: String,
        fileName: String?,
        selection: String?,
    ): String {
        val fallback = "the current file"
        val file = fileName?.takeIf { it.isNotBlank() } ?: fallback
        val selectionText = selection?.takeIf { it.isNotBlank() } ?: fallback
        val base = command.promptTemplate
            .replace("{file}", file)
            .replace("{selection}", selectionText)
        val text = userText.trim()
        return if (text.isEmpty()) base else "$base\n\n$text"
    }
}
