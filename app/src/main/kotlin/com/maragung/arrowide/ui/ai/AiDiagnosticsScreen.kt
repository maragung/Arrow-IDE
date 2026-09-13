package com.maragung.arrowide.ui.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.maragung.arrowide.ai.AiActivityAudit
import com.maragung.arrowide.ai.AiActivityKind
import com.maragung.arrowide.ai.AiRequestLog

/**
 * AI diagnostics (plans #92 + #97): the in-memory AI request log and the
 * persisted agent activity audit, newest first, with clear actions. Pure
 * display — every entry was recorded by the real AI layer; nothing is
 * fabricated (plan #49).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiDiagnosticsScreen(
    requestLog: AiRequestLog,
    activityAudit: AiActivityAudit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var requestEntries by remember { mutableStateOf(requestLog.snapshot()) }
    var auditEntries by remember { mutableStateOf(activityAudit.entries()) }
    var refreshKey by remember { mutableStateOf(0) }

    // Re-read on refresh; both stores are cheap snapshots.
    androidx.compose.runtime.LaunchedEffect(refreshKey) {
        requestEntries = requestLog.snapshot()
        auditEntries = activityAudit.entries()
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("AI diagnostics") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(onClick = { refreshKey++ }) { Text("Refresh") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                SectionHeader(
                    title = "Request log (${requestEntries.size})",
                    onClear = {
                        requestLog.clear()
                        refreshKey++
                    },
                )
            }
            if (requestEntries.isEmpty()) {
                item { Empty("No AI requests recorded in this session.") }
            } else {
                items(requestEntries.asReversed(), key = { "req-${it.timestamp}-${it.url}" }) { entry ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "${entry.method} ${entry.url}",
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                            )
                            Text(
                                text = "${entry.statusCode} · ${entry.durationMs} ms" +
                                    (entry.error?.let { " · $it" } ?: ""),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (entry.statusCode in 200..399) {
                                    MaterialTheme.colorScheme.tertiary
                                } else {
                                    MaterialTheme.colorScheme.error
                                },
                            )
                        }
                    }
                }
            }

            item {
                SectionHeader(
                    title = "Activity audit (${auditEntries.size})",
                    onClear = {
                        activityAudit.clear()
                        refreshKey++
                    },
                )
            }
            if (auditEntries.isEmpty()) {
                item { Empty("No agent activity recorded yet.") }
            } else {
                items(auditEntries.asReversed(), key = { "act-${it.timestamp}-${it.detail}" }) { entry ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = activityLabel(entry.kind),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                text = entry.detail,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                text = java.text.DateFormat.getDateTimeInstance()
                                    .format(java.util.Date(entry.timestamp)) +
                                    " · session ${entry.sessionId.take(8)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, onClear: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onClear) {
            Icon(Icons.Filled.Delete, contentDescription = "Clear")
        }
    }
}

@Composable
private fun Empty(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun activityLabel(kind: AiActivityKind): String = when (kind) {
    AiActivityKind.FILE_EDIT -> "File edit"
    AiActivityKind.TOOL_RUN -> "Tool run"
    AiActivityKind.PERMISSION_GRANTED -> "Permission granted"
    AiActivityKind.PERMISSION_DENIED -> "Permission denied"
    AiActivityKind.USER_MESSAGE -> "User message"
}
