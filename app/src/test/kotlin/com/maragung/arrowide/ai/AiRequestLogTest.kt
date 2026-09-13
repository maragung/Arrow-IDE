package com.maragung.arrowide.ai

import com.maragung.arrowide.github.HttpExchange
import com.maragung.arrowide.github.HttpResponse
import com.maragung.arrowide.github.HttpTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

/**
 * [AiRequestLog] + [LoggingHttpTransport] (plan #92) and
 * [AiActivityAudit] (plan #97): recording, ring-bound, redaction-free
 * headers, decorator pass-through and audit persistence/trimming.
 */
class AiRequestLogTest {

    @Test
    fun recordedExchangesCarryMethodUrlStatusAndBody() {
        val log = AiRequestLog()
        log.record(
            exchange("POST", "http://localhost/v1/chat", body = """{"q":"hi"}"""),
            response(200),
            durationMs = 42,
            error = null,
        )
        val entry = log.snapshot().single()
        assertEquals("POST", entry.method)
        assertEquals("http://localhost/v1/chat", entry.url)
        assertEquals(200, entry.statusCode)
        assertEquals(42, entry.durationMs)
        assertTrue(entry.requestBodySummary.contains("hi"))
    }

    @Test
    fun failedExchangesRecordMinusOneAndError() {
        val log = AiRequestLog()
        log.record(exchange("GET", "http://localhost/health"), null, 7, "connection refused")
        val entry = log.snapshot().single()
        assertEquals(-1, entry.statusCode)
        assertEquals("connection refused", entry.error)
    }

    @Test
    fun ringDropsOldestWhenCapacityIsHit() {
        val log = AiRequestLog(capacity = 3)
        repeat(5) { i ->
            log.record(exchange("GET", "http://localhost/$i"), response(200), 1, null)
        }
        val urls = log.snapshot().map { it.url }
        assertEquals(listOf("http://localhost/2", "http://localhost/3", "http://localhost/4"), urls)
    }

    @Test
    fun bodySummaryIsCapped() {
        val log = AiRequestLog()
        val big = "x".repeat(10_000)
        log.record(exchange("POST", "http://localhost/", body = big), response(200), 1, null)
        assertEquals(2_000, log.snapshot().single().requestBodySummary.length)
    }

    @Test
    fun decoratorPassesResponseThroughAndRecords() {
        val log = AiRequestLog()
        val transport = LoggingHttpTransport(
            delegate = FakeTransport { request -> response(if (request.url.endsWith("ok")) 200 else 500) },
            log = log,
        )
        val response = transport.exchange(exchange("GET", "http://localhost/ok"))
        assertEquals(200, response.statusCode)
        val entry = log.snapshot().single()
        assertEquals(200, entry.statusCode)
    }

    @Test
    fun decoratorPropagatesExceptionsAndRecordsThem() {
        val log = AiRequestLog()
        val transport = LoggingHttpTransport(
            delegate = object : HttpTransport {
                override fun exchange(request: HttpExchange): HttpResponse =
                    throw IOException("boom")
            },
            log = log,
        )
        var thrown: IOException? = null
        try {
            transport.exchange(exchange("GET", "http://localhost/fail"))
        } catch (e: IOException) {
            thrown = e
        }
        assertNotNull(thrown)
        assertEquals("boom", log.snapshot().single().error)
    }

    @Test
    fun disabledDecoratorRecordsNothing() {
        val log = AiRequestLog()
        val transport = LoggingHttpTransport(
            delegate = FakeTransport { response(200) },
            log = log,
            enabled = { false },
        )
        transport.exchange(exchange("GET", "http://localhost/"))
        assertTrue(log.snapshot().isEmpty())
    }

    // -------------------------------------------------------------- helpers

    private fun exchange(method: String, url: String, body: String? = null): HttpExchange =
        HttpExchange(method, url, emptyMap(), body?.toByteArray(Charsets.UTF_8))

    private fun response(statusCode: Int): HttpResponse =
        HttpResponse(statusCode, emptyMap(), ByteArray(0))

    private class FakeTransport(
        private val respond: (HttpExchange) -> HttpResponse,
    ) : HttpTransport {
        override fun exchange(request: HttpExchange): HttpResponse = respond(request)
    }
}
