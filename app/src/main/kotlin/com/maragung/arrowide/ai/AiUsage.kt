package com.maragung.arrowide.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Token usage of one AI exchange (plan #77). The OpenCode server reports
 * tokens per message part in several shapes depending on release and
 * provider; [AiUsageParser] normalizes them into this pair.
 */
data class AiUsage(
    val inputTokens: Long,
    val outputTokens: Long,
) {
    val totalTokens: Long get() = inputTokens + outputTokens
}

/**
 * Defensive parser for the token-count field of ONE message part
 * (plan #77). Never throws: anything unparseable yields null and callers
 * simply skip the part. Same hand-parsing style as the parse* functions
 * in [OpenCodeClient.kt].
 *
 * Accepted shapes (numbers may be JSON numbers OR numeric strings):
 *  - `tokens` as {input, output}
 *  - `tokens` as {in, out}
 *  - `tokens` as {prompt, completion}
 *  - flat {inputTokens, outputTokens}
 */
object AiUsageParser {

    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Parses one message-part JSON object; null when no counts are found. */
    fun parsePart(partJson: String): AiUsage? = try {
        val obj = json.parseToJsonElement(partJson) as? JsonObject ?: return null
        val tokens = obj["tokens"] as? JsonObject
        when {
            tokens != null -> usageFrom(
                input = tokens.numberField("input") ?: tokens.numberField("in")
                    ?: tokens.numberField("prompt"),
                output = tokens.numberField("output") ?: tokens.numberField("out")
                    ?: tokens.numberField("completion"),
            )
            else -> usageFrom(
                input = obj.numberField("inputTokens"),
                output = obj.numberField("outputTokens"),
            )
        }
    } catch (e: IllegalArgumentException) {
        null
    }

    /**
     * Cost in USD of [usage] at per-million-token rates. Null rates mean
     * unknown pricing — we return null rather than fabricate a number.
     */
    fun estimateCost(
        usage: AiUsage,
        inputPerMillion: Double?,
        outputPerMillion: Double?,
    ): Double? {
        if (inputPerMillion == null || outputPerMillion == null) return null
        return (usage.inputTokens / 1_000_000.0) * inputPerMillion +
            (usage.outputTokens / 1_000_000.0) * outputPerMillion
    }

    private fun usageFrom(input: Long?, output: Long?): AiUsage? =
        if (input != null && output != null) AiUsage(input, output) else null
}

/**
 * Long value of [key] in this object; accepts a JSON number or a numeric
 * string ("1234"); null when absent, null-literal, non-primitive or not
 * numeric. Companion to [stringField] for the token-count shapes above.
 */
private fun JsonObject.numberField(key: String): Long? =
    (this[key] as? JsonPrimitive)?.longOrNull
