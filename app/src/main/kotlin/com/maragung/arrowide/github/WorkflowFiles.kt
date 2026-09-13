package com.maragung.arrowide.github

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import java.net.URLEncoder
import java.util.Base64

/**
 * One workflow file of `.github/workflows` (plan #17): everything the
 * workflow editor's file list needs — identity ([path]/[name]), the blob
 * [sha] for conflict detection on save, [size] for the list subtitle and
 * [htmlUrl] for an "open on GitHub" link (null when GitHub omits it).
 */
data class WorkflowFile(
    val path: String,
    val name: String,
    val sha: String,
    val size: Long,
    val htmlUrl: String?,
)

/**
 * Decoded content of one workflow file (plan #17): the YAML text as the
 * editor shows it, plus the blob [sha] the file was at when read — a save
 * must pass that sha back so GitHub rejects clobbering concurrent edits.
 */
data class WorkflowContent(
    val content: String,
    val sha: String,
)

/**
 * Workflow-file core (plan #17): lists `.github/workflows`, reads one
 * file's decoded YAML and saves it back as a commit — all through the
 * GitHub REST contents endpoint, all results as [GitHubResult] values
 * (errors are values, never exceptions, same philosophy as
 * [GitHubService]).
 *
 * [GitHubService] keeps its transport and token store private and offers
 * no generic request surface, so this service takes the same seams
 * directly: the [HttpTransport] and a late-bound [tokenProvider] that is
 * read per call (never cached), e.g.
 * `WorkflowFileService(transport) { tokenStore.get() }`. URL construction,
 * headers (Accept, `Authorization` only on the API host, Content-Type with
 * a body) and error mapping follow [GitHubApi]/[GitHubService] exactly.
 *
 * A missing token short-circuits like every [GitHubService] call: an
 * error, and no request leaves the app. A 404 from [listWorkflowFiles]
 * means the repository has no `.github/workflows` directory (or the token
 * lacks access) and is surfaced as an ordinary error — the UI decides
 * whether to read that as "no workflows yet".
 */
class WorkflowFileService(
    private val transport: HttpTransport,
    private val tokenProvider: () -> String?,
    private val baseUrl: String = "https://api.github.com",
) {

    /**
     * Entries of a repository's `.github/workflows` directory
     * (GET /repos/{owner}/{repo}/contents/.github/workflows), filtered to
     * `type == "file"` — subdirectories are dropped.
     */
    suspend fun listWorkflowFiles(owner: String, repo: String): GitHubResult<List<WorkflowFile>> =
        withToken { token ->
            perform(token, "GET", "${repoPath(owner, repo)}/contents/.github/workflows")
                .decodeBody { body ->
                    githubJson.decodeFromString<List<WorkflowEntryDto>>(body)
                        .filter { it.type == FILE_TYPE }
                        .map { dto ->
                            WorkflowFile(
                                path = dto.path,
                                name = dto.name,
                                sha = dto.sha,
                                size = dto.size,
                                htmlUrl = dto.htmlUrl,
                            )
                        }
                }
                .toResult(token)
        }

    /**
     * One workflow file's decoded YAML (GET contents/{path}). GitHub
     * returns the text base64-wrapped at 60 columns, so the content field
     * is decoded with the MIME decoder (which ignores the embedded line
     * breaks) before it is handed to the editor.
     */
    suspend fun fetchWorkflowContent(
        owner: String,
        repo: String,
        path: String,
    ): GitHubResult<WorkflowContent> = withToken { token ->
        when (
            val result = perform(token, "GET", "${repoPath(owner, repo)}/contents/${contentPath(path)}")
                .decodeBody { body -> githubJson.decodeFromString<WorkflowFileContentDto>(body) }
        ) {
            is GitHubApiResult.NetworkError -> GitHubResult.Error(null, result.message)
            is GitHubApiResult.HttpError ->
                GitHubResult.Error(result.statusCode, httpErrorMessage(result.statusCode, result.bodyText, token))
            is GitHubApiResult.Ok -> decodeContent(result.value, path)
        }
    }

    /**
     * Saves the edited YAML back as a commit (PUT contents/{path}): the
     * request body carries the commit [message], the base64-encoded
     * [content] and the blob [sha] the editor loaded, so GitHub answers
     * 409 when the file changed on GitHub in the meantime — that surfaces
     * as an error mentioning "changed on GitHub", and the editor can ask
     * the user to reload and reapply. [branch] is null for the default
     * branch. Returns [GitHubResult.Ok] of [Unit] on any 2xx.
     */
    suspend fun saveWorkflowContent(
        owner: String,
        repo: String,
        path: String,
        content: String,
        sha: String,
        message: String,
        branch: String? = null,
    ): GitHubResult<Unit> = withToken { token ->
        val body = githubJson.encodeToString(
            SaveWorkflowRequestDto(
                message = message,
                content = Base64.getEncoder().encodeToString(content.toByteArray(Charsets.UTF_8)),
                sha = sha,
                branch = branch,
            ),
        ).toByteArray(Charsets.UTF_8)
        when (val result = perform(token, "PUT", "${repoPath(owner, repo)}/contents/${contentPath(path)}", body)) {
            is GitHubApiResult.NetworkError -> GitHubResult.Error(null, result.message)
            is GitHubApiResult.HttpError ->
                GitHubResult.Error(result.statusCode, httpErrorMessage(result.statusCode, result.bodyText, token))
            is GitHubApiResult.Ok ->
                if (result.value.statusCode in 200..299) {
                    GitHubResult.Ok(Unit)
                } else {
                    GitHubResult.Error(
                        result.value.statusCode,
                        httpErrorMessage(result.value.statusCode, result.value.bodyText, token),
                    )
                }
        }
    }

    // -------------------------------------------------------------------
    // Plumbing (mirrors GitHubApi/GitHubService conventions exactly)
    // -------------------------------------------------------------------

    /** Runs [block] with the current token, or fails fast when none is stored. */
    private suspend fun <T> withToken(block: suspend (String) -> GitHubResult<T>): GitHubResult<T> {
        val token = tokenProvider()
            ?: return GitHubResult.Error(statusCode = null, message = NOT_CONNECTED_MESSAGE)
        return block(token)
    }

    private fun repoPath(owner: String, repo: String): String =
        "/repos/${segment(owner)}/${segment(repo)}"

    /** Encodes a repo-relative path (e.g. `.github/workflows/ci.yml`) segment by segment. */
    private fun contentPath(path: String): String =
        path.split('/').joinToString("/") { segment(it) }

    /** URL-encodes one path segment (URLEncoder's '+' becomes %20), as in [GitHubApi]. */
    private fun segment(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    /**
     * Caller headers for [url]: always Accept; Content-Type with a body;
     * `Authorization: Bearer` only when the request host matches the API
     * host (the token never travels cross-host, plan #12).
     */
    private fun headersFor(url: String, token: String, hasBody: Boolean): Map<String, String> {
        val headers = linkedMapOf("Accept" to ACCEPT)
        if (hasBody) headers["Content-Type"] = "application/json"
        if (hostOf(url) == hostOf(baseUrl)) {
            headers["Authorization"] = "Bearer $token"
        }
        return headers
    }

    /** One wire exchange; network failures become [GitHubApiResult.NetworkError]. */
    private suspend fun perform(
        token: String,
        method: String,
        path: String,
        requestBody: ByteArray? = null,
    ): GitHubApiResult<HttpResponse> = try {
        val url = baseUrl.trimEnd('/') + path
        GitHubApiResult.Ok(
            transport.exchangeSuspending(
                HttpExchange(method, url, headersFor(url, token, requestBody != null), requestBody),
            ),
        )
    } catch (e: IOException) {
        GitHubApiResult.NetworkError(e.message ?: "Network error")
    }

    /**
     * Maps a raw [HttpResponse] through [decode]: 2xx decodes, anything
     * else is an [GitHubApiResult.HttpError]; parse failures
     * (IllegalArgumentException, incl. SerializationException) become a
     * NetworkError with a fixed message, as in [GitHubApi.decoded].
     */
    private fun <T> GitHubApiResult<HttpResponse>.decodeBody(decode: (String) -> T): GitHubApiResult<T> =
        when (this) {
            is GitHubApiResult.NetworkError -> this
            is GitHubApiResult.Ok ->
                if (value.statusCode in 200..299) {
                    try {
                        GitHubApiResult.Ok(decode(value.bodyText))
                    } catch (e: IllegalArgumentException) {
                        GitHubApiResult.NetworkError(UNPARSEABLE_MESSAGE)
                    }
                } else {
                    GitHubApiResult.HttpError(value.statusCode, value.bodyText)
                }
            is GitHubApiResult.HttpError -> this
        }

    /** Decodes the base64 content field of a fetched workflow file. */
    private fun decodeContent(dto: WorkflowFileContentDto, path: String): GitHubResult<WorkflowContent> {
        if (dto.encoding != BASE64_ENCODING) {
            return GitHubResult.Error(
                statusCode = null,
                message = "Workflow file is not base64-encoded (encoding=${dto.encoding ?: "none"})",
            )
        }
        val raw = dto.content
            ?: return GitHubResult.Error(statusCode = null, message = "Workflow file $path has no content")
        return try {
            val bytes = Base64.getMimeDecoder().decode(raw)
            GitHubResult.Ok(WorkflowContent(content = String(bytes, Charsets.UTF_8), sha = dto.sha))
        } catch (e: IllegalArgumentException) {
            GitHubResult.Error(statusCode = null, message = "Could not decode the workflow file content")
        }
    }

    private fun <T> GitHubApiResult<T>.toResult(token: String): GitHubResult<T> = when (this) {
        is GitHubApiResult.Ok -> GitHubResult.Ok(value)
        is GitHubApiResult.HttpError ->
            GitHubResult.Error(statusCode, httpErrorMessage(statusCode, bodyText, token))
        is GitHubApiResult.NetworkError -> GitHubResult.Error(null, message)
    }

    /**
     * Human message for an HTTP failure: a fixed base per status plus the
     * body's detail, sanitized against the token (plan #12 — no error
     * message ever echoes the PAT). 409 is the save conflict of
     * [saveWorkflowContent] and spells out its remedy.
     */
    private fun httpErrorMessage(statusCode: Int, bodyText: String, token: String): String {
        val base = when (statusCode) {
            401 -> "Unauthorized — check your token"
            403 -> "Forbidden — the token lacks a required permission or the rate limit is exhausted"
            404 -> "Not found — check the name and the token's access"
            409 -> "Conflict — the workflow file changed on GitHub since it was loaded; reload it and try again"
            422 -> "Unprocessable — check the path, branch and file"
            else -> "GitHub request failed (HTTP $statusCode)"
        }
        val detail = errorDetail(bodyText)
            ?.let { sanitizeMessage(it, token) }
            ?.take(MAX_DETAIL_LENGTH)
            ?.trim()
        return if (detail.isNullOrEmpty()) base else "$base: $detail"
    }

    /** The `message` field of a GitHub error body, or the first non-blank raw line. */
    private fun errorDetail(bodyText: String): String? {
        val messageField = try {
            githubJson.parseToJsonElement(bodyText) as? JsonObject
        } catch (e: IllegalArgumentException) {
            null // not JSON — fall through to the raw line below
        }?.get("message") as? JsonPrimitive
        if (messageField != null) return messageField.content
        return bodyText.lineSequence().firstOrNull { it.isNotBlank() }
    }

    private companion object {
        const val ACCEPT = "application/vnd.github+json"
        const val FILE_TYPE = "file"
        const val BASE64_ENCODING = "base64"
        const val NOT_CONNECTED_MESSAGE = "Not connected — add a GitHub token first"
        const val UNPARSEABLE_MESSAGE = "Could not parse GitHub response"
        const val MAX_DETAIL_LENGTH = 200
    }
}

// ---------------------------------------------------------------------------
// Wire DTOs (GitHub REST v3 contents-endpoint shapes), same conventions as
// the DTOs in GitHubModels.kt: every field defaults so partial responses
// never crash a decode; unknown fields are dropped by [githubJson].
// ---------------------------------------------------------------------------

/** One entry of the `.github/workflows` directory listing. */
@Serializable
internal data class WorkflowEntryDto(
    val name: String = "",
    val path: String = "",
    val type: String = "",
    val sha: String = "",
    val size: Long = 0L,
    @SerialName("html_url") val htmlUrl: String? = null,
)

/** One file as returned by GET contents/{path} (content still base64). */
@Serializable
internal data class WorkflowFileContentDto(
    val name: String = "",
    val path: String = "",
    val sha: String = "",
    val size: Long = 0L,
    val encoding: String? = null,
    val content: String? = null,
    @SerialName("html_url") val htmlUrl: String? = null,
)

/**
 * Request body of PUT contents/{path}. `branch` is omitted from the JSON
 * when null (encodeDefaults is off), exactly matching GitHub's optional
 * field.
 */
@Serializable
internal data class SaveWorkflowRequestDto(
    val message: String,
    val content: String,
    val sha: String,
    val branch: String? = null,
)
