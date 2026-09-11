package com.maragung.arrowide.ai

import com.maragung.arrowide.github.HttpExchange
import com.maragung.arrowide.github.HttpResponse
import com.maragung.arrowide.github.HttpTransport
import com.maragung.arrowide.github.exchangeSuspending
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL

/**
 * Thin typed client for the OpenCode HTTP server (base
 * http://127.0.0.1:<port>). Pure JVM: every REST call goes through the
 * shared [HttpTransport] seam (tests drive a fake), failures are
 * [AiResult] values, and nothing throws except cancellation.
 *
 * The one exception is [events]: [HttpTransport] buffers whole response
 * bodies, which an infinite SSE stream cannot provide, so the stream is
 * read directly from [HttpURLConnection] line by line on
 * [ioDispatcher] (the same URLConnection seam
 * [com.maragung.arrowide.github.HttpUrlConnectionTransport] uses), parsed
 * by [SseParser] and delivered to the caller's handler.
 *
 * Wire formats are parsed defensively (see the internal parse functions
 * below): the OpenCode schema evolves between releases, so unknown keys
 * are ignored, alternative field spellings are tried, and a body that
 * does not match becomes [AiResult.Error] — never a crash.
 */
class OpenCodeClient(
    private val transport: HttpTransport,
    private val baseUrl: String,
    private val ioDispatcher: CoroutineDispatcher,
) {

    // -------------------------------------------------------------------
    // Server
    // -------------------------------------------------------------------

    /** GET /global/health → Ok(true) on 200. */
    suspend fun health(): AiResult<Boolean> {
        val result = perform("GET", "/global/health")
        return when (result) {
            is AiResult.Error -> result
            is AiResult.Ok -> if (result.value.statusCode == 200) {
                AiResult.Ok(true)
            } else {
                AiResult.Error(
                    result.value.statusCode,
                    errorMessage(result.value.statusCode, result.value.bodyText),
                )
            }
        }
    }

    // -------------------------------------------------------------------
    // Providers / credentials
    // -------------------------------------------------------------------

    /**
     * Lists providers and their models. Tries GET /config/providers (a map
     * keyed by provider id) first and falls back to GET /provider (an
     * object with an `all` array); both shapes are understood by
     * [parseProviders].
     */
    suspend fun listProviders(): AiResult<List<AiProvider>> {
        val primary = perform("GET", "/config/providers")
        if (primary is AiResult.Ok && primary.value.statusCode in 200..299) {
            parseQuietly { parseProviders(primary.value.bodyText) }?.let {
                return AiResult.Ok(it)
            }
        }
        val secondary = perform("GET", "/provider")
        if (secondary is AiResult.Ok && secondary.value.statusCode in 200..299) {
            parseQuietly { parseProviders(secondary.value.bodyText) }?.let {
                return AiResult.Ok(it)
            }
        }
        return when {
            primary is AiResult.Error -> primary
            secondary is AiResult.Error -> secondary
            secondary is AiResult.Ok -> AiResult.Error(
                secondary.value.statusCode,
                errorMessage(secondary.value.statusCode, secondary.value.bodyText),
            )
            else -> AiResult.Error(null, UNPARSEABLE_MESSAGE)
        }
    }

    /**
     * GET /provider/auth → providerID -> names of its supported auth
     * methods ([parseProviderAuth] accepts both string and object entries).
     */
    suspend fun listProviderAuth(): AiResult<Map<String, List<String>>> =
        getDecoded("/provider/auth") { parseProviderAuth(it) }

    /**
     * PUT /auth/{providerId} — sets the credentials for a provider. The
     * body is caller-supplied JSON because the shape follows the
     * provider's schema (e.g. `{"type":"api","key":"..."}`); it is sent
     * verbatim and never logged.
     */
    suspend fun setProviderAuth(providerId: String, bodyJson: String): AiResult<Unit> =
        expectOk(
            "PUT",
            "/auth/${segment(providerId)}",
            bodyJson.toByteArray(Charsets.UTF_8),
        )

    // -------------------------------------------------------------------
    // Sessions
    // -------------------------------------------------------------------

    suspend fun listSessions(): AiResult<List<AiSessionInfo>> =
        getDecoded("/session") { parseSessions(it) }

    suspend fun createSession(title: String? = null): AiResult<AiSessionInfo> {
        val body = buildJsonObject {
            title?.let { put("title", it) }
        }.toString().toByteArray(Charsets.UTF_8)
        return decoded("POST", "/session", requestBody = body) { parseSession(it) }
    }

    suspend fun deleteSession(id: String): AiResult<Unit> =
        expectOk("DELETE", "/session/${segment(id)}")

    /**
     * GET /session/{id}/message — the full transcript as
     * info/parts envelopes ([parseMessages]).
     */
    suspend fun messages(sessionId: String, limit: Int = 100): AiResult<List<AiMessage>> =
        getDecoded(
            "/session/${segment(sessionId)}/message",
            mapOf("limit" to limit.toString()),
        ) { parseMessages(it) }

    /**
     * POST /session/{id}/message with a single text part. The response
     * envelope is not returned: the assistant reply streams in via
     * [events] (message.updated etc.). [model]/[agent] are omitted from
     * the body when null.
     */
    suspend fun sendMessage(
        sessionId: String,
        text: String,
        model: String? = null,
        agent: String? = null,
    ): AiResult<Unit> {
        val body = buildJsonObject {
            put(
                "parts",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("type", "text")
                            put("text", text)
                        },
                    )
                },
            )
            model?.let { put("model", it) }
            agent?.let { put("agent", it) }
        }.toString().toByteArray(Charsets.UTF_8)
        return expectOk(
            "POST",
            "/session/${segment(sessionId)}/message",
            body,
        )
    }

    /** POST /session/{id}/abort — cancels the session's current run. */
    suspend fun abort(sessionId: String): AiResult<Unit> =
        expectOk("POST", "/session/${segment(sessionId)}/abort")

    /** GET /session/{id}/diff — files changed by the session (or one message). */
    suspend fun sessionDiff(sessionId: String, messageId: String? = null): AiResult<List<AiFileDiff>> {
        val query = messageId?.let { mapOf("messageID" to it) } ?: emptyMap()
        return getDecoded("/session/${segment(sessionId)}/diff", query) { parseFileDiffs(it) }
    }

    /**
     * POST /session/{id}/permissions/{permissionId} — answers a permission
     * request from [AiPermissionRequest]. Wire body:
     * `{"response":"once"|"reject","remember":true?}` — the remember flag
     * is only sent when true, matching the endpoint's optional field.
     */
    suspend fun respondToPermission(
        sessionId: String,
        permissionId: String,
        allow: Boolean,
        remember: Boolean = false,
    ): AiResult<Unit> {
        val body = buildJsonObject {
            put("response", if (allow) "once" else "reject")
            if (remember) put("remember", true)
        }.toString().toByteArray(Charsets.UTF_8)
        return expectOk(
            "POST",
            "/session/${segment(sessionId)}/permissions/${segment(permissionId)}",
            body,
        )
    }

    // -------------------------------------------------------------------
    // SSE
    // -------------------------------------------------------------------

    /**
     * Subscribes to GET /event. **Runs until the calling coroutine is
     * cancelled** — there is no return handle; launch it in a scope whose
     * lifetime is the subscription (e.g. a Compose screen's scope) and
     * cancel that scope to unsubscribe:
     *
     * ```kotlin
     * val job = scope.launch { client.events { name, json -> ... } }
     * job.cancel()
     * ```
     *
     * The first event is `server.connected`, then bus events
     * (`message.updated`, `permission.updated`, `session.updated`, ...)
     * arrive as they happen. [onEvent] receives the raw event name and the
     * raw JSON payload string (multi-line `data:` lines are joined per the
     * SSE spec). The connection is read on [ioDispatcher]; on EOF, read
     * timeout (60s idle) or I/O error the stream is reconnected
     * automatically after a short delay, so a server restart does not end
     * the subscription.
     */
    suspend fun events(onEvent: suspend (eventName: String, dataJson: String) -> Unit) {
        withContext(ioDispatcher) {
            while (currentCoroutineContext().isActive) {
                try {
                    streamOnce(onEvent)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    // Stream ended or broke: reconnect below.
                }
                delay(RECONNECT_DELAY_MS)
            }
        }
    }

    /** One SSE connection from open to EOF; returns on EOF or throws. */
    private suspend fun streamOnce(onEvent: suspend (eventName: String, dataJson: String) -> Unit) {
        val connection = URL(eventsUrl()).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = false
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "text/event-stream")
            connection.setRequestProperty("User-Agent", USER_AGENT)
            if (connection.responseCode != 200) return
            val parser = SseParser()
            BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8)).use { reader ->
                while (true) {
                    // Interruptible so cancelling the subscription closes
                    // the read instead of leaking a blocked thread.
                    val line = runInterruptible { reader.readLine() } ?: break
                    for (event in parser.feed(line + "\n")) {
                        onEvent(event.name, event.data)
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun eventsUrl(): String = "${baseUrl.trimEnd('/')}/event"

    // -------------------------------------------------------------------
    // Plumbing
    // -------------------------------------------------------------------

    private fun buildUrl(path: String, query: Map<String, String>): String {
        val url = StringBuilder(baseUrl.trimEnd('/')).append(path)
        if (query.isNotEmpty()) {
            url.append('?')
            url.append(
                query.entries.joinToString("&") { "${it.key}=${segment(it.value)}" },
            )
        }
        return url.toString()
    }

    /** Caller headers: always Accept; Content-Type with a body. */
    private fun headersFor(hasBody: Boolean): Map<String, String> =
        if (hasBody) {
            mapOf("Accept" to "application/json", "Content-Type" to "application/json")
        } else {
            mapOf("Accept" to "application/json")
        }

    /** One wire exchange; network failures become [AiResult.Error]. */
    private suspend fun perform(
        method: String,
        path: String,
        query: Map<String, String> = emptyMap(),
        requestBody: ByteArray? = null,
    ): AiResult<HttpResponse> = try {
        AiResult.Ok(
            transport.exchangeSuspending(
                HttpExchange(method, buildUrl(path, query), headersFor(requestBody != null), requestBody),
            ),
        )
    } catch (e: IOException) {
        AiResult.Error(null, e.message ?: "Network error")
    }

    /** GET + decode for JSON endpoints. */
    private suspend fun <T> getDecoded(
        path: String,
        query: Map<String, String> = emptyMap(),
        decode: (String) -> T,
    ): AiResult<T> = decoded("GET", path, query, null, decode)

    private suspend fun <T> decoded(
        method: String,
        path: String,
        query: Map<String, String> = emptyMap(),
        requestBody: ByteArray? = null,
        decode: (String) -> T,
    ): AiResult<T> {
        val result = perform(method, path, query, requestBody)
        return when (result) {
            is AiResult.Error -> result
            is AiResult.Ok ->
                if (result.value.statusCode in 200..299) {
                    // kotlinx SerializationException extends
                    // IllegalArgumentException — both are parse failures here.
                    try {
                        AiResult.Ok(decode(result.value.bodyText))
                    } catch (e: IllegalArgumentException) {
                        AiResult.Error(null, UNPARSEABLE_MESSAGE)
                    }
                } else {
                    AiResult.Error(
                        result.value.statusCode,
                        errorMessage(result.value.statusCode, result.value.bodyText),
                    )
                }
        }
    }

    /** Any 2xx status is success; the body is not parsed. */
    private suspend fun expectOk(
        method: String,
        path: String,
        requestBody: ByteArray? = null,
    ): AiResult<Unit> {
        val result = perform(method, path, requestBody = requestBody)
        return when (result) {
            is AiResult.Error -> result
            is AiResult.Ok ->
                if (result.value.statusCode in 200..299) {
                    AiResult.Ok(Unit)
                } else {
                    AiResult.Error(
                        result.value.statusCode,
                        errorMessage(result.value.statusCode, result.value.bodyText),
                    )
                }
        }
    }

    /** URL-encodes one path/query segment (URLEncoder's '+' becomes %20). */
    private fun segment(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private companion object {
        private const val USER_AGENT = "ArrowIDE/0.1"
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 60_000
        private const val RECONNECT_DELAY_MS = 1_000L
        private const val UNPARSEABLE_MESSAGE = "Could not parse OpenCode response"
    }
}

/**
 * Human message for an HTTP failure: a fixed base per status plus the
 * body's detail, truncated (secret hygiene: nothing large or echoed from
 * a request body ever reaches the UI).
 */
internal fun errorMessage(statusCode: Int, bodyText: String): String {
    val base = when (statusCode) {
        401 -> "Unauthorized — check the provider credentials"
        404 -> "Not found — the session or resource may be gone"
        else -> "OpenCode request failed (HTTP $statusCode)"
    }
    val detail = try {
        (opencodeJson.parseToJsonElement(bodyText) as? JsonObject)
            ?.stringField("message")
            ?: bodyText.lineSequence().firstOrNull { it.isNotBlank() }
    } catch (e: IllegalArgumentException) {
        bodyText.lineSequence().firstOrNull { it.isNotBlank() }
    }
    return if (detail.isNullOrBlank()) {
        base
    } else {
        "$base: ${detail.take(MAX_ERROR_DETAIL_LENGTH).trim()}"
    }
}

private const val MAX_ERROR_DETAIL_LENGTH = 200

/** Runs [block], mapping a parse failure (IllegalArgumentException) to null. */
private fun <T> parseQuietly(block: () -> T): T? = try {
    block()
} catch (e: IllegalArgumentException) {
    null
}

/**
 * String value of [key] in this object; null when absent, null-literal,
 * or non-primitive (JsonNull is a JsonPrimitive whose content would
 * otherwise read as the string "null").
 */
internal fun JsonObject.stringField(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

/**
 * Parses a provider list from either endpoint shape:
 *
 *  - GET /config/providers: `{ "<id>": { "name": ..., "models": {...} } }`
 *  - GET /provider: `{ "all": [ { "id": ..., "models": [...] } ], ... }`
 *
 * `models` may be a map keyed by model id or an array of objects; both
 * are accepted. Every field is optional with an id/name fallback chain.
 */
internal fun parseProviders(body: String): List<AiProvider> {
    val root = opencodeJson.parseToJsonElement(body)
    val entries: List<Pair<String, JsonObject>> = when (root) {
        is JsonObject -> {
            val all = root["all"]
            if (all is JsonArray) {
                all.filterIsInstance<JsonObject>()
                    .map { (it.stringField("id") ?: it.stringField("providerID") ?: "") to it }
            } else {
                root.entries.mapNotNull { (key, value) ->
                    (value as? JsonObject)?.let { key to it }
                }
            }
        }
        is JsonArray -> root.filterIsInstance<JsonObject>()
            .map { (it.stringField("id") ?: it.stringField("providerID") ?: "") to it }
        else -> throw IllegalArgumentException("Expected a JSON object or array")
    }
    return entries
        .map { (key, obj) ->
            val id = obj.stringField("id") ?: obj.stringField("providerID") ?: key
            AiProvider(
                id = id,
                name = obj.stringField("name") ?: id,
                models = parseModels(obj["models"]),
            )
        }
        .distinctBy { it.id }
}

/** Provider `models` field: object keyed by model id, or array of objects. */
private fun parseModels(element: JsonElement?): List<AiModel> =
    when (element) {
        is JsonObject -> element.entries.mapNotNull { (key, value) ->
            val obj = value as? JsonObject
            val id = obj?.stringField("id") ?: key
            AiModel(id = id, name = obj?.stringField("name") ?: id)
        }
        is JsonArray -> element.filterIsInstance<JsonObject>().map { obj ->
            val id = obj.stringField("id") ?: obj.stringField("modelID") ?: ""
            AiModel(id = id, name = obj.stringField("name") ?: id)
        }
        else -> emptyList()
    }

/**
 * Parses GET /provider/auth: `{ "<providerID>": [ "api", { "id": ... } ] }`
 * — entries may be strings or objects; object entries report their
 * id/type/name/label (first present wins).
 */
internal fun parseProviderAuth(body: String): Map<String, List<String>> {
    val root = opencodeJson.parseToJsonElement(body) as? JsonObject
        ?: throw IllegalArgumentException("Expected a JSON object")
    return root.entries.mapNotNull { (providerId, value) ->
        val methods = value as? JsonArray ?: return@mapNotNull null
        providerId to methods.mapNotNull { element ->
            when (element) {
                is JsonPrimitive -> element.contentOrNull
                is JsonObject -> element.stringField("id")
                    ?: element.stringField("type")
                    ?: element.stringField("name")
                    ?: element.stringField("label")
                else -> null
            }
        }
    }.toMap()
}

/** Parses one session object (POST /session). */
internal fun parseSession(body: String): AiSessionInfo {
    val obj = opencodeJson.parseToJsonElement(body) as? JsonObject
        ?: throw IllegalArgumentException("Expected a JSON object")
    return sessionFrom(obj)
}

/** Parses GET /session (an array of session objects). */
internal fun parseSessions(body: String): List<AiSessionInfo> {
    val root = opencodeJson.parseToJsonElement(body) as? JsonArray
        ?: throw IllegalArgumentException("Expected a JSON array")
    return root.filterIsInstance<JsonObject>().map { sessionFrom(it) }
}

/** Session mapping with the `updatedAt` vs `time.updated` fallback. */
private fun sessionFrom(obj: JsonObject): AiSessionInfo = AiSessionInfo(
    id = obj.stringField("id") ?: "",
    title = obj.stringField("title") ?: "",
    updatedAt = obj.stringField("updatedAt")
        ?: (obj["time"] as? JsonObject)?.stringField("updated"),
)

/**
 * Parses GET /session/{id}/message: `[{ "info": {...}, "parts": [...] }]`.
 * Hand-parsed (not via @Serializable DTOs) because part fields vary by
 * part type and server release; the tool name is accepted from `tool`
 * (string or object) or `toolName`.
 */
internal fun parseMessages(body: String): List<AiMessage> {
    val root = opencodeJson.parseToJsonElement(body) as? JsonArray
        ?: throw IllegalArgumentException("Expected a JSON array")
    return root.filterIsInstance<JsonObject>().map { envelope ->
        val info = envelope["info"] as? JsonObject
        val parts = (envelope["parts"] as? JsonArray)
            ?.filterIsInstance<JsonObject>()
            ?.map { part ->
                AiMessagePart(
                    type = part.stringField("type") ?: "",
                    text = part.stringField("text"),
                    state = part.stringField("state"),
                    toolName = partToolName(part),
                )
            }
            .orEmpty()
        AiMessage(
            id = info?.stringField("id") ?: "",
            role = info?.stringField("role") ?: "",
            parts = parts,
        )
    }
}

/** Tool name of one part: `tool` as string, `tool` as {name}, or `toolName`. */
private fun partToolName(part: JsonObject): String? = when (val tool = part["tool"]) {
    is JsonPrimitive -> tool.contentOrNull
    is JsonObject -> tool.stringField("name") ?: tool.stringField("id")
    else -> part.stringField("toolName")
}

/**
 * Parses GET /session/{id}/diff: an array of file diffs. Field spellings
 * are tried defensively (`path`/`file`/`filename` for the path).
 */
internal fun parseFileDiffs(body: String): List<AiFileDiff> {
    val root = opencodeJson.parseToJsonElement(body) as? JsonArray
        ?: throw IllegalArgumentException("Expected a JSON array")
    return root.filterIsInstance<JsonObject>().map { obj ->
        AiFileDiff(
            path = obj.stringField("path")
                ?: obj.stringField("file")
                ?: obj.stringField("filename")
                ?: "",
            before = obj.stringField("before"),
            after = obj.stringField("after"),
        )
    }
}

/**
 * Parses the payload of a `permission.updated` SSE event into an
 * [AiPermissionRequest] (field spellings tried defensively:
 * `sessionID`/`sessionId`, `id`/`permissionID`, `pattern`/`path`,
 * `description`/`message`). Returns null when the payload is not a JSON
 * object — callers skip such events rather than crash.
 */
fun parsePermissionRequest(dataJson: String): AiPermissionRequest? = try {
    val obj = opencodeJson.parseToJsonElement(dataJson) as? JsonObject ?: return null
    AiPermissionRequest(
        sessionId = obj.stringField("sessionID")
            ?: obj.stringField("sessionId")
            ?: "",
        id = obj.stringField("id")
            ?: obj.stringField("permissionID")
            ?: "",
        pattern = obj.stringField("pattern") ?: obj.stringField("path"),
        title = obj.stringField("title"),
        description = obj.stringField("description") ?: obj.stringField("message"),
    )
} catch (e: IllegalArgumentException) {
    null
}

/**
 * One completed SSE event: its name (default "message" per the spec when
 * no `event:` line was seen) and its payload (all `data:` lines joined
 * with newlines).
 */
internal data class SseEvent(val name: String, val data: String)

/**
 * Incremental server-sent-events parser: feed it arbitrary string chunks
 * (the reader's lines, raw socket chunks, anything); it buffers partial
 * lines and returns the events that became complete with each chunk.
 *
 * Handles: `event:`/`data:` fields (a single leading space after the
 * colon is stripped), multi-line data, CRLF and bare-CR terminators,
 * comment lines (`:`), and `id:`/`retry:` (parsed and ignored). An event
 * dispatches on the blank line; events without any `data:` line are
 * dropped per the SSE spec.
 */
internal class SseParser {

    private var buffer = ""
    private var eventName: String? = null
    private val dataLines = mutableListOf<String>()

    /** Feeds [chunk], returning every event completed by it (often empty). */
    fun feed(chunk: String): List<SseEvent> {
        buffer += chunk
        val events = mutableListOf<SseEvent>()
        while (true) {
            val newline = buffer.indexOf('\n')
            if (newline < 0) break
            val line = buffer.substring(0, newline).removeSuffix("\r")
            buffer = buffer.substring(newline + 1)
            handleLine(line)?.let { events += it }
        }
        return events
    }

    private fun handleLine(line: String): SseEvent? = when {
        line.isEmpty() -> dispatch()
        line.startsWith(":") -> null // comment
        else -> {
            val colon = line.indexOf(':')
            val field = if (colon < 0) line else line.substring(0, colon)
            var value = if (colon < 0) "" else line.substring(colon + 1)
            if (value.startsWith(" ")) value = value.substring(1)
            when (field) {
                "event" -> eventName = value
                "data" -> dataLines += value
                // id/retry are recognized but not tracked (no Last-Event-ID
                // resume; the bus replays state via the REST endpoints).
                "id", "retry" -> Unit
            }
            null
        }
    }

    /** Completes the current event, or null when it had no data lines. */
    private fun dispatch(): SseEvent? {
        val event = if (dataLines.isEmpty()) {
            null
        } else {
            SseEvent(eventName ?: DEFAULT_EVENT_NAME, dataLines.joinToString("\n"))
        }
        eventName = null
        dataLines.clear()
        return event
    }

    private companion object {
        private const val DEFAULT_EVENT_NAME = "message"
    }
}
