package com.maragung.arrowide.ui.github

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.maragung.arrowide.github.GitHubResult
import com.maragung.arrowide.github.GitHubService
import com.maragung.arrowide.github.GithubRepo
import com.maragung.arrowide.github.GithubWorkflowRun
import com.maragung.arrowide.github.GithubJob
import java.time.Duration
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Shared plumbing of the GitHub UI (plan #13-#16, #19): the internal page
 * model, the action/error controller used by every page, and small building
 * blocks (status icons, badges, avatars, time formatting).
 *
 * Everything here is internal to the GitHub package; the public entry point
 * is [GitHubScreen].
 */

/** Internal navigation inside the GitHub screen (NOT the app NavHost). */
internal sealed interface GitHubPage {
    data object Connect : GitHubPage
    data object Account : GitHubPage
    data object Repositories : GitHubPage
    data class RepoDetail(val repo: GithubRepo) : GitHubPage
    data object NewRepository : GitHubPage
    data object Actions : GitHubPage
    data class RunDetail(val repo: GithubRepo, val run: GithubWorkflowRun) : GitHubPage
    data class LogViewer(
        val repo: GithubRepo,
        val run: GithubWorkflowRun,
        val job: GithubJob
    ) : GitHubPage
}

/** Where the system back button / top-bar back arrow leads from [page]. */
internal fun parentPage(page: GitHubPage): GitHubPage = when (page) {
    is GitHubPage.RepoDetail -> GitHubPage.Repositories
    is GitHubPage.RunDetail -> GitHubPage.Actions
    is GitHubPage.LogViewer -> GitHubPage.RunDetail(page.repo, page.run)
    GitHubPage.Repositories, GitHubPage.NewRepository, GitHubPage.Actions -> GitHubPage.Account
    GitHubPage.Connect, GitHubPage.Account -> GitHubPage.Account
}

/** Per-page bundle of the shared snackbar host and error-card state. */
internal class GitHubPageContext(
    val controller: GitHubController,
    val snackbarHostState: SnackbarHostState,
    val error: String?,
    val dismissError: () -> Unit
)

/**
 * Wraps every GitHub call of a page: [call] turns [GitHubResult.Error] into
 * the screen's error card + snackbar (returning null), and [launchAction]
 * runs suspend blocks with [CancellationException] re-thrown and any other
 * exception reported — the same discipline as the Source Control screen.
 */
internal class GitHubController(
    val service: GitHubService,
    private val coroutineScope: CoroutineScope,
    private val onError: (String) -> Unit
) {
    /** Surfaces a raw [GitHubResult.Error] through the error paths. */
    fun report(label: String, error: GitHubResult.Error) {
        onError(formatGitHubError(label, error))
    }

    /** Surfaces an arbitrary failure message through the error paths. */
    fun reportDetail(detail: String) {
        onError(detail)
    }

    /** Fire-and-forget action with exception reporting. */
    fun launchAction(label: String, action: suspend () -> Unit) {
        coroutineScope.launch {
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError("GitHub $label failed: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    /** Runs a GitHub API call; [GitHubResult.Error] is reported and yields null. */
    suspend fun <T> call(label: String, block: suspend () -> GitHubResult<T>): T? =
        when (val result = block()) {
            is GitHubResult.Ok -> result.value
            is GitHubResult.Error -> {
                onError(formatGitHubError(label, result))
                null
            }
        }
}

/**
 * Generic failure line: operation label + HTTP status + the server's message.
 * Never includes anything the user typed (e.g. the PAT) — only the label we
 * chose and the server response (plan #12).
 */
internal fun formatGitHubError(label: String, error: GitHubResult.Error): String {
    val message = error.message.trim()
    return buildString {
        append("GitHub ")
        append(label)
        append(" failed")
        error.statusCode?.let { append(" (HTTP ").append(it).append(')') }
        if (message.isNotEmpty()) {
            append(": ")
            append(message)
        }
    }
}

/** First non-blank line of [detail], truncated, for the snackbar. */
internal fun snackbarExcerpt(detail: String): String {
    val line = detail.lineSequence().firstOrNull { it.isNotBlank() } ?: return detail
    return if (line.length > 140) line.take(137) + "…" else line
}

/** Collapsible error card, mirroring the Source Control pattern. */
@Composable
internal fun GitHubErrorCard(
    message: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(
                start = 16.dp,
                top = 12.dp,
                end = 8.dp,
                bottom = 4.dp
            )
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "GitHub error",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onDismiss) { Text("Dismiss") }
            }
            ExpandableErrorText(message)
        }
    }
}

@Composable
private fun ExpandableErrorText(message: String) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            maxLines = if (expanded) Int.MAX_VALUE else 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.clickable { expanded = !expanded }
        )
        if (!expanded) {
            TextButton(onClick = { expanded = true }) { Text("Details") }
        }
    }
}

/** Circle avatar with the account's first letter (no image loading, plan #12). */
@Composable
internal fun LetterAvatar(
    login: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(40.dp)
            .background(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = CircleShape
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = login.trim().firstOrNull()?.uppercase() ?: "?",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}

/** Small rounded "Private" badge for private repositories. */
@Composable
internal fun PrivateBadge(modifier: Modifier = Modifier) {
    Text(
        text = "Private",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(6.dp)
            )
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

/** Neutral hint line used for empty lists and footers. */
@Composable
internal fun HintText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
    )
}

/**
 * Status icon for a workflow run or step. [status] is the raw API status
 * ("queued", "in_progress", "completed"); [conclusion] the raw conclusion
 * ("success", "failure", …). Unknown values fall back to a neutral icon.
 */
@Composable
internal fun RunStatusIcon(
    status: String?,
    conclusion: String?,
    modifier: Modifier = Modifier
) {
    when (status) {
        "in_progress" -> CircularProgressIndicator(
            modifier = modifier.size(16.dp),
            strokeWidth = 2.dp
        )
        "queued" -> Icon(
            imageVector = Icons.Filled.Schedule,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier.size(18.dp)
        )
        else -> ConclusionIcon(conclusion, modifier)
    }
}

/** Icon for a finished run/step, mapped defensively (unknown → neutral). */
@Composable
internal fun ConclusionIcon(
    conclusion: String?,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    val (icon, tint) = when (conclusion) {
        "success" -> Icons.Filled.CheckCircle to colorScheme.tertiary
        "failure" -> Icons.Filled.Close to colorScheme.error
        "startup_failure" -> Icons.Filled.Error to colorScheme.error
        "timed_out" -> Icons.Filled.Warning to colorScheme.error
        "cancelled" -> Icons.Filled.Cancel to colorScheme.onSurfaceVariant
        "skipped" -> Icons.Filled.SkipNext to colorScheme.onSurfaceVariant
        "neutral" -> Icons.Filled.Remove to colorScheme.onSurfaceVariant
        "action_required" -> Icons.Filled.Warning to colorScheme.primary
        else -> Icons.Filled.HelpOutline to colorScheme.onSurfaceVariant
    }
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = tint,
        modifier = modifier.size(18.dp)
    )
}

/** Human label for a run/step conclusion, mapped defensively. */
internal fun conclusionLabel(conclusion: String?): String = when (conclusion) {
    "success" -> "Success"
    "failure" -> "Failed"
    "startup_failure" -> "Startup failure"
    "cancelled" -> "Cancelled"
    "skipped" -> "Skipped"
    "timed_out" -> "Timed out"
    "neutral" -> "Neutral"
    "action_required" -> "Action required"
    null -> "Unknown"
    else -> conclusion
}

/** Human label for a run/step status, mapped defensively. */
internal fun statusLabel(status: String?): String = when (status) {
    "queued" -> "Queued"
    "in_progress" -> "Running"
    "completed" -> "Completed"
    null -> "Unknown"
    else -> status
}

/** A run still executing (cancel button shown while true). */
internal fun isRunActive(status: String?): Boolean =
    status == "queued" || status == "in_progress"

/** Re-run failed jobs is offered for failed conclusions only. */
internal fun canRerun(conclusion: String?): Boolean =
    conclusion == "failure" || conclusion == "startup_failure" || conclusion == "timed_out"

/** ISO-8601 parsing that never throws; null for blank/invalid input. */
internal fun parseIsoTime(raw: String?): OffsetDateTime? {
    if (raw.isNullOrBlank()) return null
    return try {
        OffsetDateTime.parse(raw)
    } catch (_: DateTimeParseException) {
        null
    }
}

private val fallbackDateFormat = DateTimeFormatter.ofPattern("MMM d, yyyy")

/** "3h ago" style relative age; empty string when the input is unparseable. */
internal fun formatRelativeTime(raw: String?): String {
    val time = parseIsoTime(raw) ?: return ""
    val age = Duration.between(time, OffsetDateTime.now())
    if (age.isNegative || age.seconds < 60) return "just now"
    val minutes = age.toMinutes()
    if (minutes < 60) return "${minutes}m ago"
    val hours = age.toHours()
    if (hours < 24) return "${hours}h ago"
    val days = age.toDays()
    if (days < 30) return "${days}d ago"
    return fallbackDateFormat.format(time)
}

/** "3m 12s" / "1h 04m" duration between two ISO timestamps. */
internal fun formatRunDuration(createdAt: String?, updatedAt: String?): String {
    val start = parseIsoTime(createdAt) ?: return ""
    val end = parseIsoTime(updatedAt) ?: OffsetDateTime.now()
    return formatDuration(Duration.between(start, end))
}

private fun formatDuration(duration: Duration): String {
    if (duration.isNegative || duration.isZero) return "0s"
    val totalSeconds = duration.seconds
    if (totalSeconds < 60) return "${totalSeconds}s"
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    if (minutes < 60) return "${minutes}m ${seconds}s"
    val hours = minutes / 60
    val remMinutes = minutes % 60
    return "${hours}h " + remMinutes.toString().padStart(2, '0') + "m"
}

/** First 7 characters of a commit SHA, monospace-friendly. */
internal fun shortSha(sha: String?): String = sha?.take(7) ?: ""
