package com.maragung.arrowide.github

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Shared Json for the GitHub REST v3 layer: GitHub responses carry many
 * fields we do not model, so unknown keys are ignored and parsing is
 * lenient. All decode failures surface as parse errors in [GitHubApi],
 * never as crashes.
 */
internal val githubJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

/** GitHub account (GET /user). */
data class GithubUser(
    val login: String,
    val name: String?,
    val avatarUrl: String = "",
)

/**
 * One repository (GET /user/repos, GET /repos/{o}/{r}, POST /user/repos).
 * [ownerLogin] is the flattened `owner.login` of the wire format.
 */
data class GithubRepo(
    val id: Long,
    val name: String,
    val ownerLogin: String,
    val isPrivate: Boolean,
    val defaultBranch: String,
    val description: String? = null,
    val updatedAt: String,
    val htmlUrl: String,
)

/** One commit of GET /repos/{o}/{r}/commits. */
data class GithubCommit(
    val sha: String,
    val message: String,
    val authorName: String,
    val authorEmail: String? = null,
    val date: String,
)

/** One pull request of GET /repos/{o}/{r}/pulls. */
data class GithubPullRequest(
    val number: Int,
    val title: String,
    val state: String,
    val updatedAt: String,
)

/** One issue of GET /repos/{o}/{r}/issues (PR entries filtered out upstream). */
data class GithubIssue(
    val number: Int,
    val title: String,
    val state: String,
    val updatedAt: String,
)

/** One workflow run of GET /repos/{o}/{r}/actions/runs. */
data class GithubWorkflowRun(
    val id: Long,
    val name: String,
    val status: String? = null,
    val conclusion: String? = null,
    val headBranch: String? = null,
    val headSha: String? = null,
    val createdAt: String,
    val updatedAt: String,
    val htmlUrl: String,
)

/** One job of GET /repos/{o}/{r}/actions/runs/{id}/jobs. */
data class GithubJob(
    val id: Long,
    val name: String,
    val status: String,
    val conclusion: String? = null,
    val startedAt: String? = null,
    val completedAt: String? = null,
    val steps: List<GithubStep> = emptyList(),
)

/** One step of a job's `steps` array. */
data class GithubStep(
    val name: String,
    val number: Int,
    val status: String,
    val conclusion: String? = null,
)

/** One entry of GET /repos/{o}/{r}/contents/.github/workflows. */
data class GithubContentEntry(
    val name: String,
    val path: String,
    val type: String,
    val downloadUrl: String? = null,
)

// ---------------------------------------------------------------------------
// Wire DTOs (GitHub REST v3 JSON shapes) and their mapping to the models
// above. Every field has a default so partial responses never crash a
// decode; unknown fields are dropped by [githubJson].
// ---------------------------------------------------------------------------

@Serializable
internal data class UserDto(
    val login: String = "",
    val name: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
) {
    fun toModel() = GithubUser(
        login = login,
        name = name,
        avatarUrl = avatarUrl.orEmpty(),
    )
}

@Serializable
internal data class OwnerDto(val login: String = "")

@Serializable
internal data class RepoDto(
    val id: Long = 0L,
    val name: String = "",
    val owner: OwnerDto? = null,
    @SerialName("private") val isPrivate: Boolean = false,
    @SerialName("default_branch") val defaultBranch: String? = null,
    val description: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("html_url") val htmlUrl: String = "",
) {
    fun toModel() = GithubRepo(
        id = id,
        name = name,
        ownerLogin = owner?.login.orEmpty(),
        isPrivate = isPrivate,
        defaultBranch = defaultBranch.orEmpty(),
        description = description,
        updatedAt = updatedAt.orEmpty(),
        htmlUrl = htmlUrl,
    )
}

/** Request body of POST /user/repos. */
@Serializable
internal data class CreateRepoRequestDto(
    val name: String,
    val description: String? = null,
    @SerialName("private") val isPrivate: Boolean,
)

@Serializable
internal data class BranchDto(val name: String = "")

@Serializable
internal data class CommitAuthorDto(
    val name: String = "",
    val email: String? = null,
    val date: String = "",
)

@Serializable
internal data class CommitDetailDto(
    val message: String = "",
    val author: CommitAuthorDto? = null,
)

@Serializable
internal data class CommitDto(
    val sha: String = "",
    val commit: CommitDetailDto? = null,
    /** The GitHub account of the author; login is the name fallback. */
    val author: UserDto? = null,
) {
    fun toModel() = GithubCommit(
        sha = sha,
        message = commit?.message.orEmpty(),
        authorName = commit?.author?.name ?: author?.login.orEmpty(),
        authorEmail = commit?.author?.email,
        date = commit?.author?.date.orEmpty(),
    )
}

@Serializable
internal data class PullRequestDto(
    val number: Int = 0,
    val title: String = "",
    val state: String = "",
    @SerialName("updated_at") val updatedAt: String? = null,
) {
    fun toModel() = GithubPullRequest(
        number = number,
        title = title,
        state = state,
        updatedAt = updatedAt.orEmpty(),
    )
}

/**
 * Wire shape of an issue entry. The issues endpoint also returns pull
 * requests; [GitHubApi] drops entries whose JSON carries a "pull_request"
 * object BEFORE decoding, so the dto does not need the field.
 */
@Serializable
internal data class IssueDto(
    val number: Int = 0,
    val title: String = "",
    val state: String = "",
    @SerialName("updated_at") val updatedAt: String? = null,
) {
    fun toModel() = GithubIssue(
        number = number,
        title = title,
        state = state,
        updatedAt = updatedAt.orEmpty(),
    )
}

/** Wrapper of GET /repos/{o}/{r}/actions/runs. */
@Serializable
internal data class WorkflowRunsResponseDto(
    @SerialName("total_count") val totalCount: Int = 0,
    @SerialName("workflow_runs") val workflowRuns: List<WorkflowRunDto> = emptyList(),
)

@Serializable
internal data class WorkflowRunDto(
    val id: Long = 0L,
    val name: String? = null,
    val status: String? = null,
    val conclusion: String? = null,
    @SerialName("head_branch") val headBranch: String? = null,
    @SerialName("head_sha") val headSha: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("html_url") val htmlUrl: String = "",
) {
    fun toModel() = GithubWorkflowRun(
        id = id,
        name = name.orEmpty(),
        status = status,
        conclusion = conclusion,
        headBranch = headBranch,
        headSha = headSha,
        createdAt = createdAt.orEmpty(),
        updatedAt = updatedAt.orEmpty(),
        htmlUrl = htmlUrl,
    )
}

/** Wrapper of GET /repos/{o}/{r}/actions/runs/{id}/jobs. */
@Serializable
internal data class JobsResponseDto(
    @SerialName("total_count") val totalCount: Int = 0,
    val jobs: List<JobDto> = emptyList(),
)

@Serializable
internal data class JobDto(
    val id: Long = 0L,
    val name: String = "",
    val status: String = "",
    val conclusion: String? = null,
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("completed_at") val completedAt: String? = null,
    val steps: List<StepDto> = emptyList(),
) {
    fun toModel() = GithubJob(
        id = id,
        name = name,
        status = status,
        conclusion = conclusion,
        startedAt = startedAt,
        completedAt = completedAt,
        steps = steps.map { it.toModel() },
    )
}

@Serializable
internal data class StepDto(
    val name: String = "",
    val number: Int = 0,
    val status: String = "",
    val conclusion: String? = null,
) {
    fun toModel() = GithubStep(
        name = name,
        number = number,
        status = status,
        conclusion = conclusion,
    )
}

@Serializable
internal data class ContentEntryDto(
    val name: String = "",
    val path: String = "",
    val type: String = "",
    @SerialName("download_url") val downloadUrl: String? = null,
) {
    fun toModel() = GithubContentEntry(
        name = name,
        path = path,
        type = type,
        downloadUrl = downloadUrl,
    )
}

/** Request body of POST .../dispatches. */
@Serializable
internal data class DispatchRequestDto(
    val ref: String,
    val inputs: Map<String, String> = emptyMap(),
)
