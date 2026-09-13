package com.maragung.arrowide.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AiCommands] slash-command resolution, completion and prompt expansion
 * (plan #87). Pure string logic — no Android.
 */
class AiCommandsTest {

    @Test
    fun resolvesKnownCommandWithUserText() {
        val resolved = AiCommands.resolve("  /fix  the null bug  ")

        assertEquals("/fix", resolved!!.first.command)
        assertEquals("the null bug", resolved.second)
    }

    @Test
    fun resolvesCommandWithoutUserText() {
        val resolved = AiCommands.resolve("/explain")

        assertEquals("/explain", resolved!!.first.command)
        assertEquals("", resolved.second)
    }

    @Test
    fun resolveReturnsNullForUnknownOrPlainInput() {
        assertNull(AiCommands.resolve("/definitely-not-a-command do it"))
        assertNull(AiCommands.resolve("just a normal message"))
        assertNull(AiCommands.resolve(""))
    }

    @Test
    fun expandSubstitutesFileAndSelection() {
        val (command, userText) = AiCommands.resolve("/review careful with nulls")!!

        val prompt = AiCommands.expand(
            command,
            userText,
            fileName = "Editor.kt",
            selection = "fun save()",
        )

        assertTrue(prompt.contains("Editor.kt"))
        assertTrue(prompt.contains("fun save()"))
        assertTrue(prompt.endsWith("careful with nulls"))
    }

    @Test
    fun expandFallsBackWhenFileOrSelectionMissing() {
        val command = AiCommands.all.first { it.command == "/explain" }

        val prompt = AiCommands.expand(command, "", null, null)

        assertTrue(prompt.contains("the current file"))
        // No trailing separator when the user text is empty.
        assertEquals(prompt, prompt.trimEnd())
    }

    @Test
    fun completeFiltersByPrefix() {
        assertEquals(AiCommands.all, AiCommands.complete(""))

        val re = AiCommands.complete("/re")

        assertEquals(listOf("/review", "/refactor"), re.map { it.command })

        assertEquals(
            listOf("/fix"),
            AiCommands.complete("/fi").map { it.command },
        )

        val all = AiCommands.complete("/")
        assertEquals(7, all.size)
        assertTrue(all.any { it.command == "/commit" })
        assertTrue(AiCommands.complete("/nope").isEmpty())
    }
}
