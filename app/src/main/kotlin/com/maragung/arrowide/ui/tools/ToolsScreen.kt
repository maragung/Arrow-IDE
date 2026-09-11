package com.maragung.arrowide.ui.tools

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.maragung.arrowide.toolchain.ToolInfo
import com.maragung.arrowide.toolchain.ToolOperation
import com.maragung.arrowide.toolchain.ToolPhase
import com.maragung.arrowide.toolchain.ToolStatus
import com.maragung.arrowide.toolchain.ToolchainManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Tool Manager (plan #5): lists every tool known to the [ToolchainManager]
 * with install / update / remove / repair actions. Active operations render
 * inline on their tool's card (phase label, message and progress), optional
 * tools that have no binary for this architecture are shown as unavailable,
 * and removal always asks for confirmation first.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsScreen(
    manager: ToolchainManager,
    modifier: Modifier = Modifier
) {
    val tools by manager.tools.collectAsStateWithLifecycle()
    val operations by manager.operations.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var removing by remember { mutableStateOf<ToolInfo?>(null) }

    // Refresh tool statuses whenever the screen is entered.
    LaunchedEffect(Unit) {
        try {
            manager.refresh()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Failures are surfaced through the tools StateFlow by the manager.
        }
    }

    fun launchToolAction(action: suspend () -> Unit) {
        scope.launch {
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The manager reports failures through the tools StateFlow;
                // never let them crash the UI.
            }
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Tools") },
                actions = {
                    IconButton(onClick = { launchToolAction { manager.refresh() } }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh tools")
                    }
                }
            )
        }
    ) { padding ->
        if (tools.isEmpty()) {
            Box(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Text(
                        text = "${tools.count { it.status == ToolStatus.INSTALLED }} of ${tools.size} installed",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                items(tools, key = { it.id }) { tool ->
                    // Only optional, not-yet-installed tools are gated on
                    // architecture availability.
                    val availability =
                        if (tool.optional && tool.status == ToolStatus.NOT_INSTALLED) {
                            remember(tool.id) { manager.isAvailable(tool.id) }
                        } else {
                            null
                        }
                    ToolCard(
                        tool = tool,
                        operation = operations[tool.id],
                        installEnabled = !tool.optional || availability == true,
                        showUnavailable = tool.optional && availability == false,
                        onInstall = { launchToolAction { manager.install(tool.id) } },
                        onUpdate = { launchToolAction { manager.update(tool.id) } },
                        onRemove = { removing = tool },
                        onRepair = { launchToolAction { manager.repair(tool.id) } }
                    )
                }
            }
        }
    }

    removing?.let { tool ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove ${tool.displayName}?") },
            text = { Text("Files shared with other tools are kept.") },
            confirmButton = {
                TextButton(onClick = {
                    removing = null
                    launchToolAction { manager.remove(tool.id) }
                }) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { removing = null }) { Text("Cancel") }
            }
        )
    }
}

/**
 * A single tool in the list: name, description, status (or live operation
 * progress) and the actions available in that state. All buttons are disabled
 * while an operation for this tool is running.
 */
@Composable
private fun ToolCard(
    tool: ToolInfo,
    operation: ToolOperation?,
    installEnabled: Boolean,
    showUnavailable: Boolean,
    onInstall: () -> Unit,
    onUpdate: () -> Unit,
    onRemove: () -> Unit,
    onRepair: () -> Unit
) {
    val busy = operation != null && operation.phase != ToolPhase.DONE

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = tool.displayName,
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = tool.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(8.dp))

            if (operation != null) {
                OperationProgress(operation)
            } else {
                ToolStatusText(tool = tool, showUnavailable = showUnavailable)
                if (tool.status == ToolStatus.FAILED && tool.error != null) {
                    Spacer(Modifier.height(4.dp))
                    ExpandableErrorText(message = tool.error)
                }
            }

            if (tool.status != ToolStatus.UPDATING_OR_INSTALLING) {
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    when (tool.status) {
                        ToolStatus.INSTALLED -> {
                            Button(onClick = onUpdate, enabled = !busy) { Text("Update") }
                            OutlinedButton(onClick = onRemove, enabled = !busy) { Text("Remove") }
                            TextButton(onClick = onRepair, enabled = !busy) { Text("Repair") }
                        }
                        ToolStatus.NOT_INSTALLED -> {
                            Button(
                                onClick = onInstall,
                                enabled = !busy && installEnabled
                            ) { Text("Install") }
                        }
                        ToolStatus.FAILED -> {
                            Button(onClick = onInstall, enabled = !busy) { Text("Retry") }
                            OutlinedButton(onClick = onRepair, enabled = !busy) { Text("Repair") }
                        }
                        ToolStatus.UPDATING_OR_INSTALLING -> Unit
                    }
                }
            }
        }
    }
}

/** One-line status summary for a tool that has no operation running. */
@Composable
private fun ToolStatusText(tool: ToolInfo, showUnavailable: Boolean) {
    val text = when (tool.status) {
        ToolStatus.INSTALLED -> buildString {
            append("Installed")
            tool.installedVersion?.let { append(" · v").append(it) }
            if (tool.availableVersion != null && tool.availableVersion != tool.installedVersion) {
                append(" (v").append(tool.availableVersion).append(" available)")
            }
        }
        ToolStatus.NOT_INSTALLED ->
            if (showUnavailable) "Not available for this architecture" else "Not installed"
        ToolStatus.UPDATING_OR_INSTALLING -> "Working…"
        ToolStatus.FAILED -> "Installation failed"
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (tool.status == ToolStatus.FAILED) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
    )
}

/**
 * Live operation state: phase label (with the manager's message when present)
 * and a progress bar — determinate with a percentage when the phase reports
 * progress, indeterminate otherwise.
 */
@Composable
private fun OperationProgress(operation: ToolOperation) {
    Column {
        Text(
            text = buildString {
                append(phaseLabel(operation.phase))
                operation.message?.let { append(" · ").append(it) }
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(6.dp))
        val progress = operation.progress
        if (progress != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "${(progress * 100).roundToInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
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

private fun phaseLabel(phase: ToolPhase): String = when (phase) {
    ToolPhase.RESOLVING -> "Resolving"
    ToolPhase.DOWNLOADING -> "Downloading"
    ToolPhase.VERIFYING -> "Verifying"
    ToolPhase.EXTRACTING -> "Extracting"
    ToolPhase.INSTALLING -> "Installing"
    ToolPhase.DONE -> "Done"
}
