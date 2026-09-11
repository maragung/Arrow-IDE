package com.maragung.arrowide.ai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Shared Json for the OpenCode layer: the server's responses carry many
 * fields we do not model (and its schema evolves between releases), so
 * unknown keys are ignored and parsing is lenient. Endpoints whose wire
 * shape is uncertain are parsed even more defensively by hand (see the
 * parse* functions in [OpenCodeClient.kt]); decode failures surface as
 * [AiResult.Error], never as crashes.
 */
internal val opencodeJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

/**
 * Result of any OpenCode operation the UI triggers: errors are VALUES,
 * never exceptions, so screens can render them directly (same philosophy
 * as [com.maragung.arrowide.github.GitHubResult]). [Error.statusCode] is
 * the HTTP status when the server answered, or null for local failures
 * (no network, server not running, unparseable response).
 */
sealed interface AiResult<out T> {

    data class Ok<T>(val value: T) : AiResult<T>

    data class Error(val statusCode: Int?, val message: String) : AiResult<Nothing>
}

/**
 * The GitHub release asset that fits this device: sst/opencode publishes
 * static musl binaries (`opencode-linux-arm64-musl.tar.gz`,
 * `opencode-linux-x64-musl.tar.gz`) that run on Android via exec().
 */
data class OpenCodeReleaseInfo(
    val tagName: String,
    val assetName: String,
    val assetUrl: String,
    val assetSizeBytes: Long,
)

/** Lifecycle of the OpenCode binary on disk. */
enum class OpenCodeInstallState { NOT_INSTALLED, INSTALLING, INSTALLED, FAILED }

/**
 * Detail view of a (possibly running) install: the coarse [state] plus a
 * human message and a 0..1 progress estimate (null when indeterminate).
 * Rendered from [OpenCodeService.installProgress].
 */
data class OpenCodeInstallProgress(
    val state: OpenCodeInstallState,
    val message: String? = null,
    val progress: Float? = null,
)

/**
 * Lifecycle of the on-device OpenCode server process, published through
 * [OpenCodeService.serverState].
 */
sealed interface OpenCodeServerState {

    /** No server process is running. */
    data object Stopped : OpenCodeServerState

    /** A process was spawned; waiting for its HTTP health check to pass. */
    data object Starting : OpenCodeServerState

    /**
     * The server answers on 127.0.0.1:[port]. [workingDir] is the directory
     * the process was spawned in — the open project's workspace, or null
     * when it runs in its HOME. A [OpenCodeService.ensureServer] call for a
     * different directory replaces the running server.
     */
    data class Ready(val port: Int, val workingDir: File? = null) : OpenCodeServerState

    /** Startup failed or the process died; [reason] carries a truncated tail. */
    data class Failed(val reason: String) : OpenCodeServerState
}

/** One chat session (GET /session, POST /session). */
data class AiSessionInfo(
    val id: String,
    val title: String,
    val updatedAt: String? = null,
)

/**
 * One part of a chat message. [type] is the wire discriminator
 * ("text", "step-start", "tool", ...); the remaining fields are whatever
 * the part carried, all optional because the shape varies per type and
 * per server release.
 */
data class AiMessagePart(
    val type: String,
    val text: String? = null,
    val state: String? = null,
    val toolName: String? = null,
)

/** One message of a session: role ("user"/"assistant") plus its parts. */
data class AiMessage(
    val id: String,
    val role: String,
    val parts: List<AiMessagePart> = emptyList(),
)

/** One file changed by the agent (GET /session/{id}/diff). */
data class AiFileDiff(
    val path: String,
    val before: String? = null,
    val after: String? = null,
)

/**
 * One permission request the agent raised (delivered as a
 * `permission.updated` event on the SSE stream). Answer it with
 * [OpenCodeClient.respondToPermission] using [id].
 */
data class AiPermissionRequest(
    val sessionId: String,
    val id: String,
    val pattern: String? = null,
    val title: String? = null,
    val description: String? = null,
)

/** One model a provider offers. */
data class AiModel(val id: String, val name: String)

/** One AI provider (Anthropic, OpenAI, ...) and its models. */
data class AiProvider(
    val id: String,
    val name: String,
    val models: List<AiModel> = emptyList(),
)

// ---------------------------------------------------------------------------
// Wire DTOs for the GitHub release lookup (GET /repos/sst/opencode/releases/
// latest). Every field has a default so partial responses never crash a
// decode; unknown fields are dropped by [opencodeJson].
// ---------------------------------------------------------------------------

@Serializable
internal data class OpenCodeReleaseDto(
    @SerialName("tag_name") val tagName: String = "",
    val assets: List<OpenCodeAssetDto> = emptyList(),
)

@Serializable
internal data class OpenCodeAssetDto(
    val name: String = "",
    @SerialName("browser_download_url") val browserDownloadUrl: String = "",
    val size: Long = 0L,
)
