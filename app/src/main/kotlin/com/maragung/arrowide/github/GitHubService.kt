package com.maragung.arrowide.github

import com.maragung.arrowide.git.CredentialsProvider
import com.maragung.arrowide.git.GitCredentials
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Result of any GitHub operation the UI triggers: errors are VALUES, never
 * exceptions, so screens can render them directly (same philosophy as
 * [com.maragung.arrowide.git.GitOutcome]). [Error.statusCode] is the HTTP
 * status when the API answered, or null for local failures (no network,
 * not connected, unparseable response).
 */
sealed interface GitHubResult<out T> {

    data class Ok<T>(val value: T) : GitHubResult<T>

    data class Error(val statusCode: Int?, val message: String) : GitHubResult<Nothing>
}

/**
 * Public facade over the GitHub REST API (plans #12-#16, #19-#20) and the
 * M3 git layer's credential seam.
 *
 * Token handling (plan #12): the PAT lives in [tokenStore], is read per
 * call (never cached in a field), is sent ONLY to the API host, and never
 * appears in any [GitHubResult.Error.message] — see [sanitizeMessage].
 * [connect] stores the token only after GitHub validated it.
 *
 * Construction performs no I/O. All API calls run on Dispatchers.IO via
 * [HttpTransport.exchangeSuspending].
 */
class GitHubService(
    private val tokenStore: TokenStore,
    private val transport: HttpTransport,
) {

    private val api = GitHubApi(transport)

    /** Whether a token is stored (not whether it is still valid). */
    fun hasToken(): Boolean = tokenStore.get() != null

    /**
     * Validates [token] via GET /user and stores it ONLY on success; a bad
     * token is neither stored nor echoed in the error message.
     */
    suspend fun connect(token: String): GitHubResult<GithubUser> {
        val result = api.currentUser(token).toResult(token)
        if (result is GitHubResult.Ok) {
            tokenStore.set(token)
        }
        return result
    }

    /** Clears the stored token (and its Keystore key); always succeeds locally. */
    suspend fun disconnect(): GitHubResult<Unit> {
        tokenStore.set(null)
        return GitHubResult.Ok(Unit)
    }

    suspend fun currentUser(): GitHubResult<GithubUser> = withToken { token ->
        api.currentUser(token).toResult(token)
    }

    suspend fun listRepositories(page: Int = 1): GitHubResult<List<GithubRepo>> = withToken { token ->
        api.listRepositories(token, page).toResult(token)
    }

    suspend fun listBranches(owner: String, repo: String): GitHubResult<List<String>> = withToken { token ->
        api.listBranches(token, owner, repo).toResult(token)
    }

    suspend fun listCommits(
        owner: String,
        repo: String,
        page: Int = 1,
    ): GitHubResult<List<GithubCommit>> = withToken { token ->
        api.listCommits(token, owner, repo, page).toResult(token)
    }

    suspend fun listPullRequests(owner: String, repo: String): GitHubResult<List<GithubPullRequest>> =
        withToken { token ->
            api.listPullRequests(token, owner, repo).toResult(token)
        }

    suspend fun listIssues(owner: String, repo: String): GitHubResult<List<GithubIssue>> = withToken { token ->
        api.listIssues(token, owner, repo).toResult(token)
    }

    suspend fun createRepository(
        name: String,
        description: String?,
        isPrivate: Boolean,
    ): GitHubResult<GithubRepo> = withToken { token ->
        api.createRepository(token, name, description, isPrivate).toResult(token)
    }

    suspend fun deleteRepository(owner: String, repo: String): GitHubResult<Unit> = withToken { token ->
        api.deleteRepository(token, owner, repo).toResult(token)
    }

    suspend fun listWorkflowRuns(
        owner: String,
        repo: String,
        page: Int = 1,
    ): GitHubResult<List<GithubWorkflowRun>> = withToken { token ->
        api.listWorkflowRuns(token, owner, repo, page).toResult(token)
    }

    suspend fun listRunJobs(
        owner: String,
        repo: String,
        runId: Long,
    ): GitHubResult<List<GithubJob>> = withToken { token ->
        api.listRunJobs(token, owner, repo, runId).toResult(token)
    }

    suspend fun downloadJobLog(owner: String, repo: String, jobId: Long): GitHubResult<String> =
        withToken { token ->
            api.downloadJobLog(token, owner, repo, jobId).toResult(token)
        }

    suspend fun cancelRun(owner: String, repo: String, runId: Long): GitHubResult<Unit> = withToken { token ->
        api.cancelRun(token, owner, repo, runId).toResult(token)
    }

    suspend fun rerunFailedJobs(owner: String, repo: String, runId: Long): GitHubResult<Unit> =
        withToken { token ->
            api.rerunFailedJobs(token, owner, repo, runId).toResult(token)
        }

    suspend fun dispatchWorkflow(
        owner: String,
        repo: String,
        workflowFileName: String,
        ref: String,
        inputs: Map<String, String> = emptyMap(),
    ): GitHubResult<Unit> = withToken { token ->
        api.dispatchWorkflow(token, owner, repo, workflowFileName, ref, inputs).toResult(token)
    }

    suspend fun listWorkflowFiles(owner: String, repo: String): GitHubResult<List<GithubContentEntry>> =
        withToken { token ->
            api.listWorkflowFiles(token, owner, repo).toResult(token)
        }

    /**
     * Credentials for git push/pull/clone over HTTPS (M3's askpass
     * mechanism). LATE binding: the provider reads the token store at
     * call time, so a connect/disconnect between git operations needs no
     * provider rebuild. Username is GitHub's conventional machine user
     * `x-access-token`; null credentials when no token is stored, which
     * makes git proceed unauthenticated (public repos still work).
     */
    fun gitCredentials(): CredentialsProvider = object : CredentialsProvider {
        override fun credentialsForUrl(url: String): GitCredentials? {
            val token = tokenStore.get() ?: return null
            return GitCredentials(username = GIT_USERNAME, password = token)
        }
    }

    // -------------------------------------------------------------------
    // Plumbing
    // -------------------------------------------------------------------

    private suspend fun <T> withToken(block: suspend (String) -> GitHubResult<T>): GitHubResult<T> {
        val token = tokenStore.get()
            ?: return GitHubResult.Error(statusCode = null, message = NOT_CONNECTED_MESSAGE)
        return block(token)
    }

    private fun <T> GitHubApiResult<T>.toResult(token: String?): GitHubResult<T> = when (this) {
        is GitHubApiResult.Ok -> GitHubResult.Ok(value)
        is GitHubApiResult.HttpError ->
            GitHubResult.Error(statusCode, httpErrorMessage(statusCode, bodyText, token))
        is GitHubApiResult.NetworkError -> GitHubResult.Error(null, message)
    }

    /**
     * Human message for an HTTP failure: a fixed base per status plus the
     * body's detail (sanitized against the token — a body that ever echoes
     * the PAT is redacted before it can reach the UI, plan #12).
     */
    private fun httpErrorMessage(statusCode: Int, bodyText: String, token: String?): String {
        val base = when (statusCode) {
            401 -> "Unauthorized — check your token"
            403 -> "Forbidden — the token lacks a required permission or the rate limit is exhausted"
            404 -> "Not found — check the name and the token's access"
            else -> "GitHub request failed (HTTP $statusCode)"
        }
        val detail = errorDetail(bodyText)
            ?.let { sanitizeMessage(it, token) }
            ?.take(MAX_DETAIL_LENGTH)
            ?.trim()
        return if (detail.isNullOrEmpty()) base else "$base: $detail"
    }

    /**
     * Detail line of an error body: GitHub error bodies are
     * `{"message": "..."}` — the message field when present, otherwise the
     * first non-blank line of the raw body.
     */
    private fun errorDetail(bodyText: String): String? {
        val messageField = try {
            githubJson.parseToJsonElement(bodyText)
                as? JsonObject
        } catch (e: IllegalArgumentException) {
            null // not JSON — fall through to the raw line below
        }?.get("message") as? JsonPrimitive
        if (messageField != null) return messageField.content
        return bodyText.lineSequence().firstOrNull { it.isNotBlank() }
    }

    private companion object {
        const val GIT_USERNAME = "x-access-token"
        const val NOT_CONNECTED_MESSAGE = "Not connected — add a GitHub token first"
        const val MAX_DETAIL_LENGTH = 200
    }
}

/** Real PATs are long; only redact against plausible token values. */
private const val MIN_REDACT_LENGTH = 8

/**
 * Replaces every occurrence of [token] in [message] with "[redacted]".
 * Guarded by a minimum length so short values cannot mangle ordinary
 * messages. By construction no error message ever contains the token.
 */
internal fun sanitizeMessage(message: String, token: String?): String =
    if (token != null && token.length >= MIN_REDACT_LENGTH) {
        message.replace(token, "[redacted]")
    } else {
        message
    }
