package com.maragung.arrowide.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [AiActivityAudit] (plan #97): append-only persistence, tolerant parsing,
 * tab/newline scrubbing in details, and bounded growth.
 */
class AiActivityAuditTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun entriesSurviveRoundTripAndReload() {
        val dir = tmp.newFolder()
        val audit = AiActivityAudit(dir = dir)
        audit.record("s1", AiActivityKind.FILE_EDIT, "Edited src/Main.kt")
        audit.record("s1", AiActivityKind.PERMISSION_GRANTED, "bash: npm test")

        // A NEW instance over the SAME directory reads the persisted log.
        val reloaded = AiActivityAudit(dir = dir)
        val entries = reloaded.entries()
        assertEquals(2, entries.size)
        assertEquals(AiActivityKind.FILE_EDIT, entries[0].kind)
        assertEquals("Edited src/Main.kt", entries[0].detail)
        assertEquals("s1", entries[0].sessionId)
        assertEquals(AiActivityKind.PERMISSION_GRANTED, entries[1].kind)
        assertTrue(entries[1].timestamp > 0)
    }

    @Test
    fun tabsAndNewlinesInDetailsAreScrubbed() {
        val audit = audit()
        audit.record("s1", AiActivityKind.TOOL_RUN, "ran\tsomething\nnasty")
        assertEquals("ran something nasty", audit.entries().single().detail)
    }

    @Test
    fun missingFileYieldsEmptyList() {
        assertTrue(audit().entries().isEmpty())
    }

    @Test
    fun clearRemovesEverything() {
        val audit = audit()
        audit.record("s1", AiActivityKind.USER_MESSAGE, "hello")
        audit.clear()
        assertTrue(audit.entries().isEmpty())
    }

    @Test
    fun oversizedLogIsTrimmedToTheNewerHalf() {
        val audit = audit(maxBytes = 200)
        repeat(40) { i -> audit.record("s", AiActivityKind.USER_MESSAGE, "message number $i") }
        val entries = audit.entries()
        assertTrue("expected trimming, got ${entries.size}", entries.size < 40)
        // The newest entry always survives.
        assertEquals("message number 39", entries.last().detail)
    }

    private fun audit(maxBytes: Long = 512 * 1024): AiActivityAudit =
        AiActivityAudit(dir = tmp.newFolder(), maxBytes = maxBytes)
}
