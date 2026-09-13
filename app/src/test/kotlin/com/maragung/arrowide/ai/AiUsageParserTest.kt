package com.maragung.arrowide.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [AiUsageParser] against the token-count shapes the OpenCode server
 * emits (plan #77): {input, output}, {in, out} and {prompt, completion}
 * keys, numeric strings, garbage input, plus [AiUsageParser.estimateCost]
 * math and its null-rates honesty. Pure parsing — no network.
 */
class AiUsageParserTest {

    @Test
    fun parsesTokensObjectWithInputOutputKeys() {
        val usage = AiUsageParser.parsePart(
            """{"type":"step-start","tokens":{"input":100,"output":50}}""",
        )

        assertEquals(AiUsage(100L, 50L), usage)
        assertEquals(150L, usage!!.totalTokens)
    }

    @Test
    fun parsesTokensObjectWithInOutKeys() {
        val usage = AiUsageParser.parsePart(
            """{"tokens":{"in":7,"out":3}}""",
        )

        assertEquals(AiUsage(7L, 3L), usage)
    }

    @Test
    fun parsesTokensObjectWithPromptCompletionKeys() {
        val usage = AiUsageParser.parsePart(
            """{"tokens":{"prompt":9,"completion":1}}""",
        )

        assertEquals(AiUsage(9L, 1L), usage)
    }

    @Test
    fun parsesFlatInputOutputTokenFields() {
        val usage = AiUsageParser.parsePart(
            """{"type":"text","inputTokens":42,"outputTokens":8}""",
        )

        assertEquals(AiUsage(42L, 8L), usage)
    }

    @Test
    fun parsesNumericStringsAsNumbers() {
        val usage = AiUsageParser.parsePart(
            """{"tokens":{"input":"1234","output":"56"}}""",
        )

        assertEquals(AiUsage(1234L, 56L), usage)
    }

    @Test
    fun returnsNullForGarbageOrIncompleteInput() {
        assertNull(AiUsageParser.parsePart("not json at all"))
        assertNull(AiUsageParser.parsePart("""[1,2,3]"""))
        assertNull(AiUsageParser.parsePart("""{"type":"text"}"""))
        assertNull(AiUsageParser.parsePart("""{"tokens":{"input":5}}"""))
        assertNull(AiUsageParser.parsePart("""{"tokens":"many"}"""))
    }

    @Test
    fun estimateCostScalesPerMillion() {
        val usage = AiUsage(inputTokens = 1_000_000L, outputTokens = 500_000L)

        val cost = AiUsageParser.estimateCost(
            usage,
            inputPerMillion = 3.0,
            outputPerMillion = 15.0,
        )

        assertEquals(3.0 + 7.5, cost!!, 1e-9)
    }

    @Test
    fun estimateCostWithNullRatesIsHonestNull() {
        val usage = AiUsage(inputTokens = 10L, outputTokens = 10L)

        assertNull(AiUsageParser.estimateCost(usage, null, 1.0))
        assertNull(AiUsageParser.estimateCost(usage, 1.0, null))
        assertNull(AiUsageParser.estimateCost(usage, null, null))
    }
}
