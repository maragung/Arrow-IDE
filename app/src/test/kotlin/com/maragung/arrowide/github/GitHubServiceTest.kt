package com.maragung.arrowide.github

import com.maragung.arrowide.git.GitCredentials
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * [GitHubService] facade behavior: token lifecycle (connect stores only
 * after validation), error mapping (never leaking the token), idempotent
 * cancel, and the late-bound git credentials for M3's askpass. No network.
 */
class GitHubServiceTest {

    private val token = "faketoken_0123456789abcdefghijklmnopqrstuvwxyz"

    private lateinit var store: FakeTokenStore
    private lateinit var fake: FakeTransport
    private lateinit var service: GitHubService

    @Before
    fun setUp() {
        store = FakeTokenStore()
        fake = FakeTransport()
        service = GitHubService(store, fake)
    }

    private val userJson =
        """
        {
          "login": "octocat",
          "name": "The Octocat",
          "avatar_url": "https://github.com/images/error/octocat_happy.gif"
        }
        """.trimIndent()

    // ---- token lifecycle ---------------------------------------------------

    @Test
    fun hasToken_reflectsStore() {
        assertFalse(service.hasToken())
        store.set(token)
        assertTrue(service.hasToken())
        store.set(null)
        assertFalse(service.hasToken())
    }

    @Test
    fun connect_storesTokenAfterSuccessfulValidation() = runBlocking {
        fake.route("https://api.github.com/user", jsonResponse(200, userJson))

        val result = service.connect(token)

        assertTrue(result is GitHubResult.Ok)
        assertEquals("octocat", (result as GitHubResult.Ok<GithubUser>).value.login)
        assertEquals(listOf(token), store.setCalls)
        assertEquals("Bearer $token", fake.requests.single().requestHeaders["Authorization"])
    }

    @Test
    fun connect_rejectsBadTokenWithoutStoringIt() = runBlocking {
        fake.route(
            "https://api.github.com/user",
            jsonResponse(401, "{\"message\":\"Bad credentials\"}"),
        )

        val result = service.connect(token)

        val error = result as GitHubResult.Error
        assertEquals(401, error.statusCode)
        assertEquals("Unauthorized — check your token: Bad credentials", error.message)
        assertTrue("nothing stored on failure", store.setCalls.isEmpty())
    }

    @Test
    fun connect_networkFailureDoesNotStore() = runBlocking {
        fake.fail("https://api.github.com/user", IOException("Connection reset"))

        val result = service.connect(token)

        val error = result as GitHubResult.Error
        assertNull(error.statusCode)
        assertEquals("Connection reset", error.message)
        assertTrue(store.setCalls.isEmpty())
    }

    @Test
    fun disconnect_clearsStoredToken() = runBlocking {
        store.set(token)

        val result = service.disconnect()

        assertTrue(result is GitHubResult.Ok)
        assertEquals(listOf<String?>(token, null), store.setCalls)
        assertFalse(service.hasToken())
    }

    @Test
    fun notConnected_failsWithoutAnyRequest() = runBlocking {
        val result = service.listRepositories()

        val error = result as GitHubResult.Error
        assertNull(error.statusCode)
        assertEquals("Not connected — add a GitHub token first", error.message)
        assertTrue("no request was sent", fake.requests.isEmpty())
    }

    // ---- error mapping (plan #12: the token never appears in messages) ----

    @Test
    fun error401_messageHasNoTokenEvenWhenBodyEchoesIt() = runBlocking {
        store.set(token)
        fake.route(
            "https://api.github.com/user",
            // Hostile/impossible body: GitHub echoing the PAT back.
            jsonResponse(401, "{\"message\":\"Bad credentials for $token\"}"),
        )

        val result = service.currentUser()

        val error = result as GitHubResult.Error
        assertEquals(401, error.statusCode)
        assertFalse("token must not leak", error.message.contains(token))
        assertTrue(
            "token is redacted: ${error.message}",
            error.message.contains("[redacted]"),
        )
    }

    @Test
    fun networkFailure_mapsToErrorWithNullStatus() = runBlocking {
        store.set(token)
        fake.fail("https://api.github.com/user/repos", IOException("ECONNRESET"))

        val result = service.listRepositories()

        val error = result as GitHubResult.Error
        assertNull(error.statusCode)
        assertEquals("ECONNRESET", error.message)
    }

    @Test
    fun httpError_includesBodyMessage() = runBlocking {
        store.set(token)
        fake.route(
            "https://api.github.com/repos/octocat/gone",
            jsonResponse(404, "{\"message\":\"Not Found\"}"),
        )

        val result = service.listBranches("octocat", "gone")

        val error = result as GitHubResult.Error
        assertEquals(404, error.statusCode)
        assertEquals("Not found — check the name and the token's access: Not Found", error.message)
    }

    // ---- repository operations ---------------------------------------------

    @Test
    fun currentUser_returnsStoredTokenUser() = runBlocking {
        store.set(token)
        fake.route("https://api.github.com/user", jsonResponse(200, userJson))

        val result = service.currentUser()

        assertEquals("octocat", (result as GitHubResult.Ok<GithubUser>).value.login)
    }

    @Test
    fun listRepositories_readsTokenFromStoreAtCallTime() = runBlocking {
        fake.route("https://api.github.com/user/repos", jsonResponse(200, "[]"))

        store.set("first-token")
        service.listRepositories()
        assertEquals("Bearer first-token", fake.requests[0].requestHeaders["Authorization"])

        store.set("second-token")
        service.listRepositories()
        assertEquals("Bearer second-token", fake.requests[1].requestHeaders["Authorization"])
    }

    @Test
    fun createRepository_returnsCreatedRepo() = runBlocking {
        store.set(token)
        fake.route(
            "https://api.github.com/user/repos",
            jsonResponse(
                201,
                """
                {
                  "id": 42,
                  "name": "demo",
                  "owner": {"login": "octocat"},
                  "private": false,
                  "default_branch": "main",
                  "updated_at": "2026-09-10T09:00:00Z",
                  "html_url": "https://github.com/octocat/demo"
                }
                """.trimIndent(),
            ),
        )

        val result = service.createRepository("demo", null, isPrivate = false)

        val repo = (result as GitHubResult.Ok<GithubRepo>).value
        assertEquals("demo", repo.name)
        assertEquals("octocat", repo.ownerLogin)
        assertEquals(
            "{\"name\":\"demo\",\"private\":false}",
            String(fake.requests.single().requestBody ?: ByteArray(0), Charsets.UTF_8),
        )
    }

    // ---- Actions -----------------------------------------------------------

    @Test
    fun cancelRun_conflict409IsOk() = runBlocking {
        store.set(token)
        fake.route(
            "https://api.github.com/repos/octocat/Hello-World/actions/runs/7/cancel",
            textResponse(409, ""),
        )

        val result = service.cancelRun("octocat", "Hello-World", 7L)

        assertTrue("409 (already finished) is idempotent success", result is GitHubResult.Ok)
    }

    @Test
    fun downloadJobLog_expiredLogsAreShownAsText() = runBlocking {
        store.set(token)
        fake.route(
            "https://api.github.com/repos/octocat/Hello-World/actions/jobs/9/logs",
            textResponse(404, ""),
        )

        val result = service.downloadJobLog("octocat", "Hello-World", 9L)

        assertEquals("Logs expired", (result as GitHubResult.Ok<String>).value)
    }

    @Test
    fun dispatchWorkflow_succeedsOn204() = runBlocking {
        store.set(token)
        fake.route(
            "https://api.github.com/repos/octocat/Hello-World/actions/workflows/deploy.yml/dispatches",
            textResponse(204, ""),
        )

        val result = service.dispatchWorkflow(
            "octocat",
            "Hello-World",
            "deploy.yml",
            "main",
            mapOf("environment" to "staging"),
        )

        assertTrue(result is GitHubResult.Ok)
        assertEquals(
            "{\"ref\":\"main\",\"inputs\":{\"environment\":\"staging\"}}",
            String(fake.requests.single().requestBody ?: ByteArray(0), Charsets.UTF_8),
        )
    }

    // ---- git credentials (M3 askpass bridge) --------------------------------

    @Test
    fun gitCredentials_readsTheStoreAtCallTime() {
        store.set("first-token")
        val provider = service.gitCredentials()

        assertEquals(
            GitCredentials("x-access-token", "first-token"),
            provider.credentialsForUrl("https://github.com/octocat/Hello-World.git"),
        )

        // Late binding: no provider rebuild needed after a reconnect.
        store.set("second-token")
        assertEquals(
            GitCredentials("x-access-token", "second-token"),
            provider.credentialsForUrl("https://github.com/octocat/Hello-World.git"),
        )

        store.set(null)
        assertNull(provider.credentialsForUrl("https://github.com/octocat/Hello-World.git"))
    }
}
