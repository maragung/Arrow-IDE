package com.maragung.arrowide.ui.scm

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.maragung.arrowide.git.GitBranches
import com.maragung.arrowide.git.GitOutcome
import com.maragung.arrowide.git.GitRepository

/**
 * Dialogs and the diff sheet of the Source Control screen (plan #11):
 * branch switcher with branch creation, the stash list, and a monospace
 * unified-diff viewer.
 *
 * Shared with [SourceControlScreen] across files, hence internal visibility.
 */

/** Branch picker: local branches (tap to check out) plus a new-branch row. */
@Composable
internal fun BranchSwitchDialog(
    branches: GitBranches?,
    currentBranch: String?,
    busy: Boolean,
    onDismiss: () -> Unit,
    onCheckout: (String) -> Unit,
    onCreate: (String) -> Unit
) {
    var newBranch by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Branches") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newBranch,
                        onValueChange = { newBranch = it },
                        label = { Text("New branch name") },
                        singleLine = true,
                        enabled = !busy,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    IconButton(
                        onClick = { onCreate(newBranch.trim()) },
                        enabled = !busy && newBranch.isNotBlank()
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = "Create branch")
                    }
                }
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(4.dp))
                val locals = branches?.locals
                when {
                    locals == null -> Text(
                        text = "Loading branches…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    locals.isEmpty() -> Text(
                        text = "No local branches.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    else -> LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                        items(locals, key = { it }) { branch ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = !busy && branch != currentBranch) {
                                        onCheckout(branch)
                                    }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (branch == currentBranch) {
                                    Icon(
                                        imageVector = Icons.Filled.Check,
                                        contentDescription = "Current branch",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                } else {
                                    Spacer(Modifier.size(18.dp))
                                }
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = branch,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

/** Stash list; loads `git stash list` itself while the dialog is open. */
@Composable
internal fun StashListDialog(
    repository: GitRepository,
    onDismiss: () -> Unit
) {
    var stashes by remember { mutableStateOf<List<String>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(repository) {
        when (val outcome = repository.stashList()) {
            is GitOutcome.Ok -> stashes = outcome.value
            is GitOutcome.Error -> error = buildString {
                append("git stash list failed (exit ")
                append(outcome.exitCode)
                append(")")
                outcome.stderr.trim().takeIf { it.isNotBlank() }?.let {
                    append(": ")
                    append(it)
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Stashes") },
        text = {
            val list = stashes
            val errorMessage = error
            when {
                errorMessage != null -> Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                list == null -> Text(
                    text = "Loading…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                list.isEmpty() -> Text(
                    text = "No stashes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                else -> Column(
                    modifier = Modifier
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    list.forEach { stash ->
                        Text(
                            text = stash,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

/**
 * Bottom sheet showing a unified diff in a monospace, selectable, two-axis
 * scrollable view. [diff] is null while the diff is being loaded.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DiffSheet(
    title: String,
    staged: Boolean,
    diff: String?,
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
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (staged) "Staged changes" else "Unstaged changes",
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
            } else {
                SelectionContainer {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 420.dp)
                            .verticalScroll(rememberScrollState())
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        content.lineSequence().forEach { line ->
                            DiffLineText(line)
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

/** One diff line, tinted by its unified-diff prefix (+/-/@@). */
@Composable
private fun DiffLineText(line: String) {
    val colorScheme = MaterialTheme.colorScheme
    val color = when {
        line.startsWith("+") -> colorScheme.tertiary
        line.startsWith("-") -> colorScheme.error
        line.startsWith("@@") -> colorScheme.primary
        else -> colorScheme.onSurface
    }
    Text(
        text = line.ifEmpty { " " },
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        softWrap = false,
        color = color
    )
}
