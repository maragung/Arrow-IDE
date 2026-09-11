package com.maragung.arrowide.ui.templates

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.maragung.arrowide.templates.ProjectTemplate
import com.maragung.arrowide.templates.WorkflowTemplate
import com.maragung.arrowide.templates.WorkflowTemplates
import java.io.File

/**
 * Building blocks of the Templates screen (plan #47 + #48): the error card,
 * the no-workspace card, name validation helpers, and the project/workflow
 * template cards with their expanding previews.
 *
 * Shared with [TemplatesScreen] across files, hence internal visibility.
 */

/** Collapsible error card, mirroring the GitHub / Source Control pattern. */
@Composable
internal fun TemplatesErrorCard(
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
                    text = "Templates error",
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

/** Info card shown on the Workflows tab without an open workspace. */
@Composable
internal fun NoWorkspaceCard(modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "No project open",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Open a project to add workflows to it.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Human label for a tool id — matches the Tools catalog display names;
 * unknown ids fall back to the raw id.
 */
internal fun toolDisplayName(toolId: String): String = when (toolId) {
    "nodejs" -> "Node.js"
    "python" -> "Python 3"
    "git" -> "Git"
    "clang" -> "Clang / LLVM"
    "cmake" -> "CMake"
    "make" -> "GNU Make"
    else -> toolId
}

/**
 * Validation for new project names (plan #47): non-blank, single safe path
 * segment of letters/digits/dots/underscores/dashes, not already taken
 * under [projectsDir]. Returns null when the name is valid.
 */
internal fun projectNameError(raw: String, projectsDir: File): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return "Enter a project name"
    if (!trimmed.matches(PROJECT_NAME_REGEX)) {
        return "Use letters, digits, dots, underscores and dashes only"
    }
    if (trimmed == "." || trimmed == "..") return "Invalid name"
    if (File(projectsDir, trimmed).exists()) {
        return "\"$trimmed\" already exists under ${projectsDir.name}"
    }
    return null
}

private val PROJECT_NAME_REGEX = Regex("[A-Za-z0-9._-]+")

/**
 * Validation for workflow file names (plan #48): non-blank, single path
 * segment, ending in .yml or .yaml. Returns null when valid.
 */
internal fun workflowFileNameError(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return "Enter a file name"
    if ('/' in trimmed || '\\' in trimmed) {
        return "File name must not contain path separators"
    }
    if (trimmed == "." || trimmed == "..") return "Invalid file name"
    if (!trimmed.endsWith(".yml") && !trimmed.endsWith(".yaml")) {
        return "File name must end in .yml or .yaml"
    }
    return null
}

/** Shared text field for the new project's name. */
@Composable
internal fun TemplateNameField(
    name: String,
    onNameChange: (String) -> Unit,
    enabled: Boolean,
    problem: String?
) {
    OutlinedTextField(
        value = name,
        onValueChange = onNameChange,
        label = { Text("Project name") },
        singleLine = true,
        enabled = enabled,
        isError = problem != null,
        supportingText = { problem?.let { Text(it) } },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        modifier = Modifier.fillMaxWidth()
    )
}

/**
 * One project template card (plan #47): name, description, file count and
 * the required tool's availability. Expanding lists the template's files;
 * Create is disabled while the tool is missing or the name is invalid, each
 * with its helper text.
 */
@Composable
internal fun ProjectTemplateCard(
    template: ProjectTemplate,
    toolAvailable: (String) -> Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    nameValid: Boolean,
    creating: Boolean,
    onCreate: () -> Unit
) {
    val requiredToolId = template.requiredToolId
    val toolMissing = requiredToolId != null && !toolAvailable(requiredToolId)
    val toolLabel = when {
        requiredToolId == null -> "No tool required"
        toolMissing -> "Requires ${toolDisplayName(requiredToolId)} — not installed"
        else -> "Requires ${toolDisplayName(requiredToolId)}"
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = template.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = template.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (requiredToolId != null) {
                            Icon(
                                imageVector = if (toolMissing) {
                                    Icons.Filled.Warning
                                } else {
                                    Icons.Filled.CheckCircle
                                },
                                contentDescription = null,
                                tint = if (toolMissing) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.tertiary
                                },
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                        }
                        Text(
                            text = "${template.files.size} files · $toolLabel",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Icon(
                    imageVector = if (expanded) {
                        Icons.Filled.ExpandLess
                    } else {
                        Icons.Filled.ExpandMore
                    },
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (expanded) {
                Spacer(Modifier.height(8.dp))
                template.files.forEach { file ->
                    Text(
                        text = file.relativePath,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.height(12.dp))
                if (toolMissing) {
                    Text(
                        text = "requires ${toolDisplayName(requiredToolId)} installed",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(4.dp))
                } else if (!nameValid) {
                    Text(
                        text = "Enter a valid project name above",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                }
                Button(onClick = onCreate, enabled = !creating && !toolMissing && nameValid) {
                    if (creating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("Create project")
                }
            }
        }
    }
}

/**
 * One workflow template card (plan #48): name, description, and — expanded —
 * the target file name (defaulting to the template's), a read-only
 * monospace preview of the generated YAML, and the Add button. Existing
 * files are never overwritten; that case surfaces as an error card.
 */
@Composable
internal fun WorkflowTemplateCard(
    template: WorkflowTemplate,
    expanded: Boolean,
    adding: Boolean,
    onToggle: () -> Unit,
    onAdd: (fileName: String) -> Unit
) {
    var fileName by remember(template.id) { mutableStateOf(template.defaultFileName) }
    val problem = workflowFileNameError(fileName)
    val preview = remember(template.id) { WorkflowTemplates.generate(template) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = template.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = template.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = ".github/workflows/${template.defaultFileName}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Icon(
                    imageVector = if (expanded) {
                        Icons.Filled.ExpandLess
                    } else {
                        Icons.Filled.ExpandMore
                    },
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (expanded) {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = fileName,
                    onValueChange = { fileName = it },
                    label = { Text("File name") },
                    singleLine = true,
                    enabled = !adding,
                    isError = problem != null,
                    supportingText = { problem?.let { Text(it) } },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Preview",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = preview,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp)
                        .background(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(8.dp)
                        )
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp)
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { onAdd(fileName) },
                    enabled = !adding && problem == null
                ) {
                    if (adding) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("Add workflow")
                }
            }
        }
    }
}
