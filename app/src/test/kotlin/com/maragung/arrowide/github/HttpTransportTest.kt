package com.maragung.arrowide.github

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/**
 * Redirect-policy tests for [followRedirects] (the loop every
 * [HttpUrlConnectionTransport] exchange runs), driven by a
 * [FakeTransport] — the real HttpURLConnection plumbing needs a live
 * server and is covered by CI usage instead. No network.
 */
class HttpTransportTest {

    private val token = "faketoken_0123456789abcdefghijklmnopqrstuvwxyz"

    private fun authorizedGet(url: String) = HttpExchange(
        method = "GET",
        url = url,
        requestHeaders = mapOf(
            "Authorization" to "Bearer $token",
            "Accept" to "application/vnd.github+json",
        ),
        requestBody = null,
    )

    @Test
    fun followRedirects_dropsAuthorizationOnCrossHostRedirect() {
        val fake = FakeTransport()
        // GitHub's job-log endpoint 302s to a signed blob URL.
        fake.route(
            "https://api.github.com/repos/octocat/Hello-World/actions/jobs/42/logs",
            redirectResponse(
                302,
                "https://productionresultssa9.blob.core.windows.net/actions-results/9b0d6f/log",
            ),
        )
        fake.route(
            "https://productionresultssa9.blob.core.windows.net/actions-results/9b0d6f/log",
            textResponse(200, "2026-01-01T00:00:00Z Run npm install\nok"),
        )

        val response = followRedirects(authorizedGet(
            "https://api.github.com/repos/octocat/Hello-World/actions/jobs/42/logs",
        )) { fake.exchange(it) }

        assertEquals(200, response.statusCode)
        assertEquals("2026-01-01T00:00:00Z Run npm install\nok", response.bodyText)
        assertEquals("two requests (302 + follow)", 2, fake.requests.size)
        assertEquals("Bearer $token", fake.requests[0].requestHeaders["Authorization"])
        assertFalse(
            "Authorization dropped for the blob host",
            fake.requests[1].requestHeaders.containsKey("Authorization"),
        )
    }

    @Test
    fun followRedirects_keepsAuthorizationOnSameHostRedirect() {
        val fake = FakeTransport()
        fake.route(
            "https://api.github.com/user/repos",
            redirectResponse(302, "https://api.github.com/user/repos?page=2"),
        )
        fake.route(
            "https://api.github.com/user/repos?page=2",
            jsonResponse(200, "[]"),
        )

        val response = followRedirects(authorizedGet("https://api.github.com/user/repos")) {
            fake.exchange(it)
        }

        assertEquals(200, response.statusCode)
        assertEquals(2, fake.requests.size)
        assertEquals("Bearer $token", fake.requests[1].requestHeaders["Authorization"])
    }

    @Test
    fun followRedirects_resolvesRelativeLocation() {
        val fake = FakeTransport()
        fake.route(
            "https://api.github.com/a",
            redirectResponse(302, "/b/c"),
        )
        fake.route("https://api.github.com/b/c", textResponse(200, "ok"))

        val response = followRedirects(authorizedGet("https://api.github.com/a")) {
            fake.exchange(it)
        }

        assertEquals(200, response.statusCode)
        assertEquals("https://api.github.com/b/c", fake.requests[1].url)
    }

    @Test
    fun followRedirects_convertsPostToGetOn302AndDropsBody() {
        val fake = FakeTransport()
        fake.route("https://api.github.com/post-here", redirectResponse(302, "/after"))
        fake.route("https://api.github.com/after", textResponse(200, "ok"))

        val response = followRedirects(
            HttpExchange(
                method = "POST",
                url = "https://api.github.com/post-here",
                requestHeaders = mapOf("Authorization" to "Bearer $token"),
                requestBody = "payload".toByteArray(Charsets.UTF_8),
            ),
        ) { fake.exchange(it) }

        assertEquals(200, response.statusCode)
        assertEquals("GET", fake.requests[1].method)
        assertEquals(null, fake.requests[1].requestBody)
    }

    @Test
    fun followRedirects_preservesMethodAndBodyOn307() {
        val fake = FakeTransport()
        fake.route("https://api.github.com/post-here", redirectResponse(307, "/after"))
        fake.route("https://api.github.com/after", textResponse(200, "ok"))

        val response = followRedirects(
            HttpExchange(
                method = "POST",
                url = "https://api.github.com/post-here",
                requestHeaders = emptyMap(),
                requestBody = "payload".toByteArray(Charsets.UTF_8),
            ),
        ) { fake.exchange(it) }

        assertEquals(200, response.statusCode)
        assertEquals("POST", fake.requests[1].method)
        assertEquals(
            "payload",
            String(fake.requests[1].requestBody ?: ByteArray(0), Charsets.UTF_8),
        )
    }

    @Test
    fun followRedirects_returnsResponseWithNoLocationHeader() {
        val fake = FakeTransport()
        fake.route("https://api.github.com/a", textResponse(302, ""))

        val response = followRedirects(authorizedGet("https://api.github.com/a")) {
            fake.exchange(it)
        }

        assertEquals(302, response.statusCode)
        assertEquals(1, fake.requests.size)
    }

    @Test
    fun followRedirects_returnsNonRedirectResponse() {
        val fake = FakeTransport()
        fake.route("https://api.github.com/missing", jsonResponse(404, "{\"message\":\"Not Found\"}"))

        val response = followRedirects(authorizedGet("https://api.github.com/missing")) {
            fake.exchange(it)
        }

        assertEquals(404, response.statusCode)
        assertEquals(1, fake.requests.size)
    }

    @Test
    fun followRedirects_followsUpToFiveRedirects() {
        val fake = FakeTransport()
        // 5 hops: r1 -> r2 -> r3 -> r4 -> r5 -> final.
        for (i in 1..4) {
            fake.route("https://api.github.com/r$i", redirectResponse(302, "/r${i + 1}"))
        }
        fake.route("https://api.github.com/r5", redirectResponse(302, "/final"))
        fake.route("https://api.github.com/final", textResponse(200, "done"))

        val response = followRedirects(authorizedGet("https://api.github.com/r1")) {
            fake.exchange(it)
        }

        assertEquals(200, response.statusCode)
        assertEquals("5 redirects = 6 requests", 6, fake.requests.size)
    }

    @Test
    fun followRedirects_throwsBeyondFiveRedirects() {
        val fake = FakeTransport()
        for (i in 1..6) {
            fake.route("https://api.github.com/r$i", redirectResponse(302, "/r${i + 1}"))
        }
        fake.route("https://api.github.com/r7", textResponse(200, "never reached"))

        try {
            followRedirects(authorizedGet("https://api.github.com/r1")) { fake.exchange(it) }
            fail("expected IOException after the redirect budget")
        } catch (e: IOException) {
            assertTrue(
                "message mentions redirect budget: ${e.message}",
                e.message ?: "".contains("Too many redirects"),
            )
        }
    }
}
