package com.maragung.arrowide.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SseParser] against chunked, CRLF-terminated and multi-line input:
 * event/data pairs, comments, id/retry lines, default event names and
 * data-less events. Pure string feeding — no network.
 */
class SseParserTest {

    @Test
    fun parsesEventAndData() {
        val parser = SseParser()

        val events = parser.feed("event: server.connected\r\ndata: {\"ok\":true}\r\n\r\n")

        assertEquals(listOf(SseEvent("server.connected", "{\"ok\":true}")), events)
    }

    @Test
    fun handlesLfOnlyLines() {
        val parser = SseParser()

        val events = parser.feed("event: message.updated\ndata: {\"id\":\"m1\"}\n\n")

        assertEquals(listOf(SseEvent("message.updated", "{\"id\":\"m1\"}")), events)
    }

    @Test
    fun joinsMultiLineDataWithNewlines() {
        val parser = SseParser()

        val events = parser.feed(
            "event: message.updated\n" +
                "data: {\n" +
                "data:   \"id\": \"m1\"\n" +
                "data: }\n" +
                "\n",
        )

        assertEquals(1, events.size)
        assertEquals("message.updated", events[0].name)
        assertEquals("{\n  \"id\": \"m1\"\n}", events[0].data)
    }

    @Test
    fun feedsAcrossArbitraryChunkBoundaries() {
        val parser = SseParser()
        val collected = mutableListOf<SseEvent>()

        // The stream arrives in chunks that split lines mid-field.
        for (chunk in listOf(
            "event: session.upd",
            "ated\nda",
            "ta: {\"id\":\"s1\"",
            "}\n",
            "\n",
            "data: second\n",
            "\n",
        )) {
            collected += parser.feed(chunk)
        }

        assertEquals(
            listOf(
                SseEvent("session.updated", "{\"id\":\"s1\"}"),
                SseEvent("message", "second"),
            ),
            collected,
        )
    }

    @Test
    fun defaultEventNameIsMessage() {
        val parser = SseParser()

        val events = parser.feed("data: payload\n\n")

        assertEquals(listOf(SseEvent("message", "payload")), events)
    }

    @Test
    fun commentsAndIdAndRetryLinesAreIgnored() {
        val parser = SseParser()

        val events = parser.feed(
            ": this is a comment\n" +
                "id: 42\n" +
                "retry: 5000\n" +
                "event: message.updated\n" +
                "data: {}\n" +
                "\n",
        )

        assertEquals(listOf(SseEvent("message.updated", "{}")), events)
    }

    @Test
    fun eventWithoutDataIsDropped() {
        val parser = SseParser()

        val events = parser.feed("event: heartbeat\n\n")

        assertTrue(events.isEmpty())
    }

    @Test
    fun spaceAfterTheColonIsStrippedButDeeperSpacesAreKept() {
        val parser = SseParser()

        val events = parser.feed("data:  two leading spaces kept\nevent:  spaced\n\n")

        assertEquals(
            listOf(SseEvent("spaced", " two leading spaces kept")),
            events,
        )
    }

    @Test
    fun crlfIsStrippedFromDataLines() {
        val parser = SseParser()

        val events = parser.feed("data: line\r\n\r\n")

        assertEquals(listOf(SseEvent("message", "line")), events)
    }

    @Test
    fun emptyDataValueProducesEmptyPayload() {
        val parser = SseParser()

        val events = parser.feed("event: ping\ndata:\n\n")

        assertEquals(listOf(SseEvent("ping", "")), events)
    }

    @Test
    fun consecutiveEventsResetStateBetweenDispatches() {
        val parser = SseParser()

        val events = parser.feed(
            "event: one\ndata: 1\n\n" +
                "data: 2\n\n" +
                "event: three\ndata: 3\n\n",
        )

        assertEquals(
            listOf(
                SseEvent("one", "1"),
                SseEvent("message", "2"),
                SseEvent("three", "3"),
            ),
            events,
        )
    }
}
