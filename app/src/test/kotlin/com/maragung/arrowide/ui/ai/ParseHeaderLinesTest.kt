package com.maragung.arrowide.ui.ai

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [parseHeaderLines] (plan #91): "Name: value" lines → pairs, ignoring
 * malformed input.
 */
class ParseHeaderLinesTest {

    @Test
    fun parsesSimpleLines() {
        assertEquals(
            listOf("X-Org-Id" to "my-org"),
            parseHeaderLines("X-Org-Id: my-org"),
        )
    }

    @Test
    fun parsesMultipleLinesAndTrimsWhitespace() {
        assertEquals(
            listOf(
                "X-Org-Id" to "my-org",
                "HTTP-Referer" to "https://myapp.dev",
            ),
            parseHeaderLines(
                """
                X-Org-Id : my-org
                  HTTP-Referer:  https://myapp.dev
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun valuesMayContainColons() {
        assertEquals(
            listOf("Host" to "example.com:8080"),
            parseHeaderLines("Host: example.com:8080"),
        )
    }

    @Test
    fun blankAndMalformedLinesAreIgnored() {
        assertEquals(
            emptyList<Pair<String, String>>(),
            parseHeaderLines("\n\nno colon here\n: novalue\nName:  \n"),
        )
    }

    @Test
    fun emptyInputYieldsEmptyList() {
        assertEquals(emptyList<Pair<String, String>>(), parseHeaderLines(""))
    }
}
