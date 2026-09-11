package com.maragung.arrowide.ui.github

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.maragung.arrowide.github.GithubJob
import com.maragung.arrowide.github.GithubRepo
import com.maragung.arrowide.github.GithubWorkflowRun
import kotlinx.coroutines.launch

/**
 * Actions log viewer (plan #19): a terminal-like, monospace, selectable,
 * two-axis scrollable view of one job's log, with search (filters lines,
 * keeping the original line numbers), an auto-scroll-to-bottom toggle that is
 * ON by default, and a copy-to-clipboard action. The log is treated as plain
 * text lines; no tokens or secrets are stored from it (plan #12, #20).
 */

/** Render cap so very large logs stay responsive in the non-lazy viewer. */
private const val MAX_RENDERED_LINES = 5000

@Composable
internal fun LogViewerPage(
    ctx: GitHubPageContext,
    repo: GithubRepo,
    run: GithubWorkflowRun,
    job: GithubJob,
    onBack: () -> Unit
) {
    // neverEqualPolicy: a reloaded log with identical text still re-triggers
    // the auto-scroll effect.
    var log by remember(repo.id, run.id, job.id) {
        mutableStateOf<String?>(null, neverEqualPolicy())
    }
    var loading by remember(repo.id, run.id, job.id) { mutableStateOf(false) }
    var query by remember(repo.id, run.id, job.id) { mutableStateOf("") }
    var autoScroll by remember(repo.id, run.id, job.id) { mutableStateOf(true) }

    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val verticalScroll = rememberScrollState()
    val horizontalScroll = rememberScrollState()

    fun load() {
        ctx.controller.launchAction("load job log") {
            loading = true
            try {
                log = ctx.controller.call("load job log") {
                    ctx.controller.service.downloadJobLog(
                        owner = repo.ownerLogin,
                        repo = repo.name,
                        jobId = job.id
                    )
                } ?: log
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(repo.id, run.id, job.id) { load() }

    // Auto-scroll to the bottom while enabled; re-enabling the toggle jumps
    // back down. One frame is skipped so the new content is measured first.
    LaunchedEffect(autoScroll, log) {
        if (autoScroll && log != null) {
            withFrameNanos { }
            verticalScroll.scrollTo(verticalScroll.maxValue)
        }
    }

    val trimmedQuery = query.trim()
    val allLines = remember(log) { (log ?: "").lines() }
    val matching = remember(allLines, trimmedQuery) {
        if (trimmedQuery.isEmpty()) {
            allLines.indices.toList()
        } else {
            allLines.indices.filter { allLines[it].contains(trimmedQuery, ignoreCase = true) }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(ctx.snackbarHostState) },
        topBar = {
            GitHubTopBar(
                title = "Logs",
                onBack = onBack,
                onRefresh = { load() },
                refreshEnabled = !loading,
                extraActions = {
                    IconButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(log ?: ""))
                            scope.launch {
                                ctx.snackbarHostState.showSnackbar("Log copied")
                            }
                        },
                        enabled = !loading && log != null
                    ) {
                        Icon(
                            Icons.Filled.ContentCopy,
                            contentDescription = "Copy log"
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                Text(
                    text = job.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "Run #${run.id} · ${repo.ownerLogin}/${repo.name}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search log") },
                singleLine = true,
                enabled = log != null,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Auto-scroll",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                Switch(checked = autoScroll, onCheckedChange = { autoScroll = it })
            }
            if (trimmedQuery.isNotEmpty()) {
                Text(
                    text = "${matching.size} of ${allLines.size} lines",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
            ctx.error?.let { message ->
                GitHubErrorCard(
                    message = message,
                    onDismiss = ctx.dismissError,
                    modifier = Modifier.padding(16.dp)
                )
            }
            val content = log
            when {
                content == null && loading -> Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
                content == null -> Unit
                matching.isEmpty() -> Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No lines match \"$trimmedQuery\".",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                else -> {
                    SelectionContainer(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .verticalScroll(verticalScroll)
                                .horizontalScroll(horizontalScroll)
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(1.dp)
                        ) {
                            val rendered = matching.take(MAX_RENDERED_LINES)
                            rendered.forEach { index ->
                                LogLine(
                                    lineNumber = index + 1,
                                    text = allLines[index]
                                )
                            }
                            if (matching.size > rendered.size) {
                                Text(
                                    text = "… ${matching.size - rendered.size} more lines " +
                                        "not shown",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One log line: number gutter plus the raw text, monospace, no wrapping. */
@Composable
private fun LogLine(
    lineNumber: Int,
    text: String
) {
    Row {
        Text(
            text = lineNumber.toString(),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            softWrap = false,
            modifier = Modifier.width(48.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = text.ifBlank { " " },
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            softWrap = false
        )
    }
}
