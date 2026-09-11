package com.maragung.arrowide.github

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * [GitHubApi] behavior against a scripted [FakeTransport]: request shape
 * (URL, query, headers, body), status handling, PR filtering, pagination
 * helper, and network/parse failures. No network.
 */
class GitHubApiTest {

    private val token = "faketoken_0123456789abcdefghijklmnopqrstuvwxyz"

    private lateinit var fake: FakeTransport
    private lateinit var api: GitHubApi

    @Before
    fun setUp() {
        fake = FakeTransport()
        api = GitHubApi(fake)
    }

    private fun bodyOf(request: HttpExchange): String =
        String(request.requestBody ?: ByteArray(0), Charsets.UTF_8)

    // ---- pagination helper ------------------------------------------------

    @Test
    fun parseNextPage_extractsNextFromLinkHeader() {
        val header =
            "<https://api.github.com/user/repos?page=3>; rel=\"next\", " +
                "<https://api.github.com/user/repos?page=7>; rel=\"last\", " +
                "<https://api.github.com/user/repos?page=1>; rel=\"first\""

        assertEquals(3, parseNextPage(header))
    }

    @Test
    fun parseNextPage_lastPageHasNoNextLink() {
        val header =
            "<https://api.github.com/user/repos?page=6>; rel=\"prev\", " +
                "<https://api.github.com/user/repos?page=7>; rel=\"first\""

        assertNull(parseNextPage(header))
    }

    @Test
    fun parseNextPage_nullOrBlankHeader() {
        assertNull(parseNextPage(null))
        assertNull(parseNextPage(""))
        assertNull(parseNextPage("   "))
    }

    @Test
    fun parseNextPage_toleratesUnquotedRel() {
        assertEquals(2, parseNextPage("<https://api.github.com/x?page=2>; rel=next"))
    }

    @Test
    fun parseNextPage_malformedUrlYieldsNull() {
        assertNull(parseNextPage("<not-a-url>; rel=\"next\""))
    }

    // ---- request shape ----------------------------------------------------

    @Test
    fun listRepositories_sendsAuthAcceptAndPagination() = runBlocking {
        fake.route("https://api.github.com/user/repos", jsonResponse(200, "[]"))

        val result = api.listRepositories(token, page = 2)

        assertTrue(result is GitHubApiResult.Ok)
        val request = fake.requests.single()
        assertEquals(
            "https://api.github.com/user/repos?per_page=50&page=2&type=owner&sort=updated",
            request.url,
        )
        assertEquals("GET", request.method)
        assertEquals("Bearer $token", request.requestHeaders["Authorization"])
        assertEquals("application/vnd.github+json", request.requestHeaders["Accept"])
        assertEquals(null, request.requestBody)
    }

    @Test
    fun listRepositories_noTokenMeansNoAuthorizationHeader() = runBlocking {
        fake.route("https://api.github.com/user/repos", jsonResponse(200, "[]"))

        api.listRepositories(null)

        assertTrue(!fake.requests.single().requestHeaders.containsKey("Authorization"))
    }

    @Test
    fun listRepositories_typeParameterIsForwarded() = runBlocking {
        fake.route("https://api.github.com/user/repos", jsonResponse(200, "[]"))

        api.listRepositories(token, type = "all")

        assertEquals(
            "https://api.github.com/user/repos?per_page=50&page=1&type=all&sort=updated",
            fake.requests.single().url,
        )
    }

    @Test
    fun listCommits_pagesAndParses() = runBlocking {
        fake.route(
            "https://api.github.com/repos/octocat/Hello-World/commits",
            jsonResponse(
                200,
                """
                [
                  {
                    "sha": "6dcb09b",
                    "commit": {
                      "message": "Fix all the bugs",
                      "author": {"name": "Mona", "email": "mona@github.com", "date": "2011-04-14T16:00:49Z"}
                    },
                    "author": {"login": "octocat"}
                  }
                ]
                """.trimIndent(),
            ),
        )

        val result = api.listCommits(token, "octocat", "Hello-World", page = 3)

        assertEquals(
            "https://api.github.com/repos/octocat/Hello-World/commits?per_page=30&page=3",
            fake.requests.single().url,
        )
        val commits = (result as GitHubApiResult.Ok<List<GithubCommit>>).value
        assertEquals(1, commits.size)
        assertEquals("Fix all the bugs", commits[0].message)
    }

    @Test
    fun listBranches_returnsNames() = runBlocking {
        fake.route(
            "https://api.github.com/repos/octocat/Hello-World/branches",
            jsonResponse(200, """[{"name":"main","commit":{"sha":"a"}},{"name":"develop"}]"""),
        )

        val result = api.listBranches(token, "octocat", "Hello-World")

        assertEquals(listOf("main", "develop"), (result as GitHubApiResult.Ok<List<String>>).value)
        assertEquals(
            "https://api.github.com/repos/octocat/Hello-World/branches?per_page=100",
            fake.requests.single().url,
        )
    }

    @Test
    fun listIssues_filtersPullRequestEntries() = runBlocking {
        fake.route(
            "https://api.github.com/repos/octocat/Hello-World/issues",
            jsonResponse(
                200,
                """
                [
                  {
                    "number": 1,
                    "title": "Crash on start",
                    "state": "open",
                    "updated_at": "2026-09-01T10:00:00Z",
                    "user": {"login": "octocat"}
                  },
                  {
                    "number": 2,
                    "title": "Add feature",
                    "state": "closed",
                    "updated_at": "2026-09-02T10:00:00Z",
                    "pull_request": {
                      "url": "https://api.github.com/repos/octocat/Hello-World/pulls/2"
                    }
                  },
                  {
                    "number": 3,
                    "title": "Docs typo",
                    "state": "open",
                    "updated_at": "2026-09-03T10:00:00Z"
                  }
                ]
                """.trimIndent(),
            ),
        )

        val result = api.listIssues(token, "octocat", "Hello-World")

        val issues = (result as GitHubApiResult.Ok<List<GithubIssue>>).value
        assertEquals(listOf(1, 3), issues.map { it.number })
        assertEquals("Crash on start", issues[0].title)
        assertEquals(
            "https://api.github.com/repos/octocat/Hello-World/issues?state=all&per_page=30",
            fake.requests.single().url,
        )
    }

    // ---- mutations ---------------------------------------------------------

    @Test
    fun createRepository_postsJsonBodyAndParsesRepo() = runBlocking {
        fake.route(
            "https://api.github.com/user/repos",
            jsonResponse(
                201,
                """
                {
                  "id": 1296269,
                  "name": "demo",
                  "owner": {"login": "octocat"},
                  "private": true,
                  "default_branch": "main",
                  "updated_at": "2026-09-10T09:00:00Z",
                  "html_url": "https://github.com/octocat/demo"
                }
                """.trimIndent(),
            ),
        )

        val result = api.createRepository(token, "demo", "An experiment", isPrivate = true)

        val request = fake.requests.single()
        assertEquals("POST", request.method)
        assertEquals(
            "{\"name\":\"demo\",\"description\":\"An experiment\",\"private\":true}",
            bodyOf(request),
        )
        assertEquals("application/json", request.requestHeaders["Content-Type"])
        val repo = (result as GitHubApiResult.Ok<GithubRepo>).value
        assertEquals("demo", repo.name)
        assertTrue(repo.isPrivate)
    }

    @Test
    fun deleteRepository_expectsNoContent() = runBlocking {
        fake.route("https://api.github.com/repos/octocat/demo", textResponse(204, ""))

        val result = api.deleteRepository(token, "octocat", "demo")

        assertTrue(result is GitHubApiResult.Ok)
        assertEquals("DELETE", fake.requests.single().method)
        assertEquals("Bearer $token", fake.requests.single().requestHeaders["Authorization"])
    }

    @Test
    fun deleteRepository_forbiddenIsHttpError() = runBlocking {
        fake.route(
            "https://api.github.com/repos/octocat/demo",
            jsonResponse(403, "{\"message\":\"Must have admin rights\"}"),
        )

        val result = api.deleteRepository(token, "octocat", "demo")

        val error = result as GitHubApiResult.HttpError
        assertEquals(403, error.statusCode)
        assertEquals("Must have admin rights", error.bodyText)
    }

    // ---- Actions -----------------------------------------------------------

    @Test
    fun listWorkflowRuns_parsesWrappedArray() = runBlocking {
        fake.route(
            "https://api.github.com/repos/octocat/Hello-World/actions/runs",
            jsonResponse(
                200,
                """
                {
                  "total_count": 1,
                  "workflow_runs": [
                    {
                      "id": 30433642,
                      "name": "Build",
                      "status": "in_progress",
                      "head_branch": "main",
                      "created_at": "2026-09-10T09:00:00Z",
                      "updated_at": "2026-09-10T09:00:00Z",
                      "html_url": "https://github.com/octocat/Hello-World/actions/runs/30433642"
                    }
                  ]
                }
                """.trimIndent(),
            ),
        )

        val result = api.listWorkflowRuns(token, "octocat", "Hello-World", page = 4)

        assertEquals(
            "https://api.github.com/repos/octocat/Hello-World/actions/runs?per_page=30&page=4",
            fake.requests.single().url,
        )
        val runs = (result as GitHubApiResult.Ok<List<GithubWorkflowRun>>).value
        assertEquals(1, runs.size)
        assertEquals(30433642L, runs[0].id)
        assertEquals("in_progress", runs[0].status)
    }

    @Test
    fun downloadJobLog_returnsText() = runBlocking {
        fake.route(
            "https://api.github.com/repos/octocat/Hello-World/actions/jobs/399444496/logs",
            textResponse(200, "2026-09-10 Run build\nok"),
        )

        val result = api.downloadJobLog(token, "octocat", "Hello-World", 399444496L)

        assertEquals("2026-09-10 Run build\nok", (result as GitHubApiResult.Ok<String>).value)
    }

    @Test
    fun downloadJobLog_expiredLogsAreNotAnError() = runBlocking {
        fake.route(
            "https://api.github.com/repos/octocat/Hello-World/actions/jobs/399444496/logs",
            textResponse(404, ""),
        )

        val result = api.downloadJobLog(token, "octocat", "Hello-World", 399444496L)

        assertEquals("Logs expired", (result as GitHubApiResult.Ok<String>).value)
    }

    @Test
    fun cancelRun_accepts202() = runBlocking {
        fake.route(
            "https://api.github.com/repos/octocat/Hello-World/actions/runs/30433642/cancel",
            textResponse(202, ""),
        )

        val result = api.cancelRun(token, "octocat", "Hello-World", 30433642L)

        assertTrue(result is GitHubApiResult.Ok)
        assertEquals("POST", fake.requests.single().method)
    }

    @Test
    fun cancelRun_conflict409MeansAlreadyDone() = runBlocking {
        fake.route(
            "https://api.github.com/repos/octocat/Hello-World/actions/runs/30433642/cancel",
            textResponse(409, ""),
        )

        val result = api.cancelRun(token, "octocat", "Hello-World", 30433642L)

        assertTrue("409 is idempotent success", result is GitHubApiResult.Ok)
    }

    @Test
    fun cancelRun_otherStatusesAreHttpErrors() = runBlocking {
        fake.route(
            "https://api.github.com/repos/octocat/Hello-World/actions/runs/30433642/cancel",
            jsonResponse(403, "{\"message\":\"Cannot cancel a completed run\"}"),
        )

        val result = api.cancelRun(token, "octocat", "Hello-World", 30433642L)

        assertEquals(403, (result as GitHubApiResult.HttpError).statusCode)
    }

    @Test
    fun rerunFailedJobs_expects201() = runBlocking {
        fake.route(
            "https://api.github.com/repos/octocat/Hello-World/actions/runs/30433642/rerun-failed-jobs",
            textResponse(201, ""),
        )

        val result = api.rerunFailedJobs(token, "octocat", "Hello-World", 30433642L)

        assertTrue(result is GitHubApiResult.Ok)
    }

    @Test
    fun rerunFailedJobs_rejectsOtherStatuses() = runBlocking {
        fake.route(
            "https://api.github.com/repos/octocat/Hello-World/actions/runs/30433642/rerun-failed-jobs",
            textResponse(403, ""),
        )

        val result = api.rerunFailedJobs(token, "octocat", "Hello-World", 30433642L)

        assertTrue(result is GitHubApiResult.HttpError)
    }

    @Test
    fun dispatchWorkflow_postsRefAndInputs() = runBlocking {
        fake.route(
            "https://api.github.com/repos/octocat/Hello-World/actions/workflows/deploy.yml/dispatches",
            textResponse(204, ""),
        )

        val result = api.dispatchWorkflow(
            token,
            "octocat",
            "Hello-World",
            "deploy.yml",
            "main",
            mapOf("environment" to "staging"),
        )

        assertTrue(result is GitHubApiResult.Ok)
        val request = fake.requests.single()
        assertEquals("POST", request.method)
        assertEquals(
            "https://api.github.com/repos/octocat/Hello-World/actions/workflows/deploy.yml/dispatches",
            request.url,
        )
        assertEquals(
            "{\"ref\":\"main\",\"inputs\":{\"environment\":\"staging\"}}",
            bodyOf(request),
        )
        assertEquals("application/json", request.requestHeaders["Content-Type"])
    }

    @Test
    fun dispatchWorkflow_omitsEmptyInputs() = runBlocking {
        fake.route(
            "https://api.github.com/repos/octocat/Hello-World/actions/workflows/deploy.yml/dispatches",
            textResponse(204, ""),
        )

        api.dispatchWorkflow(token, "octocat", "Hello-World", "deploy.yml", "develop")

        assertEquals(
            "{\"ref\":\"develop\"}",
            bodyOf(fake.requests.single()),
        )
    }

    @Test
    fun dispatchWorkflow_rejectsNon204() = runBlocking {
        fake.route(
            "https://api.github.com/repos/octocat/Hello-World/actions/workflows/deploy.yml/dispatches",
            jsonResponse(422, "{\"message\":\"Workflow does not have workflow_dispatch trigger\"}"),
        )

        val result = api.dispatchWorkflow(token, "octocat", "Hello-World", "deploy.yml", "main")

        assertEquals(422, (result as GitHubApiResult.HttpError).statusCode)
    }

    @Test
    fun listWorkflowFiles_listsWorkflowDirectory() = runBlocking {
        fake.route(
            "https://api.github.com/repos/octocat/Hello-World/contents/.github/workflows",
            jsonResponse(
                200,
                """
                [
                  {"name":"android.yml","path":".github/workflows/android.yml","type":"file",
                   "download_url":"https://raw.githubusercontent.com/o/r/main/.github/workflows/android.yml"}
                ]
                """.trimIndent(),
            ),
        )

        val result = api.listWorkflowFiles(token, "octocat", "Hello-World")

        val files = (result as GitHubApiResult.Ok).value
        assertEquals(1, files.size)
        assertEquals("android.yml", files[0].name)
        assertEquals("file", files[0].type)
    }

    // ---- failures ----------------------------------------------------------

    @Test
    fun networkFailure_becomesNetworkError() = runBlocking {
        fake.fail(
            "https://api.github.com/user",
            IOException("Connection reset"),
        )

        val result = api.currentUser(token)

        val error = result as GitHubApiResult.NetworkError
        assertEquals("Connection reset", error.message)
    }

    @Test
    fun httpErrorStatus_becomesHttpErrorWithBody() = runBlocking {
        fake.route(
            "https://api.github.com/user",
            jsonResponse(401, "{\"message\":\"Bad credentials\"}"),
        )

        val result = api.currentUser(token)

        val error = result as GitHubApiResult.HttpError
        assertEquals(401, error.statusCode)
        assertEquals("Bad credentials", error.bodyText)
    }

    @Test
    fun unparseableBody_becomesNetworkError() = runBlocking {
        fake.route("https://api.github.com/user", textResponse(200, "<html>not json</html>"))

        val result = api.currentUser(token)

        val error = result as GitHubApiResult.NetworkError
        assertEquals("Could not parse GitHub response", error.message)
    }
}
