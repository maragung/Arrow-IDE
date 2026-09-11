package com.maragung.arrowide.ui.build

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.maragung.arrowide.buildsystem.BuildCommand
import com.maragung.arrowide.buildsystem.BuildSystemKind
import com.maragung.arrowide.buildsystem.DetectedBuildSystem
import com.maragung.arrowide.buildsystem.EnvVar

/**
 * Building blocks of the Build screen (plan #24 + #33 + #22): the empty
 * state, the error card, the one-tap quick actions, per-system command
 * cards and the `.env` environment card with its plain-text warning.
 *
 * Shared with [BuildScreen] across files, hence internal visibility.
 */

/** Empty state shown when no workspace is open. */
@Composable
internal fun EmptyBuildState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Filled.Build,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "Open a project first",
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Open a project to build, run and test it.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Collapsible error card, mirroring the GitHub / Source Control pattern. */
@Composable
internal fun BuildErrorCard(
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
                    text = "Build error",
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

/** Hint card when detection found nothing in the workspace. */
@Composable
internal fun NoBuildSystemCard(modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "No build system detected",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Open a project with a package manifest (package.json, " +
                    "CMakeLists.txt, Makefile, Cargo.toml, go.mod, …), or refresh " +
                    "after adding one.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * The quick action matched for one of Run / Build / Test (plan #33).
 * [commandId] is the stable [BuildCommand.id] the detector assigns; the
 * keywords are a fallback for commands with other ids.
 */
internal enum class QuickAction(
    val label: String,
    val commandId: String,
    val keywords: List<String>
) {
    RUN("run", "run", listOf("run", "start", "dev", "serve")),
    BUILD("build", "build", listOf("build", "compile")),
    TEST("test", "test", listOf("test", "check"))
}

/**
 * The first detected system that provides a command matching [action], with
 * that command — the target of the one-tap Run / Build / Test buttons.
 * A command matches by its stable id first; otherwise a label/command
 * keyword match counts only when no OTHER action's keyword matches (so
 * `npm run build` counts as Build, not Run).
 */
internal fun quickActionTarget(
    systems: List<DetectedBuildSystem>,
    action: QuickAction
): Pair<DetectedBuildSystem, BuildCommand>? {
    for (system in systems) {
        val command = system.commands.firstOrNull { matchesQuickAction(it, action) }
        if (command != null) return system to command
    }
    return null
}

private fun matchesQuickAction(command: BuildCommand, action: QuickAction): Boolean {
    if (command.id == action.commandId) return true
    val label = command.label.lowercase()
    val text = command.command.lowercase()
    val hitsOwn = action.keywords.any { label.contains(it) || text.contains(it) }
    if (!hitsOwn) return false
    val hitsOther = QuickAction.entries
        .filter { it != action }
        .any { other ->
            other.keywords.any { label.contains(it) || text.contains(it) }
        }
    return !hitsOther
}

/**
 * A system's commands are usable when its required tool is installed.
 * Kinds without a required tool (Rust, Go) stay enabled — the compiler may
 * exist in PATH.
 */
internal fun isToolReady(
    system: DetectedBuildSystem,
    toolAvailable: (String) -> Boolean
): Boolean {
    val toolId = system.kind.requiredToolId ?: return true
    return toolAvailable(toolId)
}

/**
 * One-tap actions (plan #33): Run / Build / Test run the first matching
 * command of the detected systems in the terminal; Git Pull / Push / Actions
 * delegate to the coordinator callbacks. Disabled buttons show their reason.
 */
@Composable
internal fun QuickActionsCard(
    systems: List<DetectedBuildSystem>,
    toolAvailable: (String) -> Boolean,
    onRunCommand: (BuildCommand) -> Unit,
    onGitPull: () -> Unit,
    onGitPush: () -> Unit,
    onOpenActions: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Quick actions",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            val first = systems.firstOrNull()
            Text(
                text = if (first == null) {
                    "No build system detected"
                } else {
                    "${first.kind.displayName} · ${first.detail}"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuickActionCell(
                    label = "Run",
                    target = quickActionTarget(systems, QuickAction.RUN),
                    toolAvailable = toolAvailable,
                    modifier = Modifier.weight(1f),
                    onClick = onRunCommand
                )
                QuickActionCell(
                    label = "Build",
                    target = quickActionTarget(systems, QuickAction.BUILD),
                    toolAvailable = toolAvailable,
                    modifier = Modifier.weight(1f),
                    onClick = onRunCommand
                )
                QuickActionCell(
                    label = "Test",
                    target = quickActionTarget(systems, QuickAction.TEST),
                    toolAvailable = toolAvailable,
                    modifier = Modifier.weight(1f),
                    onClick = onRunCommand
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onGitPull,
                    modifier = Modifier.weight(1f)
                ) { Text("Git Pull") }
                OutlinedButton(
                    onClick = onGitPush,
                    modifier = Modifier.weight(1f)
                ) { Text("Git Push") }
                OutlinedButton(
                    onClick = onOpenActions,
                    modifier = Modifier.weight(1f)
                ) { Text("Actions") }
            }
        }
    }
}

/** One Run / Build / Test cell: the button plus its disabled reason. */
@Composable
private fun QuickActionCell(
    label: String,
    target: Pair<DetectedBuildSystem, BuildCommand>?,
    toolAvailable: (String) -> Boolean,
    modifier: Modifier = Modifier,
    onClick: (BuildCommand) -> Unit
) {
    val toolReady = target != null && isToolReady(target.first, toolAvailable)
    val reason = when {
        target == null -> "No ${label.lowercase()} command detected"
        !toolReady -> "Install ${target.first.kind.displayName} from Tools"
        else -> null
    }
    Column(modifier = modifier) {
        Button(
            onClick = { target?.let { onClick(it.second) } },
            enabled = toolReady,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(label)
        }
        if (!toolReady && reason != null) {
            Text(
                text = reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2
            )
        }
    }
}

/** One detected build system (plan #24): kind badge, detail, command buttons. */
@Composable
internal fun BuildSystemCard(
    system: DetectedBuildSystem,
    toolAvailable: (String) -> Boolean,
    onRunCommand: (BuildCommand) -> Unit
) {
    val toolReady = isToolReady(system, toolAvailable)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                KindBadge(kind = system.kind)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = system.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
            if (system.commands.isEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "No commands detected for this system.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            system.commands.forEach { command ->
                Spacer(Modifier.height(12.dp))
                CommandRow(
                    command = command,
                    enabled = toolReady,
                    onRun = { onRunCommand(command) }
                )
            }
            if (!toolReady) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Install ${system.kind.displayName} from Tools",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

/** One command of a system: run button, monospace command text, description. */
@Composable
private fun CommandRow(
    command: BuildCommand,
    enabled: Boolean,
    onRun: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onRun, enabled = enabled) {
                Text(command.label)
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text = command.command,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
        command.description?.let { description ->
            Spacer(Modifier.height(2.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Small rounded badge with the build system kind's display name. */
@Composable
private fun KindBadge(kind: BuildSystemKind) {
    Text(
        text = kind.displayName,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(6.dp)
            )
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

/**
 * Environment card (plan #22): the workspace `.env` variables. Sensitive
 * values are masked; tapping the eye reveals them for this session only
 * (cleared on every reload). "Edit .env" opens the editing dialog.
 */
@Composable
internal fun EnvironmentCard(
    envVars: List<EnvVar>?,
    revealed: Map<String, Boolean>,
    onToggleReveal: (String) -> Unit,
    onEdit: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Environment",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = if (envVars == null) {
                            "No .env file in this project"
                        } else {
                            "${envVars.size} variables from .env"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedButton(onClick = onEdit) {
                    Text(if (envVars == null) "Create .env" else "Edit .env")
                }
            }
            if (!envVars.isNullOrEmpty()) {
                Spacer(Modifier.height(8.dp))
                envVars.forEach { variable ->
                    EnvVarRow(
                        variable = variable,
                        revealed = revealed[variable.name] == true,
                        onToggle = { onToggleReveal(variable.name) }
                    )
                }
            }
        }
    }
}

/** One `.env` variable: monospace name, masked-or-revealed value, eye toggle. */
@Composable
private fun EnvVarRow(
    variable: EnvVar,
    revealed: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = variable.name,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = when {
                !variable.sensitive -> variable.value
                revealed -> variable.value
                else -> "••••••••"
            },
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (variable.sensitive) {
            IconButton(onClick = onToggle, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = if (revealed) {
                        Icons.Filled.VisibilityOff
                    } else {
                        Icons.Filled.Visibility
                    },
                    contentDescription = if (revealed) {
                        "Hide value"
                    } else {
                        "Show value"
                    },
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Warning card (plan #22): names of the `.env` values that look like
 * secrets. Names only — values are never repeated here.
 */
@Composable
internal fun EnvSecretsWarningCard(
    names: List<String>,
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Plain-text secrets detected",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = "The .env file stores ${names.joinToString(", ")} in " +
                    "plain text inside the project. Anything committed to Git " +
                    "stays visible in the repository history. Consider keeping " +
                    "these values in Settings → Secrets instead.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
