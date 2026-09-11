package com.maragung.arrowide.ai

import com.maragung.arrowide.github.FakeTransport
import com.maragung.arrowide.github.HttpExchange
import com.maragung.arrowide.github.jsonResponse
import com.maragung.arrowide.github.textResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * [OpenCodeClient] against a scripted [FakeTransport]: every endpoint's
 * request shape (URL, query, headers, body), success mapping, error
 * mapping and lenient unknown-field handling. No network.
 */
class OpenCodeClientTest {

    private val base = "http://127.0.0.1:43210"
    private lateinit var fake: FakeTransport
    private lateinit var client: OpenCodeClient

    @Before
    fun setUp() {
        fake = FakeTransport()
        client = OpenCodeClient(fake, base, Dispatchers.IO)
    }

    private fun bodyOf(request: HttpExchange): String =
        String(request.requestBody ?: ByteArray(0), Charsets.UTF_8)

    // ---- health ----------------------------------------------------------

    @Test
    fun healthOkOn200() = runBlocking {
        fake.route("$base/global/health", jsonResponse(200, """{"status":"ok"}"""))

        val result = client.health()

        assertTrue(result is AiResult.Ok<*>)
        assertEquals(true, result.okValue())
    }

    @Test
    fun healthErrorOn500() = runBlocking {
        fake.route("$base/global/health", jsonResponse(500, "boom"))

        val result = client.health()

        assertTrue(result is AiResult.Error)
        assertEquals(500, (result as AiResult.Error).statusCode)
    }

    @Test
    fun networkFailureHasNullStatus() = runBlocking {
        fake.fail("$base/session", IOException("server not running"))

        val result = client.listSessions()

        assertTrue(result is AiResult.Error)
        val error = result as AiResult.Error
        assertNull(error.statusCode)
        assertTrue(error.message.contains("server not running"))
    }

    // ---- providers -------------------------------------------------------

    @Test
    fun listProvidersFromConfigProvidersMap() = runBlocking {
        fake.route(
            "$base/config/providers",
            jsonResponse(
                200,
                """
                {
                  "anthropic": {
                    "id": "anthropic",
                    "name": "Anthropic",
                    "npm": "@ai-sdk/anthropic",
                    "models": {
                      "claude-sonnet-4": { "id": "claude-sonnet-4", "name": "Claude Sonnet 4", "limit": 1000 },
                      "claude-haiku-4": {}
                    }
                  },
                  "github-copilot": { "name": "GitHub Copilot" }
                }
                """.trimIndent(),
            ),
        )

        val result = client.listProviders()

        assertTrue(result is AiResult.Ok<*>)
        val providers = result.okValue()
        assertEquals(2, providers.size)
        val anthropic = providers.first { it.id == "anthropic" }
        assertEquals("Anthropic", anthropic.name)
        assertEquals(
            listOf("claude-sonnet-4", "claude-haiku-4"),
            anthropic.models.map { it.id },
        )
        assertEquals("Claude Sonnet 4", anthropic.models.first { it.id == "claude-sonnet-4" }.name)
        // A model without an explicit id falls back to its map key.
        assertEquals("claude-haiku-4", anthropic.models[1].name)
        // A provider without an id falls back to its map key; a model-less
        // provider has an empty model list.
        val copilot = providers.first { it.id == "github-copilot" }
        assertEquals("GitHub Copilot", copilot.name)
        assertTrue(copilot.models.isEmpty())
    }

    @Test
    fun listProvidersFallsBackToProviderAllArray() = runBlocking {
        fake.route("$base/config/providers", jsonResponse(404, "not found"))
        fake.route(
            "$base/provider",
            jsonResponse(
                200,
                """
                {
                  "all": [
                    { "id": "openai", "name": "OpenAI", "models": [ { "id": "gpt-5", "name": "GPT-5" } ] }
                  ],
                  "default": { "providerID": "openai", "modelID": "gpt-5" },
                  "connected": []
                }
                """.trimIndent(),
            ),
        )

        val result = client.listProviders()

        assertTrue(result is AiResult.Ok<*>)
        val providers = result.okValue()
        assertEquals(1, providers.size)
        assertEquals("openai", providers[0].id)
        assertEquals(1, providers[0].models.size)
        assertEquals("GPT-5", providers[0].models[0].name)
    }

    @Test
    fun listProvidersErrorsWhenBothEndpointsFail() = runBlocking {
        fake.route("$base/config/providers", jsonResponse(500, "broken"))
        fake.route("$base/provider", jsonResponse(500, "also broken"))

        val result = client.listProviders()

        assertTrue(result is AiResult.Error)
        assertEquals(500, (result as AiResult.Error).statusCode)
    }

    @Test
    fun listProviderAuthAcceptsStringsAndObjects() = runBlocking {
        fake.route(
            "$base/provider/auth",
            jsonResponse(
                200,
                """
                {
                  "anthropic": [ "api" ],
                  "openai": [ { "id": "api", "type": "api" }, { "label": "OAuth" } ]
                }
                """.trimIndent(),
            ),
        )

        val result = client.listProviderAuth()

        assertTrue(result is AiResult.Ok<*>)
        val auth = result.okValue()
        assertEquals(listOf("api"), auth["anthropic"])
        assertEquals(listOf("api", "OAuth"), auth["openai"])
    }

    @Test
    fun setProviderAuthSendsBodyVerbatim() = runBlocking {
        fake.route("$base/auth/anthropic", jsonResponse(200, "{}"))
        val body = """{"type":"api","key":"sk-ant-secret"}"""

        val result = client.setProviderAuth("anthropic", body)

        assertTrue(result is AiResult.Ok<*>)
        val request = fake.requests.single()
        assertEquals("PUT", request.method)
        assertEquals("$base/auth/anthropic", request.url)
        assertEquals("application/json", request.requestHeaders["Content-Type"])
        assertEquals(body, bodyOf(request))
    }

    @Test
    fun setProviderAuthEncodesTheProviderId() = runBlocking {
        fake.route("$base/auth/custom%20provider", jsonResponse(200, "{}"))

        val result = client.setProviderAuth("custom provider", "{}")

        assertTrue(result is AiResult.Ok<*>)
        assertEquals("$base/auth/custom%20provider", fake.requests.single().url)
    }

    // ---- sessions --------------------------------------------------------

    @Test
    fun listSessionsPrefersUpdatedAtAndFallsBackToTime() = runBlocking {
        fake.route(
            "$base/session",
            jsonResponse(
                200,
                """
                [
                  { "id": "ses_1", "title": "Bug hunt", "updatedAt": "2026-09-01T10:00:00Z", "parentID": null },
                  { "id": "ses_2", "title": "Refactor", "time": { "created": "2026-08-01T00:00:00Z", "updated": "2026-08-02T00:00:00Z" } }
                ]
                """.trimIndent(),
            ),
        )

        val result = client.listSessions()

        assertTrue(result is AiResult.Ok<*>)
        val sessions = result.okValue()
        assertEquals(2, sessions.size)
        assertEquals("ses_1", sessions[0].id)
        assertEquals("Bug hunt", sessions[0].title)
        assertEquals("2026-09-01T10:00:00Z", sessions[0].updatedAt)
        assertEquals("2026-08-02T00:00:00Z", sessions[1].updatedAt)
    }

    @Test
    fun createSessionSendsTitleAndMapsResponse() = runBlocking {
        fake.route("$base/session") { request ->
            if (request.method == "POST") {
                jsonResponse(200, """{ "id": "ses_new", "title": "New chat", "time": { "updated": "2026-09-12T00:00:00Z" } }""")
            } else {
                jsonResponse(200, "[]")
            }
        }

        val result = client.createSession(title = "New chat")

        assertTrue(result is AiResult.Ok<*>)
        val session = result.okValue()
        assertEquals("ses_new", session.id)
        assertEquals("New chat", session.title)
        assertEquals("2026-09-12T00:00:00Z", session.updatedAt)
        val request = fake.requests.single()
        assertEquals("POST", request.method)
        assertEquals("""{"title":"New chat"}""", bodyOf(request))
    }

    @Test
    fun createSessionWithoutTitleSendsEmptyObject() = runBlocking {
        fake.route("$base/session", jsonResponse(200, """{ "id": "ses_x", "title": "" }"""))

        val result = client.createSession()

        assertTrue(result is AiResult.Ok<*>)
        assertEquals("{}", bodyOf(fake.requests.single()))
    }

    @Test
    fun deleteSessionIssuesDelete() = runBlocking {
        fake.route("$base/session/ses_1", jsonResponse(200, "ok"))

        val result = client.deleteSession("ses_1")

        assertTrue(result is AiResult.Ok<*>)
        val request = fake.requests.single()
        assertEquals("DELETE", request.method)
        assertEquals("$base/session/ses_1", request.url)
    }

    // ---- messages --------------------------------------------------------

    @Test
    fun messagesMapsInfoAndPartsLeniently() = runBlocking {
        fake.route(
            "$base/session/ses_1/message?limit=100",
            jsonResponse(
                200,
                """
                [
                  {
                    "info": { "id": "msg_1", "role": "user", "modelID": "claude-sonnet-4", "cost": 0 },
                    "parts": [ { "id": "p1", "type": "text", "text": "Fix the build" } ]
                  },
                  {
                    "info": { "id": "msg_2", "role": "assistant" },
                    "parts": [
                      { "id": "p2", "type": "step-start", "time": { "start": 1 } },
                      { "id": "p3", "type": "tool", "tool": "read", "state": "completed", "input": { "file": "x" } },
                      { "id": "p4", "type": "tool", "tool": { "name": "write" }, "state": "pending" },
                      { "id": "p5", "type": "text", "text": "Done" }
                    ]
                  }
                ]
                """.trimIndent(),
            ),
        )

        val result = client.messages("ses_1")

        assertTrue(result is AiResult.Ok<*>)
        val messages = result.okValue()
        assertEquals(2, messages.size)
        assertEquals("msg_1", messages[0].id)
        assertEquals("user", messages[0].role)
        assertEquals("Fix the build", messages[0].parts.single().text)
        val parts = messages[1].parts
        assertEquals(4, parts.size)
        assertEquals("step-start", parts[0].type)
        assertNull(parts[0].text)
        assertEquals("read", parts[1].toolName)
        assertEquals("completed", parts[1].state)
        // tool as an object with a name is accepted too
        assertEquals("write", parts[2].toolName)
        assertEquals("Done", parts[3].text)
    }

    @Test
    fun messagesSendsTheLimitAsQuery() = runBlocking {
        fake.route("$base/session/ses_1/message?limit=5", jsonResponse(200, "[]"))

        val result = client.messages("ses_1", limit = 5)

        assertTrue(result is AiResult.Ok<*>)
        assertEquals("$base/session/ses_1/message?limit=5", fake.requests.single().url)
    }

    @Test
    fun sendMessagePostsATextPart() = runBlocking {
        fake.route("$base/session/ses_1/message", jsonResponse(200, """{ "info": {}, "parts": [] }"""))

        val result = client.sendMessage("ses_1", "Hello there")

        assertTrue(result is AiResult.Ok<*>)
        val request = fake.requests.single()
        assertEquals("POST", request.method)
        assertEquals(
            """{"parts":[{"type":"text","text":"Hello there"}]}""",
            bodyOf(request),
        )
    }

    @Test
    fun sendMessageIncludesModelAndAgentWhenPresent() = runBlocking {
        fake.route("$base/session/ses_1/message", jsonResponse(200, """{ "info": {}, "parts": [] }"""))

        val result = client.sendMessage("ses_1", "Go", model = "claude-sonnet-4", agent = "build")

        assertTrue(result is AiResult.Ok<*>)
        assertEquals(
            """{"parts":[{"type":"text","text":"Go"}],"model":"claude-sonnet-4","agent":"build"}""",
            bodyOf(fake.requests.single()),
        )
    }

    @Test
    fun abortPostsToAbort() = runBlocking {
        fake.route("$base/session/ses_1/abort", jsonResponse(200, "{}"))

        val result = client.abort("ses_1")

        assertTrue(result is AiResult.Ok<*>)
        val request = fake.requests.single()
        assertEquals("POST", request.method)
        assertEquals("$base/session/ses_1/abort", request.url)
    }

    // ---- diffs & permissions ---------------------------------------------

    @Test
    fun sessionDiffMapsFiles() = runBlocking {
        fake.route(
            "$base/session/ses_1/diff",
            jsonResponse(
                200,
                """
                [
                  { "path": "app/src/Main.kt", "before": "old", "after": "new", "additions": 1, "deletions": 1 },
                  { "file": "other.txt", "after": "created" }
                ]
                """.trimIndent(),
            ),
        )

        val result = client.sessionDiff("ses_1")

        assertTrue(result is AiResult.Ok<*>)
        val diffs = result.okValue()
        assertEquals(2, diffs.size)
        assertEquals("app/src/Main.kt", diffs[0].path)
        assertEquals("old", diffs[0].before)
        assertEquals("new", diffs[0].after)
        // Alternative path spelling, absent before.
        assertEquals("other.txt", diffs[1].path)
        assertNull(diffs[1].before)
        assertEquals("created", diffs[1].after)
    }

    @Test
    fun sessionDiffSendsMessageIdQuery() = runBlocking {
        fake.route("$base/session/ses_1/diff?messageID=msg_2", jsonResponse(200, "[]"))

        val result = client.sessionDiff("ses_1", messageId = "msg_2")

        assertTrue(result is AiResult.Ok<*>)
        assertEquals("$base/session/ses_1/diff?messageID=msg_2", fake.requests.single().url)
    }

    @Test
    fun respondToPermissionOnce() = runBlocking {
        fake.route("$base/session/ses_1/permissions/perm_1", jsonResponse(200, "{}"))

        val result = client.respondToPermission("ses_1", "perm_1", allow = true)

        assertTrue(result is AiResult.Ok<*>)
        val request = fake.requests.single()
        assertEquals("POST", request.method)
        assertEquals("""{"response":"once"}""", bodyOf(request))
    }

    @Test
    fun respondToPermissionRejectWithRemember() = runBlocking {
        fake.route("$base/session/ses_1/permissions/perm_2", jsonResponse(200, "{}"))

        val result = client.respondToPermission("ses_1", "perm_2", allow = false, remember = true)

        assertTrue(result is AiResult.Ok<*>)
        assertEquals(
            """{"response":"reject","remember":true}""",
            bodyOf(fake.requests.single()),
        )
    }

    // ---- error mapping ---------------------------------------------------

    @Test
    fun httpErrorCarriesTruncatedDetail() = runBlocking {
        fake.route("$base/session", jsonResponse(422, """{"message":"invalid session"}"""))

        val result = client.listSessions()

        assertTrue(result is AiResult.Error)
        val error = result as AiResult.Error
        assertEquals(422, error.statusCode)
        assertTrue(error.message.contains("invalid session"))
    }

    @Test
    fun unparseableBodyIsANullStatusError() = runBlocking {
        fake.route("$base/session", textResponse(200, "not json at all"))

        val result = client.listSessions()

        assertTrue(result is AiResult.Error)
        val error = result as AiResult.Error
        assertNull(error.statusCode)
        assertTrue(error.message.contains("parse"))
    }

    // ---- permission event payload ----------------------------------------

    @Test
    fun parsePermissionRequestMapsFieldSpellings() {
        val request = parsePermissionRequest(
            """
            { "type": "permission", "sessionID": "ses_1", "permissionID": "perm_1",
              "pattern": "bash:git *", "title": "Run command", "description": "git status" }
            """.trimIndent(),
        )

        assertEquals(
            AiPermissionRequest(
                sessionId = "ses_1",
                id = "perm_1",
                pattern = "bash:git *",
                title = "Run command",
                description = "git status",
            ),
            request,
        )
    }

    @Test
    fun parsePermissionRequestToleratesGarbage() {
        assertNull(parsePermissionRequest("not json"))
        assertNull(parsePermissionRequest("[1,2,3]"))
    }
}
