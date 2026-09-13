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
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.maragung.arrowide.packages.AptCommandShim
import com.maragung.arrowide.toolchain.ToolCatalog
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
 *
 * When [aptShim] is provided, an apt queue section (plan #4/#5 userspace
 * parity) shows the commands queued by the apt/apt-get shims and applies
 * them through the toolchain layer; anything it cannot apply honestly is
 * requeued, never silently dropped.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsScreen(
    manager: ToolchainManager,
    aptShim: AptCommandShim? = null,
    modifier: Modifier = Modifier
) {
    val tools by manager.tools.collectAsStateWithLifecycle()
    val operations by manager.operations.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var removing by remember { mutableStateOf<ToolInfo?>(null) }

    // apt queue (userspace parity): commands queued by the apt/apt-get
    // shims, peeked non-destructively until the user applies them.
    var aptQueue by remember { mutableStateOf<List<String>>(emptyList()) }
    var aptApplying by remember { mutableStateOf(false) }
    var aptResults by remember { mutableStateOf<List<AptQueueResult>>(emptyList()) }

    suspend fun refreshAptQueue() {
        val shim = aptShim ?: return
        aptQueue = try {
            shim.peekCommands()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
    }

    // Refresh tool statuses whenever the screen is entered.
    LaunchedEffect(Unit) {
        try {
            manager.refresh()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Failures are surfaced through the tools StateFlow by the manager.
        }
        refreshAptQueue()
    }

    /**
     * Drains the apt queue and applies every line it can honestly apply
     * (install → toolchain installs, update → catalog refresh). Lines that
     * were not applied — unsupported verbs, unknown packages, failed
     * installs — are requeued so nothing is silently lost.
     */
    suspend fun applyAptQueue() {
        val shim = aptShim ?: return
        aptApplying = true
        try {
            val lines = shim.queuedCommands()
            val results = mutableListOf<AptQueueResult>()
            val requeue = mutableListOf<String>()
            for (line in lines) {
                when (val action = parseAptQueueLine(line)) {
                    is AptQueueAction.Update -> {
                        manager.refresh()
                        results += AptQueueResult(
                            line = line,
                            applied = true,
                            detail = "Catalog refreshed"
                        )
                    }
                    is AptQueueAction.Install -> {
                        val perPackage = mutableListOf<String>()
                        var allApplied = true
                        for (packageName in action.packageNames) {
                            val toolId = aptPackageToToolId(packageName)
                            if (toolId == null) {
                                perPackage += "unknown package '$packageName'"
                                allApplied = false
                                continue
                            }
                            try {
                                manager.install(toolId)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                perPackage += "'$packageName': ${e.message ?: "install failed"}"
                                allApplied = false
                                continue
                            }
                            val tool = manager.tools.value.firstOrNull { it.id == toolId }
                            if (tool?.status == ToolStatus.FAILED) {
                                perPackage += "'$packageName': " +
                                    (tool.error ?: "installation failed")
                                allApplied = false
                            } else {
                                perPackage += (tool?.displayName ?: packageName) +
                                    " installing/installed"
                            }
                        }
                        results += AptQueueResult(
                            line = line,
                            applied = allApplied,
                            detail = perPackage.joinToString("; ")
                        )
                        if (!allApplied) {
                            requeue += line
                        }
                    }
                    null -> {
                        results += AptQueueResult(
                            line = line,
                            applied = false,
                            detail = aptSkipReason(line)
                        )
                        requeue += line
                    }
                }
            }
            if (requeue.isNotEmpty()) {
                shim.requeue(requeue)
            }
            aptResults = results
            refreshAptQueue()
        } finally {
            aptApplying = false
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
                if (aptShim != null) {
                    item {
                        AptQueueCard(
                            queue = aptQueue,
                            results = aptResults,
                            applying = aptApplying,
                            onApply = { launchToolAction { applyAptQueue() } }
                        )
                    }
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

/**
 * apt queue section: the command lines queued by the apt/apt-get shims
 * (or the honest "Queue empty" state), an "Apply queue" button, and the
 * result of the last apply — which commands were applied (and what each
 * package resolved to) and which were skipped, with the reason.
 */
@Composable
private fun AptQueueCard(
    queue: List<String>,
    results: List<AptQueueResult>,
    applying: Boolean,
    onApply: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "apt queue",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "Commands run through the apt / apt-get shims are " +
                    "queued here; applying them performs the real install " +
                    "or catalog refresh in the toolchain layer.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))

            if (queue.isEmpty()) {
                Text(
                    text = "Queue empty",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                queue.forEach { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace
                        )
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onApply,
                enabled = !applying && queue.isNotEmpty()
            ) { Text("Apply queue") }
            if (applying) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            if (results.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                results.forEach { result ->
                    Text(
                        text = result.line,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace
                        )
                    )
                    Text(
                        text = if (result.applied) {
                            "Applied: ${result.detail}"
                        } else {
                            "Skipped: ${result.detail}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (result.applied) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        }
                    )
                    Spacer(Modifier.height(6.dp))
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

/**
 * A queued apt command line this app can honestly apply through the
 * toolchain layer. Anything that is not one of these is skipped and
 * requeued ([aptSkipReason] explains why).
 */
internal sealed interface AptQueueAction {
    /** `apt install X Y…` — [packageNames] are apt package names. */
    data class Install(val packageNames: List<String>) : AptQueueAction

    /** `apt update` — the honest equivalent is a catalog refresh. */
    data object Update : AptQueueAction
}

/** Outcome of applying (or skipping) one queued apt command line. */
internal data class AptQueueResult(
    val line: String,
    val applied: Boolean,
    val detail: String,
)

/** Verbs the toolchain layer intentionally does not implement. */
private val UNSUPPORTED_APT_VERBES = setOf(
    "remove", "purge", "autoremove", "upgrade", "full-upgrade",
    "search", "show", "list", "edit-sources",
)

/**
 * Parses one queued apt command line. Accepts `apt`/`apt-get` with the
 * `install` and `update` verbs plus the common `-y` / `--yes` flags;
 * returns null for anything the app cannot honestly apply.
 */
internal fun parseAptQueueLine(line: String): AptQueueAction? {
    val tokens = line.trim().split(WHITESPACE).filter { it.isNotEmpty() }
    if (tokens.isEmpty()) {
        return null
    }
    val program = tokens[0]
    if (program != "apt" && program != "apt-get") {
        return null
    }
    // Leading global options: only the yes flags are accepted.
    var i = 1
    while (i < tokens.size && tokens[i].startsWith("-")) {
        if (tokens[i] != "-y" && tokens[i] != "--yes") {
            return null
        }
        i++
    }
    if (i >= tokens.size) {
        return null
    }
    val verb = tokens[i]
    val operands = tokens.drop(i + 1)
    val unsupportedOptions = operands.filter { it.startsWith("-") && it != "-y" && it != "--yes" }
    val args = operands.filterNot { it.startsWith("-") }
    if (unsupportedOptions.isNotEmpty()) {
        return null
    }
    return when {
        verb == "update" && args.isEmpty() -> AptQueueAction.Update
        verb == "install" && args.isNotEmpty() -> AptQueueAction.Install(args)
        else -> null
    }
}

/**
 * Maps an apt package name to a [com.maragung.arrowide.toolchain.ToolCatalog]
 * id, resolving the obvious aliases; null when no catalog tool matches.
 */
internal fun aptPackageToToolId(packageName: String): String? {
    val id = when (packageName) {
        "node", "nodejs" -> "nodejs"
        "python", "python3" -> "python"
        else -> packageName
    }
    return if (ToolCatalog.byId(id) != null) id else null
}

/** Human-readable reason a queued apt command line was not applied. */
internal fun aptSkipReason(line: String): String {
    val tokens = line.trim().split(WHITESPACE).filter { it.isNotEmpty() }
    if (tokens.isEmpty()) {
        return "Empty command"
    }
    val verb = tokens.getOrNull(1)
    return when {
        verb == null -> "'${tokens[0]}' needs a command (e.g. 'apt install <package>')"
        verb == "install" && tokens.size == 2 -> "'install' needs at least one package name"
        verb == "install" -> "Unsupported option in '${tokens[0]} install'"
        verb in UNSUPPORTED_APT_VERBES -> "'$verb' is not supported"
        else -> "Unknown command '$verb'"
    }
}

private val WHITESPACE = Regex("\\s+")
