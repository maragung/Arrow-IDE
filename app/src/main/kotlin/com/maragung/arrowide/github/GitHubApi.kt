package com.maragung.arrowide.github

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import java.io.IOException
import java.net.URLEncoder

/**
 * Low-level result of one GitHub REST call: errors are values, never
 * exceptions (same philosophy as [com.maragung.arrowide.git.GitOutcome]).
 * [HttpError] carries the raw body for the facade to sanitize — the body
 * itself never reaches the UI unfiltered.
 */
internal sealed interface GitHubApiResult<out T> {

    data class Ok<T>(val value: T) : GitHubApiResult<T>

    data class HttpError(val statusCode: Int, val bodyText: String) : GitHubApiResult<Nothing>

    data class NetworkError(val message: String) : GitHubApiResult<Nothing>
}

/**
 * Extracts the `page` number of the `rel="next"` link from a GitHub
 * `Link` response header, or null when there is no next page (last page,
 * absent or malformed header). Pure function; tested directly.
 *
 * Example header:
 *
 *     <https://api.github.com/user/repos?page=3>; rel="next",
 *     <https://api.github.com/user/repos?page=7>; rel="last"
 */
internal fun parseNextPage(linkHeader: String?): Int? {
    if (linkHeader.isNullOrBlank()) return null
    for (section in linkHeader.split(',')) {
        val parts = section.split(';')
        val urlPart = parts.firstOrNull()?.trim().orEmpty()
        val isNext = parts.drop(1).any { param ->
            param.trim().let {
                it.equals("rel=\"next\"", ignoreCase = true) ||
                    it.removePrefix("rel=").trim(' ', '"').equals("next", ignoreCase = true)
            }
        }
        if (!isNext) continue
        val url = urlPart.removeSurrounding("<", ">")
        val query = url.substringAfter('?', "")
        for (pair in query.split('&')) {
            val key = pair.substringBefore('=')
            if (key == "page") return pair.substringAfter('=').toIntOrNull()
        }
    }
    return null
}

/**
 * Low-level GitHub REST v3 wrapper over an [HttpTransport]. Pure JVM —
 * every call goes through [HttpTransport.exchangeSuspending], so unit
 * tests drive a fake transport. Failures (network, HTTP status, parse)
 * are [GitHubApiResult] values; nothing throws except cancellation.
 *
 * Auth: the `Authorization: Bearer <token>` header is attached ONLY when
 * the token is non-null AND the request host equals the [baseUrl] host
 * (cross-host redirects are additionally stripped by the transport, so a
 * 302 to a signed blob URL never carries the PAT, plan #12).
 *
 * @param transport HTTP seam (real: [HttpUrlConnectionTransport]; tests: fake)
 * @param baseUrl   API root; overridable for tests
 */
internal class GitHubApi(
    private val transport: HttpTransport,
    private val baseUrl: String = "https://api.github.com",
) {

    // -------------------------------------------------------------------
    // Users / repositories
    // -------------------------------------------------------------------

    suspend fun currentUser(token: String?): GitHubApiResult<GithubUser> =
        getDecoded(token, "/user") { githubJson.decodeFromString<UserDto>(it).toModel() }

    suspend fun listRepositories(
        token: String?,
        page: Int = 1,
        type: String = "owner",
    ): GitHubApiResult<List<GithubRepo>> =
        getDecoded(
            token,
            "/user/repos",
            mapOf(
                "per_page" to "50",
                "page" to page.toString(),
                "type" to type,
                "sort" to "updated",
            ),
        ) { githubJson.decodeFromString<List<RepoDto>>(it).map { dto -> dto.toModel() } }

    suspend fun getRepository(
        token: String?,
        owner: String,
        repo: String,
    ): GitHubApiResult<GithubRepo> =
        getDecoded(token, repoPath(owner, repo)) {
            githubJson.decodeFromString<RepoDto>(it).toModel()
        }

    suspend fun listBranches(
        token: String?,
        owner: String,
        repo: String,
    ): GitHubApiResult<List<String>> =
        getDecoded(token, "${repoPath(owner, repo)}/branches", mapOf("per_page" to "100")) {
            githubJson.decodeFromString<List<BranchDto>>(it).map { branch -> branch.name }
        }

    suspend fun listCommits(
        token: String?,
        owner: String,
        repo: String,
        page: Int = 1,
    ): GitHubApiResult<List<GithubCommit>> =
        getDecoded(
            token,
            "${repoPath(owner, repo)}/commits",
            mapOf("per_page" to "30", "page" to page.toString()),
        ) { githubJson.decodeFromString<List<CommitDto>>(it).map { dto -> dto.toModel() } }

    suspend fun listPullRequests(
        token: String?,
        owner: String,
        repo: String,
    ): GitHubApiResult<List<GithubPullRequest>> =
        getDecoded(
            token,
            "${repoPath(owner, repo)}/pulls",
            mapOf("state" to "all", "per_page" to "30"),
        ) { githubJson.decodeFromString<List<PullRequestDto>>(it).map { dto -> dto.toModel() } }

    /**
     * Issues without pull requests: the issues endpoint also returns PRs,
     * so entries whose JSON carries a "pull_request" object are dropped
     * before decoding.
     */
    suspend fun listIssues(
        token: String?,
        owner: String,
        repo: String,
    ): GitHubApiResult<List<GithubIssue>> =
        getDecoded(
            token,
            "${repoPath(owner, repo)}/issues",
            mapOf("state" to "all", "per_page" to "30"),
        ) { body ->
            val entries = githubJson.parseToJsonElement(body) as? JsonArray
                ?: throw IllegalArgumentException("Expected a JSON array")
            entries
                .filterIsInstance<JsonObject>()
                .filterNot { "pull_request" in it }
                .map { githubJson.decodeFromJsonElement(IssueDto.serializer(), it).toModel() }
        }

    suspend fun createRepository(
        token: String?,
        name: String,
        description: String?,
        isPrivate: Boolean,
    ): GitHubApiResult<GithubRepo> {
        val body = githubJson.encodeToString(
            CreateRepoRequestDto(name = name, description = description, isPrivate = isPrivate),
        ).toByteArray(Charsets.UTF_8)
        return decoded(token, "POST", "/user/repos", requestBody = body) {
            githubJson.decodeFromString<RepoDto>(it).toModel()
        }
    }

    suspend fun deleteRepository(
        token: String?,
        owner: String,
        repo: String,
    ): GitHubApiResult<Unit> =
        decoded(token, "DELETE", repoPath(owner, repo)) { Unit }

    // -------------------------------------------------------------------
    // Actions
    // -------------------------------------------------------------------

    suspend fun listWorkflowRuns(
        token: String?,
        owner: String,
        repo: String,
        page: Int = 1,
    ): GitHubApiResult<List<GithubWorkflowRun>> =
        getDecoded(
            token,
            "${repoPath(owner, repo)}/actions/runs",
            mapOf("per_page" to "30", "page" to page.toString()),
        ) {
            githubJson.decodeFromString<WorkflowRunsResponseDto>(it)
                .workflowRuns.map { dto -> dto.toModel() }
        }

    suspend fun listRunJobs(
        token: String?,
        owner: String,
        repo: String,
        runId: Long,
    ): GitHubApiResult<List<GithubJob>> =
        getDecoded(token, "${repoPath(owner, repo)}/actions/runs/$runId/jobs") {
            githubJson.decodeFromString<JobsResponseDto>(it).jobs.map { dto -> dto.toModel() }
        }

    /**
     * Downloads the plain-text log of one job. GitHub answers 302 to a
     * signed blob URL — the transport follows it and drops the
     * Authorization header for the cross-host hop. A 404 means GitHub has
     * already deleted the log; that is reported as Ok("Logs expired")
     * rather than an error, because the log viewer can show it as text.
     */
    suspend fun downloadJobLog(
        token: String?,
        owner: String,
        repo: String,
        jobId: Long,
    ): GitHubApiResult<String> {
        val result = perform(token, "GET", "${repoPath(owner, repo)}/actions/jobs/$jobId/logs")
        return when (result) {
            is GitHubApiResult.NetworkError -> result
            is GitHubApiResult.Ok -> when (result.value.statusCode) {
                404 -> GitHubApiResult.Ok(LOGS_EXPIRED_MESSAGE)
                in 200..299 -> GitHubApiResult.Ok(result.value.bodyText)
                else -> GitHubApiResult.HttpError(
                    result.value.statusCode,
                    result.value.bodyText,
                )
            }
            is GitHubApiResult.HttpError -> result
        }
    }

    /**
     * Cancels a run. 202 is the success status; 409 means the run already
     * finished, which is treated as Ok too (idempotent cancel — the goal
     * state "not running" holds).
     */
    suspend fun cancelRun(
        token: String?,
        owner: String,
        repo: String,
        runId: Long,
    ): GitHubApiResult<Unit> {
        val result = perform(
            token,
            "POST",
            "${repoPath(owner, repo)}/actions/runs/$runId/cancel",
        )
        return when (result) {
            is GitHubApiResult.NetworkError -> result
            is GitHubApiResult.Ok -> when (result.value.statusCode) {
                202, 409 -> GitHubApiResult.Ok(Unit)
                else -> GitHubApiResult.HttpError(
                    result.value.statusCode,
                    result.value.bodyText,
                )
            }
            is GitHubApiResult.HttpError -> result
        }
    }

    suspend fun rerunFailedJobs(
        token: String?,
        owner: String,
        repo: String,
        runId: Long,
    ): GitHubApiResult<Unit> =
        expectCreated(token, "POST", "${repoPath(owner, repo)}/actions/runs/$runId/rerun-failed-jobs")

    /**
     * Triggers a workflow (`workflow_dispatch`). [inputs] become the
     * `inputs` object of the request body.
     */
    suspend fun dispatchWorkflow(
        token: String?,
        owner: String,
        repo: String,
        workflowFileName: String,
        ref: String,
        inputs: Map<String, String> = emptyMap(),
    ): GitHubApiResult<Unit> {
        val body = githubJson.encodeToString(
            DispatchRequestDto(ref = ref, inputs = inputs),
        ).toByteArray(Charsets.UTF_8)
        return expectNoContent(
            token,
            "POST",
            "${repoPath(owner, repo)}/actions/workflows/${segment(workflowFileName)}/dispatches",
            body,
        )
    }

    /** Entries of `.github/workflows` (the repo's workflow files). */
    suspend fun listWorkflowFiles(
        token: String?,
        owner: String,
        repo: String,
    ): GitHubApiResult<List<GithubContentEntry>> =
        getDecoded(token, "${repoPath(owner, repo)}/contents/.github/workflows") {
            githubJson.decodeFromString<List<ContentEntryDto>>(it).map { dto -> dto.toModel() }
        }

    // -------------------------------------------------------------------
    // Plumbing
    // -------------------------------------------------------------------

    private fun repoPath(owner: String, repo: String): String =
        "/repos/${segment(owner)}/${segment(repo)}"

    /** URL-encodes one path segment (URLEncoder's '+' becomes %20). */
    private fun segment(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun buildUrl(path: String, query: Map<String, String>): String {
        val url = StringBuilder(baseUrl.trimEnd('/')).append(path)
        if (query.isNotEmpty()) {
            url.append('?')
            url.append(query.entries.joinToString("&") { "${it.key}=${it.value}" })
        }
        return url.toString()
    }

    /**
     * Caller headers for [url]: always Accept; Authorization only with a
     * token AND when the request host matches the API host (plan #12).
     */
    private fun headersFor(url: String, token: String?, hasBody: Boolean): Map<String, String> {
        val headers = linkedMapOf("Accept" to ACCEPT)
        if (hasBody) headers["Content-Type"] = "application/json"
        if (token != null && hostOf(url) == hostOf(baseUrl)) {
            headers["Authorization"] = "Bearer $token"
        }
        return headers
    }

    /** One wire exchange; network failures become [GitHubApiResult.NetworkError]. */
    private suspend fun perform(
        token: String?,
        method: String,
        path: String,
        query: Map<String, String> = emptyMap(),
        requestBody: ByteArray? = null,
    ): GitHubApiResult<HttpResponse> = try {
        val url = buildUrl(path, query)
        GitHubApiResult.Ok(
            transport.exchangeSuspending(
                HttpExchange(method, url, headersFor(url, token, requestBody != null), requestBody),
            ),
        )
    } catch (e: IOException) {
        GitHubApiResult.NetworkError(e.message ?: "Network error")
    }

    /** GET + decode for JSON array/object endpoints. */
    private suspend fun <T> getDecoded(
        token: String?,
        path: String,
        query: Map<String, String> = emptyMap(),
        decode: (String) -> T,
    ): GitHubApiResult<T> = decoded(token, "GET", path, query, null, decode)

    private suspend fun <T> decoded(
        token: String?,
        method: String,
        path: String,
        query: Map<String, String> = emptyMap(),
        requestBody: ByteArray? = null,
        decode: (String) -> T,
    ): GitHubApiResult<T> {
        val result = perform(token, method, path, query, requestBody)
        return when (result) {
            is GitHubApiResult.NetworkError -> result
            is GitHubApiResult.Ok ->
                if (result.value.statusCode in 200..299) {
                    // kotlinx SerializationException extends
                    // IllegalArgumentException — both are parse failures here.
                    try {
                        GitHubApiResult.Ok(decode(result.value.bodyText))
                    } catch (e: IllegalArgumentException) {
                        GitHubApiResult.NetworkError(UNPARSEABLE_MESSAGE)
                    }
                } else {
                    GitHubApiResult.HttpError(result.value.statusCode, result.value.bodyText)
                }
            is GitHubApiResult.HttpError -> result
        }
    }

    /** POST expecting 201 Created and no payload (rerun-failed-jobs). */
    private suspend fun expectCreated(
        token: String?,
        method: String,
        path: String,
    ): GitHubApiResult<Unit> = expectStatus(token, method, path, 201)

    /** POST expecting 204 No Content (dispatches). */
    private suspend fun expectNoContent(
        token: String?,
        method: String,
        path: String,
        requestBody: ByteArray,
    ): GitHubApiResult<Unit> = expectStatus(token, method, path, 204, requestBody)

    private suspend fun expectStatus(
        token: String?,
        method: String,
        path: String,
        successStatus: Int,
        requestBody: ByteArray? = null,
    ): GitHubApiResult<Unit> {
        val result = perform(token, method, path, requestBody = requestBody)
        return when (result) {
            is GitHubApiResult.NetworkError -> result
            is GitHubApiResult.Ok ->
                if (result.value.statusCode == successStatus) {
                    GitHubApiResult.Ok(Unit)
                } else {
                    GitHubApiResult.HttpError(result.value.statusCode, result.value.bodyText)
                }
            is GitHubApiResult.HttpError -> result
        }
    }

    companion object {
        private const val ACCEPT = "application/vnd.github+json"
        private const val LOGS_EXPIRED_MESSAGE = "Logs expired"
        private const val UNPARSEABLE_MESSAGE = "Could not parse GitHub response"
    }
}
