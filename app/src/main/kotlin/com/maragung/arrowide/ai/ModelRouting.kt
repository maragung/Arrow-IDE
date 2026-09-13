package com.maragung.arrowide.ai

/**
 * The kind of work an AI request represents (plan #57): drives which
 * model a task is routed to.
 */
enum class AiTaskKind { CHAT, CODING, REFACTOR, DEBUG }

/** One configured route: tasks of [kind] go to model [modelId]. */
data class ModelRoute(val kind: AiTaskKind, val modelId: String)

/**
 * Maps a request to a model id (plan #57): [classify] guesses the task
 * kind from the message text, [routeFor] looks up the user's configured
 * model for that kind.
 *
 * The router never fabricates a model: an unconfigured kind yields null
 * and the caller falls back to its manual/default model. Pure Kotlin —
 * the [routes] map is plain mutable state the settings layer can load
 * into and the UI can edit.
 */
class ModelRouter {

    /** Configured model id per task kind; empty until the user sets one. */
    var routes: Map<AiTaskKind, String> = emptyMap()

    /** The configured model for [kind], or null when unconfigured. */
    fun routeFor(kind: AiTaskKind): String? = routes[kind]

    /**
     * Heuristic classification of a message (lowercased first):
     *  - "refactor"/"rename" → [AiTaskKind.REFACTOR]
     *  - "debug"/"error"/"stack trace"/"why ... fail" → [AiTaskKind.DEBUG]
     *  - code-writing actions ("write", "implement", "add test",
     *    "function", "class") or a code fence → [AiTaskKind.CODING]
     *  - anything else → [AiTaskKind.CHAT]
     */
    fun classify(messageText: String): AiTaskKind {
        val text = messageText.lowercase()
        return when {
            "refactor" in text || "rename" in text -> AiTaskKind.REFACTOR
            "debug" in text || "error" in text || "stack trace" in text ||
                WHY_FAIL_REGEX.containsMatchIn(text) -> AiTaskKind.DEBUG
            mentionsCodeActions(text) -> AiTaskKind.CODING
            else -> AiTaskKind.CHAT
        }
    }

    /** Configured model for the kind [classify] assigns, or null. */
    fun route(messageText: String): String? = routeFor(classify(messageText))

    private fun mentionsCodeActions(text: String): Boolean =
        CODE_ACTION_KEYWORDS.any { it in text } || "```" in text

    private companion object {
        val CODE_ACTION_KEYWORDS = listOf(
            "write", "implement", "add test", "function", "class",
        )
        val WHY_FAIL_REGEX = Regex("why.*(fail|crash|break)")
    }
}
