package com.maragung.arrowide.github

import java.io.IOException

/**
 * Scripted [HttpTransport] for unit tests: responses are registered per
 * URL (the exact URL or the same path plus a query string) and every
 * request is recorded, headers included. No network.
 */
class FakeTransport : HttpTransport {

    private class Route(
        val url: String,
        val respond: (HttpExchange) -> HttpResponse,
    )

    private val routes = mutableListOf<Route>()

    /** Every exchange seen so far, in order. */
    val requests = mutableListOf<HttpExchange>()

    /** Scripts a fixed [response] for requests to [url]. */
    fun route(url: String, response: HttpResponse) {
        route(url) { response }
    }

    /**
     * Scripts a computed response for requests to [url]; [respond] may
     * throw to simulate a network failure.
     */
    fun route(url: String, respond: (HttpExchange) -> HttpResponse) {
        routes += Route(url, respond)
    }

    /** Scripts an [error] for requests to [url]. */
    fun fail(url: String, error: IOException) {
        route(url) { throw error }
    }

    override fun exchange(request: HttpExchange): HttpResponse {
        requests += request
        // Exact-URL routes win over path-only routes, so a redirect chain
        // can script distinct responses for the same path with and
        // without its query string.
        val route = routes.firstOrNull { it.url == request.url }
            ?: routes.firstOrNull { request.url.startsWith(it.url + "?") }
            ?: throw AssertionError("No scripted response for ${request.method} ${request.url}")
        return route.respond(request)
    }
}

/** Plain-text response. */
fun textResponse(statusCode: Int, body: String): HttpResponse =
    HttpResponse(statusCode, emptyMap(), body.toByteArray(Charsets.UTF_8))

/** JSON response. */
fun jsonResponse(statusCode: Int, body: String): HttpResponse =
    HttpResponse(
        statusCode,
        mapOf("Content-Type" to listOf("application/json")),
        body.toByteArray(Charsets.UTF_8),
    )

/** Redirect response with a Location header. */
fun redirectResponse(statusCode: Int, location: String): HttpResponse =
    HttpResponse(statusCode, mapOf("Location" to listOf(location)), ByteArray(0))
