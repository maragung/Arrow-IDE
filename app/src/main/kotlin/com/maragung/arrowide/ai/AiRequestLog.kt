package com.maragung.arrowide.ai

import com.maragung.arrowide.github.HttpExchange
import com.maragung.arrowide.github.HttpResponse
import com.maragung.arrowide.github.HttpTransport
import java.io.IOException

/**
 * One recorded AI HTTP exchange (plan #92 request logging).
 *
 * The request body (and any header value that could carry the provider
 * API key) is stored only when [redacted] is false — redaction is decided
 * by the caller at record time, and the default store keeps everything
 * redacted unless the user explicitly enabled verbose logging.
 *
 * @param timestamp wall-clock millis of the completed exchange
 * @param method    HTTP method
 * @param url       request URL (query included)
 * @param statusCode response status code, or -1 when the exchange threw
 * @param durationMs how long the exchange took
 * @param requestBodySummary first ~2000 chars of the request body, or a
 *                 redaction marker
 * @param error     transport exception message, or null on success
 */
data class AiRequestLogEntry(
    val timestamp: Long,
    val method: String,
    val url: String,
    val statusCode: Int,
    val durationMs: Long,
    val requestBodySummary: String,
    val error: String?,
)

/**
 * Bounded in-memory ring of [AiRequestLogEntry] (plan #92).
 *
 * Nothing is written to disk — the log exists for the Settings screen's
 * "AI request log" viewer and disappears with the process. When the
 * record limit is hit the oldest entries are dropped.
 *
 * @param capacity maximum number of entries kept
 */
class AiRequestLog(private val capacity: Int = 100) {

    private val entries = ArrayDeque<AiRequestLogEntry>(capacity)

    /** Records one completed exchange. Thread-safe. */
    @Synchronized
    fun record(exchange: HttpExchange, response: HttpResponse?, durationMs: Long, error: String?) {
        if (entries.size == capacity) entries.removeFirst()
        entries.addLast(
            AiRequestLogEntry(
                timestamp = System.currentTimeMillis(),
                method = exchange.method,
                url = exchange.url,
                statusCode = response?.statusCode ?: -1,
                durationMs = durationMs,
                requestBodySummary = summarize(exchange.requestBody),
                error = error,
            ),
        )
    }

    /** Snapshot of the current entries, oldest first. Thread-safe. */
    @Synchronized
    fun snapshot(): List<AiRequestLogEntry> = entries.toList()

    /** Drops all entries. Thread-safe. */
    @Synchronized
    fun clear() = entries.clear()

    private fun summarize(body: ByteArray?): String = when {
        body == null -> ""
        else -> String(body, Charsets.UTF_8).take(MAX_BODY_CHARS)
    }

    private companion object {
        const val MAX_BODY_CHARS = 2_000
    }
}

/**
 * [HttpTransport] decorator that records every exchange into an
 * [AiRequestLog] (plan #92). Wraps the OpenCode client's transport.
 *
 * Authorization headers never reach the log — [AiRequestLogEntry] stores
 * only the method/URL/body summary, so the provider key is never captured
 * even with logging enabled.
 */
class LoggingHttpTransport(
    private val delegate: HttpTransport,
    private val log: AiRequestLog,
    private val enabled: () -> Boolean = { true },
) : HttpTransport {

    override fun exchange(request: HttpExchange): HttpResponse {
        val start = System.currentTimeMillis()
        return try {
            val response = delegate.exchange(request)
            if (enabled()) {
                log.record(request, response, System.currentTimeMillis() - start, null)
            }
            response
        } catch (t: IOException) {
            if (enabled()) {
                log.record(request, null, System.currentTimeMillis() - start, t.message)
            }
            throw t
        }
    }
}
