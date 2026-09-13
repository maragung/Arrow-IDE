package com.maragung.arrowide.ui.ai

import com.maragung.arrowide.ai.AiCommands
import com.maragung.arrowide.ai.AiUsage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The AI chat input's pure helpers (plan #87 slash commands, plan #77
 * token usage extraction from raw SSE payloads). No Android, no Compose.
 */
class AiChatInputTest {

    // slashSuggestions ----------------------------------------------------

    @Test
    fun suggestionsForUnfinishedSlashToken() {
        assertEquals(
            listOf("/review", "/refactor"),
            slashSuggestions("/rev").map { it.command },
        )
        assertEquals(AiCommands.all, slashSuggestions("/"))
    }

    @Test
    fun noSuggestionsOnceTokenIsCompleteOrPlain() {
        // A space means the user moved on to arguments.
        assertTrue(slashSuggestions("/review ").isEmpty())
        assertTrue(slashSuggestions("/review the code").isEmpty())
        assertTrue(slashSuggestions("hello").isEmpty())
        assertTrue(slashSuggestions("").isEmpty())
        assertTrue(slashSuggestions("/nope").isEmpty())
    }

    // chatPromptForSend ---------------------------------------------------

    @Test
    fun sendExpandsSlashCommandWithHonestFallbacks() {
        val prompt = chatPromptForSend("/fix the null crash")

        // No editor context in the AI chat: the built-in fallback is used.
        assertTrue(prompt.contains("the current file"))
        assertTrue(prompt.endsWith("the null crash"))
    }

    @Test
    fun sendExpandsBareCommandWithoutUserText() {
        val prompt = chatPromptForSend("/explain")
        val expected = AiCommands.all
            .first { it.command == "/explain" }
            .promptTemplate
            .replace("{file}", "the current file")
            .replace("{selection}", "the current file")

        assertEquals(expected, prompt)
    }

    @Test
    fun sendPassesUnknownAndPlainTextThrough() {
        assertEquals("just asking", chatPromptForSend("just asking"))
        assertEquals(
            "/definitely-not-a-command now",
            chatPromptForSend("/definitely-not-a-command now"),
        )
        assertEquals("", chatPromptForSend(""))
    }

    // usageFromEventPayload ------------------------------------------------

    @Test
    fun usageFromWrappedMessageUpdatedEvent() {
        val payload = """
            {"type":"message.updated","properties":{
              "info":{"id":"m1","role":"assistant","tokens":{"input":120,"output":45}},
              "parts":[{"type":"text","text":"hi"}]}}
        """.trimIndent()

        assertEquals(AiUsage(inputTokens = 120, outputTokens = 45), usageFromEventPayload(payload))
    }

    @Test
    fun usageFromFlatCounts() {
        assertEquals(
            AiUsage(inputTokens = 5, outputTokens = 6),
            usageFromEventPayload("""{"inputTokens":5,"outputTokens":6}"""),
        )
    }

    @Test
    fun usageFromNestedPartCounts() {
        val payload = """
            {"parts":[{"type":"text","text":"ok","tokens":{"in":7,"out":9}}]}
        """.trimIndent()

        assertEquals(AiUsage(inputTokens = 7, outputTokens = 9), usageFromEventPayload(payload))
    }

    @Test
    fun usageNullWhenAbsentPartialOrUnparseable() {
        assertNull(usageFromEventPayload("""{"parts":[{"type":"text","text":"no counts"}]}"""))
        // Only one side of the counts is not a usage.
        assertNull(usageFromEventPayload("""{"tokens":{"input":1}}"""))
        assertNull(usageFromEventPayload("not json at all"))
    }
}
