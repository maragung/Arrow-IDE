package com.maragung.arrowide.github

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.IOException
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.URL

/**
 * One HTTP request to be run by an [HttpTransport]. [requestHeaders] are
 * the caller's headers only (Accept, Authorization, Content-Type, ...);
 * the User-Agent is added by [HttpUrlConnectionTransport] itself.
 */
data class HttpExchange(
    val method: String,
    val url: String,
    val requestHeaders: Map<String, String>,
    val requestBody: ByteArray?,
) {
    override fun equals(other: Any?): Boolean =
        other is HttpExchange &&
            method == other.method &&
            url == other.url &&
            requestHeaders == other.requestHeaders &&
            requestBody.contentEquals(other.requestBody)

    override fun hashCode(): Int {
        var result = method.hashCode()
        result = 31 * result + url.hashCode()
        result = 31 * result + requestHeaders.hashCode()
        result = 31 * result + (requestBody?.contentHashCode() ?: 0)
        return result
    }
}

/** One HTTP response, status code included (redirects are NOT followed here). */
data class HttpResponse(
    val statusCode: Int,
    val headers: Map<String, List<String>>,
    val body: ByteArray,
) {
    /** The body decoded as UTF-8. */
    val bodyText: String
        get() = String(body, Charsets.UTF_8)

    /** First value of header [name] (case-insensitive), or null when absent. */
    fun header(name: String): String? =
        headers.entries
            .firstOrNull { it.key.equals(name, ignoreCase = true) }
            ?.value
            ?.firstOrNull { it.isNotBlank() }

    override fun equals(other: Any?): Boolean =
        other is HttpResponse &&
            statusCode == other.statusCode &&
            headers == other.headers &&
            body.contentEquals(other.body)

    override fun hashCode(): Int {
        var result = statusCode
        result = 31 * result + headers.hashCode()
        result = 31 * result + body.contentHashCode()
        return result
    }
}

/**
 * The network seam of the GitHub layer: one request in, one response out
 * (after any redirects the implementation follows). Pure JVM so the whole
 * GitHub package stays unit-testable against a fake.
 */
interface HttpTransport {
    /**
     * Runs [request] to completion.
     *
     * @throws IOException on network failure or when redirects exceed the
     *         implementation's budget
     */
    fun exchange(request: HttpExchange): HttpResponse
}

/** Status codes [followRedirects] follows manually. */
private val REDIRECT_STATUSES = setOf(301, 302, 303, 307, 308)

/** Default redirect budget (mirrors HttpURLConnection's built-in limit). */
internal const val MAX_REDIRECTS = 5

/**
 * Manual redirect loop, shared shape of every [HttpUrlConnectionTransport]
 * exchange. Kept as a top-level function (not buried in the HttpURLConnection
 * code) so the redirect policy is unit-testable with a fake transport:
 *
 *  - up to [maxRedirects] 3xx hops with a Location header, then IOException;
 *  - the Authorization header is dropped on any hop whose host differs
 *    from the host of the ORIGINAL request — GitHub's job-log endpoint 302s
 *    to a signed blob URL, and sending the PAT there would leak it (plan #12);
 *  - 303 (and 301/302 for non-GET/HEAD) converts the follow-up to GET and
 *    drops the body; 307/308 preserve method and body.
 */
internal fun followRedirects(
    initial: HttpExchange,
    maxRedirects: Int = MAX_REDIRECTS,
    doExchange: (HttpExchange) -> HttpResponse,
): HttpResponse {
    val originalHost = hostOf(initial.url)
    var current = initial
    var followed = 0
    while (true) {
        val response = doExchange(current)
        val location = response.header("Location")
        if (response.statusCode !in REDIRECT_STATUSES || location == null) return response
        if (followed == maxRedirects) {
            throw IOException("Too many redirects (more than $maxRedirects) for ${initial.url}")
        }
        followed++
        current = redirectRequest(current, response.statusCode, resolveUrl(current.url, location), originalHost)
    }
}

/** Builds the exchange for one redirect hop; see [followRedirects] for policy. */
private fun redirectRequest(
    current: HttpExchange,
    statusCode: Int,
    nextUrl: String,
    originalHost: String,
): HttpExchange {
    val becomesGet = when (statusCode) {
        303 -> true
        301, 302 -> current.method != "GET" && current.method != "HEAD"
        else -> false // 307/308 preserve the method
    }
    val headers = if (hostOf(nextUrl) == originalHost) {
        current.requestHeaders
    } else {
        current.requestHeaders.filterKeys { !it.equals("Authorization", ignoreCase = true) }
    }
    return HttpExchange(
        method = if (becomesGet) "GET" else current.method,
        url = nextUrl,
        requestHeaders = headers,
        requestBody = if (becomesGet) null else current.requestBody,
    )
}

/** Host of [url], or "" when [url] is not a parseable absolute URL. */
internal fun hostOf(url: String): String =
    try {
        URL(url).host
    } catch (e: MalformedURLException) {
        ""
    }

/** Resolves a Location header value (absolute or relative) against [baseUrl]. */
private fun resolveUrl(baseUrl: String, location: String): String =
    try {
        URL(URL(baseUrl), location).toString()
    } catch (e: MalformedURLException) {
        location
    }

/**
 * Real [HttpTransport] over [HttpURLConnection]. Pure JVM. One exchange =
 * up to [MAX_REDIRECTS] manual redirects (instanceFollowRedirects is off)
 * with the cross-host Authorization drop of [followRedirects].
 *
 * Callers wrap exchanges in [exchangeSuspending] (Dispatchers.IO), the same
 * pattern as the git layer's `executeSuspending`.
 *
 * @param connectTimeoutMs connect timeout in milliseconds
 * @param readTimeoutMs     read timeout in milliseconds
 */
class HttpUrlConnectionTransport(
    private val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
) : HttpTransport {

    override fun exchange(request: HttpExchange): HttpResponse =
        followRedirects(request) { singleExchange(it) }

    /** One wire-level request; redirect policy lives in [exchange]. */
    private fun singleExchange(request: HttpExchange): HttpResponse {
        val connection = URL(request.url).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.instanceFollowRedirects = false
            connection.requestMethod = request.method
            connection.setRequestProperty("User-Agent", USER_AGENT)
            for ((name, value) in request.requestHeaders) {
                connection.setRequestProperty(name, value)
            }
            val body = request.requestBody
            if (body != null) {
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }
            val statusCode = connection.responseCode
            val headers = mutableMapOf<String, List<String>>()
            for ((name, values) in connection.headerFields) {
                if (name != null) headers[name] = values.toList()
            }
            HttpResponse(statusCode, headers, readBody(connection, statusCode))
        } finally {
            connection.disconnect()
        }
    }

    private fun readBody(connection: HttpURLConnection, statusCode: Int): ByteArray {
        val stream = if (statusCode in 200..399) {
            connection.inputStream
        } else {
            connection.errorStream ?: return ByteArray(0)
        }
        return stream.use { it.readBytes() }
    }

    companion object {
        private const val DEFAULT_CONNECT_TIMEOUT_MS = 10_000
        private const val DEFAULT_READ_TIMEOUT_MS = 10_000
        private const val USER_AGENT = "ArrowIDE/0.1"
    }
}

/**
 * Blocking-free wrapper for coroutine call sites: runs
 * [HttpTransport.exchange] on [Dispatchers.IO] and makes cancellation
 * interrupt the waiting thread (same pattern as the git layer's
 * `executeSuspending`).
 */
suspend fun HttpTransport.exchangeSuspending(request: HttpExchange): HttpResponse =
    runInterruptible(Dispatchers.IO) { exchange(request) }
