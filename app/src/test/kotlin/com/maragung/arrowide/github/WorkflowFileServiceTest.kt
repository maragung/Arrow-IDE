package com.maragung.arrowide.github

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Base64

/**
 * [WorkflowFileService] behavior (plan #17): the contents-endpoint calls
 * behind the workflow editor — directory listing filtered to files,
 * base64 fetch with GitHub's wrapped content, and the commit-save PUT
 * including its 409 conflict path. No network; scripted [FakeTransport].
 */
class WorkflowFileServiceTest {

    private val token = "faketoken_0123456789abcdefghijklmnopqrstuvwxyz"

    private lateinit var fake: FakeTransport
    private lateinit var service: WorkflowFileService

    @Before
    fun setUp() {
        fake = FakeTransport()
        service = WorkflowFileService(transport = fake, tokenProvider = { token })
    }

    private val listUrl = "https://api.github.com/repos/octocat/hello/contents/.github/workflows"
    private val fileUrl = "https://api.github.com/repos/octocat/hello/contents/.github/workflows/ci.yml"

    private val listJson =
        """
        [
          {
            "name": "ci.yml",
            "path": ".github/workflows/ci.yml",
            "sha": "abc123",
            "size": 412,
            "type": "file",
            "html_url": "https://github.com/octocat/hello/blob/main/.github/workflows/ci.yml"
          },
          {
            "name": "subdir",
            "path": ".github/workflows/subdir",
            "sha": "dirsha",
            "size": 0,
            "type": "dir"
          },
          {
            "name": "release.yml",
            "path": ".github/workflows/release.yml",
            "sha": "def456",
            "size": 733,
            "type": "file"
          }
        ]
        """.trimIndent()

    // ---- list -------------------------------------------------------------

    @Test
    fun listWorkflowFiles_filtersFilesAndMapsFields() = runBlocking {
        fake.route(listUrl, jsonResponse(200, listJson))

        val result = service.listWorkflowFiles(owner = "octocat", repo = "hello")

        assertTrue(result is GitHubResult.Ok)
        val files = (result as GitHubResult.Ok<List<WorkflowFile>>).value
        assertEquals(listOf("ci.yml", "release.yml"), files.map { it.name })
        val ci = files.first { it.name == "ci.yml" }
        assertEquals(".github/workflows/ci.yml", ci.path)
        assertEquals("abc123", ci.sha)
        assertEquals(412L, ci.size)
        assertEquals("https://github.com/octocat/hello/blob/main/.github/workflows/ci.yml", ci.htmlUrl)
        val release = files.first { it.name == "release.yml" }
        assertNull("html_url absent means null, not a fake value", release.htmlUrl)
        assertEquals("Bearer $token", fake.requests.single().requestHeaders["Authorization"])
    }

    @Test
    fun listWorkflowFiles_errorPropagates() = runBlocking {
        fake.route(listUrl, jsonResponse(403, "{\"message\":\"rate limit exceeded\"}"))

        val result = service.listWorkflowFiles(owner = "octocat", repo = "hello")

        val error = result as GitHubResult.Error
        assertEquals(403, error.statusCode)
        assertTrue(error.message.contains("Forbidden"))
        assertTrue(error.message.contains("rate limit exceeded"))
    }

    // ---- fetch ------------------------------------------------------------

    @Test
    fun fetchWorkflowContent_decodesBase64WithEmbeddedNewlines() = runBlocking {
        val yaml = "name: CI\non: [push]\njobs:\n  build:\n    runs-on: ubuntu-latest\n"
        // GitHub wraps the content field at 60 columns — the MIME decoder
        // must ignore those line breaks.
        val wrapped = Base64.getEncoder()
            .encodeToString(yaml.toByteArray(Charsets.UTF_8))
            .chunked(60)
            .joinToString("\n")
        val json =
            """
            {
              "name": "ci.yml",
              "path": ".github/workflows/ci.yml",
              "sha": "abc123",
              "size": 63,
              "type": "file",
              "encoding": "base64",
              "content": "${wrapped.replace("\n", "\\n")}"
            }
            """.trimIndent()
        fake.route(fileUrl, jsonResponse(200, json))

        val result = service.fetchWorkflowContent(owner = "octocat", repo = "hello", path = ".github/workflows/ci.yml")

        assertTrue(result is GitHubResult.Ok)
        val fetched = (result as GitHubResult.Ok<WorkflowContent>).value
        assertEquals(yaml, fetched.content)
        assertEquals("abc123", fetched.sha)
    }

    @Test
    fun fetchWorkflowContent_errorPropagates() = runBlocking {
        fake.route(fileUrl, jsonResponse(404, "{\"message\":\"Not Found\"}"))

        val result = service.fetchWorkflowContent(owner = "octocat", repo = "hello", path = ".github/workflows/ci.yml")

        val error = result as GitHubResult.Error
        assertEquals(404, error.statusCode)
        assertTrue(error.message.contains("Not found"))
    }

    // ---- save -------------------------------------------------------------

    @Test
    fun saveWorkflowContent_putBodyCarriesBase64ContentShaAndMessage() = runBlocking {
        val yaml = "name: CI\non: [push]\n"
        fake.route(fileUrl, jsonResponse(200, "{\"content\":{\"sha\":\"newsha\"},\"commit\":{\"sha\":\"cmt\"}}"))

        val result = service.saveWorkflowContent(
            owner = "octocat",
            repo = "hello",
            path = ".github/workflows/ci.yml",
            content = yaml,
            sha = "abc123",
            message = "Update CI workflow",
        )

        assertTrue(result is GitHubResult.Ok)
        val request = fake.requests.single()
        assertEquals("PUT", request.method)
        assertEquals("Bearer $token", request.requestHeaders["Authorization"])
        assertEquals("application/json", request.requestHeaders["Content-Type"])
        val body = String(request.requestBody!!, Charsets.UTF_8).let { githubJson.parseToJsonElement(it).jsonObject }
        assertEquals("Update CI workflow", body["message"]!!.jsonPrimitive.content)
        assertEquals("abc123", body["sha"]!!.jsonPrimitive.content)
        val encoded = body["content"]!!.jsonPrimitive.content
        assertEquals(
            yaml,
            String(Base64.getMimeDecoder().decode(encoded), Charsets.UTF_8),
        )
        assertFalse("branch is omitted when null", "branch" in body)
    }

    @Test
    fun saveWorkflowContent_conflictMentionsChangedOnGitHub() = runBlocking {
        fake.route(
            fileUrl,
            jsonResponse(409, "{\"message\":\"is at deadbeef but expected abc123\"}"),
        )

        val result = service.saveWorkflowContent(
            owner = "octocat",
            repo = "hello",
            path = ".github/workflows/ci.yml",
            content = "name: CI\n",
            sha = "abc123",
            message = "Update CI workflow",
        )

        val error = result as GitHubResult.Error
        assertEquals(409, error.statusCode)
        assertTrue("mentions the conflict", error.message.contains("Conflict"))
        assertTrue("carries the reload hint", error.message.contains("changed on GitHub"))
    }

    @Test
    fun saveWorkflowContent_withBranchIncludesItInBody() = runBlocking {
        fake.route(fileUrl, jsonResponse(200, "{\"content\":{\"sha\":\"newsha\"}}"))

        val result = service.saveWorkflowContent(
            owner = "octocat",
            repo = "hello",
            path = ".github/workflows/ci.yml",
            content = "name: CI\n",
            sha = "abc123",
            message = "Update CI workflow",
            branch = "feature/workflow-edit",
        )

        assertTrue(result is GitHubResult.Ok)
        val body = String(fake.requests.single().requestBody!!, Charsets.UTF_8)
            .let { githubJson.parseToJsonElement(it).jsonObject }
        assertEquals("feature/workflow-edit", (body["branch"] as JsonPrimitive).content)
    }

    // ---- token seam ---------------------------------------------------------

    @Test
    fun notConnected_failsWithoutAnyRequest() = runBlocking {
        val disconnected = WorkflowFileService(transport = fake, tokenProvider = { null })

        val result = disconnected.listWorkflowFiles(owner = "octocat", repo = "hello")

        val error = result as GitHubResult.Error
        assertNull(error.statusCode)
        assertEquals("Not connected — add a GitHub token first", error.message)
        assertTrue("no request was sent", fake.requests.isEmpty())
    }
}
