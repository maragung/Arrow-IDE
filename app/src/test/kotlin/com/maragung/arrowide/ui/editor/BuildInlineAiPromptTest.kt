package com.maragung.arrowide.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [buildInlineAiPrompt] (plan #88): file name + selected code → the Inline AI
 * context block (no invented question; honest truncation of big selections).
 */
class BuildInlineAiPromptTest {

    @Test
    fun wrapsSelectionInContextBlock() {
        assertEquals(
            "Context — file Main.kt:\n```\nfun main() {}\n```\n\n",
            buildInlineAiPrompt("Main.kt", "fun main() {}"),
        )
    }

    @Test
    fun emptySelectionStillProducesContextBlock() {
        assertEquals(
            "Context — file Notes.txt:\n```\n\n```\n\n",
            buildInlineAiPrompt("Notes.txt", ""),
        )
    }

    @Test
    fun selectionKeepsItsWhitespaceVerbatim() {
        assertEquals(
            "Context — file a.py:\n```\n  indented()\n\n\ttabbed()\n```\n\n",
            buildInlineAiPrompt("a.py", "  indented()\n\n\ttabbed()"),
        )
    }

    @Test
    fun largeSelectionIsTruncatedWithMarker() {
        val selection = "a".repeat(10_000)
        val prompt = buildInlineAiPrompt("big.txt", selection)
        val expected = "Context — file big.txt:\n```\n" +
            "a".repeat(8000) + "... (truncated)" +
            "\n```\n\n"
        assertEquals(expected, prompt)
    }

    @Test
    fun selectionAtLimitIsNotTruncated() {
        val selection = "x".repeat(8000)
        val prompt = buildInlineAiPrompt("edge.txt", selection)
        assertTrue(prompt.endsWith("```\n\n"))
        assertFalse(prompt.contains("truncated"))
    }

    @Test
    fun truncatedPromptLengthBoundedByLimitPlusOverhead() {
        val prompt = buildInlineAiPrompt("huge.js", "y".repeat(50_000))
        // 8000 kept chars + "... (truncated)" + the fixed prompt overhead.
        assertTrue(prompt.length < 8000 + 100)
    }
}
