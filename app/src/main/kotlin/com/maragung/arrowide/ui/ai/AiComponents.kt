package com.maragung.arrowide.ui.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.maragung.arrowide.ai.AiFileDiff
import com.maragung.arrowide.ai.AiMessage
import com.maragung.arrowide.ai.AiMessagePart
import com.maragung.arrowide.ai.AiPermissionRequest
import com.maragung.arrowide.ai.AiProvider
import com.maragung.arrowide.ai.AiSessionInfo
import com.maragung.arrowide.ai.OpenCodeInstallState
import java.time.Duration
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/**
 * Building blocks of the AI agent screen (plan #51, #58, #59, #67): empty and
 * failure states, the install progress card, message bubbles with part
 * renderers (text / tool / step), the permission approval card, the
 * before/after diff viewer, the model and agent pickers and the session list
 * dialog.
 *
 * Shared with [AiScreen] across files, hence internal visibility.
 */

/** Empty state shown when no workspace is open (chat needs a project). */
@Composable
internal fun EmptyAiWorkspaceState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Filled.FolderOpen,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "No workspace open",
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Open a project first to chat with the AI agent.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Centered progress indicator for loading providers, sessions or messages. */
@Composable
internal fun AiLoadingBox(modifier: Modifier = Modifier, label: String? = null) {
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator()
        if (label != null) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** State card shown when the OpenCode server failed to start, with Retry. */
@Composable
internal fun AiServerFailedCard(
    reason: String,
    busy: Boolean,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Error,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "AI server failed to start",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                if (reason.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.height(12.dp))
                Button(onClick = onRetry, enabled = !busy) { Text("Retry") }
            }
        }
    }
}

/**
 * Install progress card for the OpenCode runtime: determinate progress bar
 * when the service reports a 0..1 [progress] fraction, indeterminate
 * otherwise, and the failure message with a Retry button after a failed
 * install.
 */
@Composable
internal fun AiInstallProgressCard(
    state: OpenCodeInstallState,
    message: String?,
    progress: Float?,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            when (state) {
                OpenCodeInstallState.INSTALLING -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = message ?: "Downloading OpenCode…",
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    val fraction = progress
                    if (fraction != null) {
                        LinearProgressIndicator(
                            progress = { fraction.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
                OpenCodeInstallState.FAILED -> {
                    Text(
                        text = "Install failed",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = message ?: "The download or extraction did not complete.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = onRetry) { Text("Retry install") }
                }
                else -> Unit
            }
        }
    }
}

/** Collapsible error card, mirroring the GitHub / Source Control pattern. */
@Composable
internal fun AiErrorCard(
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
                    text = "AI error",
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

/** Failure detail: ellipsized to two lines, expands on tap or via "Details". */
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

/** Neutral hint line used for empty message lists and footers. */
@Composable
internal fun AiHintText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
    )
}

/**
 * One chat message: user bubbles in primaryContainer aligned to the end,
 * assistant bubbles in surfaceVariant aligned to the start, system messages
 * as a subtle centered line. Parts are rendered by type (text with monospace
 * code fences, tool invocations with name + state, steps).
 */
@Composable
internal fun MessageBubble(
    message: AiMessage,
    onShowChanges: (() -> Unit)?,
    changesEnabled: Boolean,
    modifier: Modifier = Modifier
) {
    when (message.role) {
        "user" -> Row(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .background(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(12.dp)
                    )
                    .padding(12.dp)
            ) {
                MessageParts(message)
            }
        }
        "assistant" -> Row(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Start
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth(0.95f)
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(12.dp)
                    )
                    .padding(12.dp)
            ) {
                SelectionContainer {
                    Column {
                        if (message.parts.isEmpty()) {
                            AiHintText("(empty message)")
                        } else {
                            MessageParts(message)
                        }
                    }
                }
                if (onShowChanges != null) {
                    TextButton(
                        onClick = onShowChanges,
                        enabled = changesEnabled
                    ) { Text("Changes") }
                }
            }
        }
        else -> Text(
            text = message.parts.mapNotNull { it.text }.joinToString("\n")
                .ifBlank { "(system)" },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp)
        )
    }
}

/** Renders every part of [message] in order, dispatched by part type. */
@Composable
private fun MessageParts(message: AiMessage) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        message.parts.forEach { part ->
            AiMessagePartView(part)
        }
    }
}

/**
 * One message part: "text" splits markdown ``` fences into prose and
 * monospace code blocks; "tool" shows the tool name with its live state;
 * "step" and any unknown type fall back to a subtle labeled line.
 */
@Composable
internal fun AiMessagePartView(part: AiMessagePart, modifier: Modifier = Modifier) {
    when (part.type) {
        "text" -> TextPart(part.text.orEmpty(), modifier)
        "tool" -> ToolPart(part, modifier)
        else -> {
            // "step", "reasoning" and any future part type.
            val text = part.text
            if (text.isNullOrBlank()) {
                Text(
                    text = aiPartTypeLabel(part.type) +
                        (part.state?.let { " · ${aiStateLabel(it)}" }.orEmpty()),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = modifier
                )
            } else {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = modifier
                )
            }
        }
    }
}

/** Optional language tag on the first line of a markdown code fence. */
private val fenceLanguageTag = Regex("[A-Za-z0-9_+-]{0,20}\\s*")

/** Prose with monospace, subtly-backgrounded code fence blocks. */
@Composable
private fun TextPart(text: String, modifier: Modifier = Modifier) {
    if (text.isBlank()) return
    val segments = text.split("```")
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        segments.forEachIndexed { index, segment ->
            if (segment.isBlank()) return@forEachIndexed
            if (index % 2 == 0) {
                Text(
                    text = segment.trim('\n'),
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                // A fence may open with a language tag on its own first line.
                val body = segment.trim('\n')
                val code = if (body.lineSequence().firstOrNull()
                        ?.matches(fenceLanguageTag) == true
                ) {
                    body.substringAfter('\n', "")
                } else {
                    body
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.65f),
                            shape = RoundedCornerShape(8.dp)
                        )
                        .padding(8.dp)
                ) {
                    Text(
                        text = code,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
        // Fence-only text or plain text without fences already covered above.
    }
}

/** Tool invocation line: icon, monospace tool name, live state. */
@Composable
private fun ToolPart(part: AiMessagePart, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isAiStateActive(part.state)) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp
            )
        } else {
            Icon(
                imageVector = if (part.state == "error") {
                    Icons.Filled.Close
                } else {
                    Icons.Filled.CheckCircle
                },
                contentDescription = null,
                tint = if (part.state == "error") {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(14.dp)
            )
        }
        Spacer(Modifier.width(8.dp))
        val toolName = part.toolName ?: "tool"
        Text(
            text = toolName,
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        val stateLabel = aiStateLabel(part.state)
        if (stateLabel.isNotEmpty()) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = "· $stateLabel",
                style = MaterialTheme.typography.labelMedium,
                color = if (part.state == "error") {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
        if (!part.text.isNullOrBlank()) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = part.text.orEmpty(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Inline permission approval card (plan #59): what the agent wants, the file
 * pattern, and Allow once / Allow & remember / Deny. Buttons disable while
 * the response is being sent.
 */
@Composable
internal fun PermissionCard(
    permission: AiPermissionRequest,
    busy: Boolean,
    onRespond: (allow: Boolean, remember: Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Security,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = permission.title ?: "Agent needs permission",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            val description = permission.description
            if (!description.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis
                )
            }
            val pattern = permission.pattern
            if (!pattern.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = pattern,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = { onRespond(true, false) },
                    enabled = !busy
                ) { Text("Allow once") }
                OutlinedButton(
                    onClick = { onRespond(true, true) },
                    enabled = !busy
                ) { Text("Allow & remember") }
                TextButton(
                    onClick = { onRespond(false, false) },
                    enabled = !busy
                ) { Text("Deny") }
            }
        }
    }
}

/**
 * Bottom sheet listing the file changes the agent made: one card per file
 * with stacked Before / After monospace panes (plan #78). [diff] is null
 * while it is being loaded; an empty list means "no changes".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AiDiffSheet(
    diff: List<AiFileDiff>?,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                Text(
                    text = "Changes",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "Files modified by the AI agent",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(8.dp))
            val content = diff
            if (content == null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else if (content.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    AiHintText("No file changes for this reply.")
                }
            } else {
                SelectionContainer {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 440.dp)
                            .padding(horizontal = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(content, key = { it.path }) { fileDiff ->
                            AiFileDiffCard(fileDiff)
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.End)
            ) { Text("Close") }
        }
    }
}

/** One file of the agent's changes: path header plus before/after panes. */
@Composable
private fun AiFileDiffCard(fileDiff: AiFileDiff) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(
                text = fileDiff.path,
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(6.dp))
            val before = fileDiff.before
            val after = fileDiff.after
            when {
                before == null && after == null -> AiHintText("No content changes.")
                before == null -> DiffPane(
                    label = "New file",
                    content = after.orEmpty(),
                    labelColor = MaterialTheme.colorScheme.tertiary
                )
                after == null -> DiffPane(
                    label = "Deleted",
                    content = before,
                    labelColor = MaterialTheme.colorScheme.error
                )
                else -> {
                    DiffPane(
                        label = "Before",
                        content = before,
                        labelColor = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(8.dp))
                    DiffPane(
                        label = "After",
                        content = after,
                        labelColor = MaterialTheme.colorScheme.tertiary
                    )
                }
            }
        }
    }
}

/** Monospace, two-axis scrollable content pane labeled Before/After. */
@Composable
private fun DiffPane(
    label: String,
    content: String,
    labelColor: Color
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.65f),
                shape = RoundedCornerShape(8.dp)
            )
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = labelColor,
            modifier = Modifier.padding(start = 8.dp, top = 6.dp)
        )
        Text(
            text = content,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            softWrap = false,
            modifier = Modifier
                .heightIn(max = 220.dp)
                .verticalScroll(rememberScrollState())
                .horizontalScroll(rememberScrollState())
                .padding(8.dp)
        )
    }
}

/**
 * Model picker: a compact chip that opens a dropdown of every
 * "provider / model" combination of [providers], plus the server default.
 */
@Composable
internal fun ModelDropdown(
    providers: List<AiProvider>,
    selected: String?,
    enabled: Boolean,
    onSelect: (model: String?) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        OutlinedButton(
            onClick = { expanded = true },
            enabled = enabled
        ) {
            Text(
                text = selected ?: "Default model",
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            DropdownMenuItem(
                text = { Text("Default model") },
                onClick = {
                    expanded = false
                    onSelect(null)
                }
            )
            providers.forEach { provider ->
                provider.models.forEach { model ->
                    val value = "${provider.id}/${model.id}"
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(
                                    text = model.name.ifBlank { model.id },
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    text = provider.name.ifBlank { provider.id },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        onClick = {
                            expanded = false
                            onSelect(value)
                        }
                    )
                }
            }
        }
    }
}

/** Agent-mode picker (plan #58): Chat, Plan and Agent (build). */
@Composable
internal fun AgentDropdown(
    selected: AiAgentOption,
    enabled: Boolean,
    onSelect: (AiAgentOption) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        OutlinedButton(
            onClick = { expanded = true },
            enabled = enabled
        ) {
            Text(
                text = selected.label,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            AI_AGENT_OPTIONS.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    }
                )
            }
        }
    }
}

/**
 * Session picker dialog (plan #51): tap a session to open it, delete with
 * confirmation, or start a new chat.
 */
@Composable
internal fun SessionsDialog(
    sessions: List<AiSessionInfo>,
    selectedSessionId: String?,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSelect: (AiSessionInfo) -> Unit,
    onNewSession: () -> Unit,
    onDeleteSession: (AiSessionInfo) -> Unit
) {
    var deleteTarget by remember { mutableStateOf<AiSessionInfo?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("AI sessions") },
        text = {
            Column(modifier = Modifier.heightIn(max = 360.dp)) {
                if (sessions.isEmpty()) {
                    AiHintText("No sessions yet.")
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(sessions, key = { it.id }) { session ->
                            SessionRow(
                                session = session,
                                selected = session.id == selectedSessionId,
                                busy = busy,
                                onClick = { onSelect(session) },
                                onDelete = { deleteTarget = session }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onNewSession, enabled = !busy) { Text("New chat") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete session?") },
            text = {
                Text(
                    "\"${target.title.ifBlank { "Untitled session" }}\" is removed " +
                        "from this device. Messages cannot be recovered."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteTarget = null
                        onDeleteSession(target)
                    }
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            }
        )
    }
}

/** One session row in [SessionsDialog]. */
@Composable
private fun SessionRow(
    session: AiSessionInfo,
    selected: Boolean,
    busy: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !busy, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.Chat,
            contentDescription = null,
            tint = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
        Spacer(Modifier.width(12.dp))
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 4.dp)
        ) {
            Text(
                text = session.title.ifBlank { "Untitled session" },
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val updated = formatAiRelativeTime(session.updatedAt)
            if (updated.isNotEmpty()) {
                Text(
                    text = updated,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        IconButton(onClick = onDelete, enabled = !busy) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "Delete session ${session.title}",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** "3h ago" style relative age of a session's updatedAt; "" when unparseable. */
internal fun formatAiRelativeTime(raw: String?): String {
    if (raw.isNullOrBlank()) return ""
    val time = try {
        OffsetDateTime.parse(raw)
    } catch (_: DateTimeParseException) {
        return ""
    }
    val age = Duration.between(time, OffsetDateTime.now())
    if (age.isNegative || age.seconds < 60) return "just now"
    val minutes = age.toMinutes()
    if (minutes < 60) return "${minutes}m ago"
    val hours = age.toHours()
    if (hours < 24) return "${hours}h ago"
    val days = age.toDays()
    if (days < 30) return "${days}d ago"
    return "${time.year}-${time.monthValue}-${time.dayOfMonth}"
}
