package com.maragung.arrowide.ui.github

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.maragung.arrowide.github.GitHubResult
import com.maragung.arrowide.github.GithubRepo
import com.maragung.arrowide.github.WorkflowFile
import com.maragung.arrowide.github.WorkflowFileService

/**
 * Workflow file browser (plan #17): pick a repository (same picker as
 * Actions), then list its `.github/workflows` files — name, path and a
 * human-readable size. Tapping a file opens [WorkflowEditorPage].
 *
 * Everything shown is real GitHub API state via [WorkflowFileService]; there
 * is no local/fake content. A 404 from the directory listing means the
 * repository has no `.github/workflows` directory, which reads as an empty
 * list (the service documents that reading); any other failure is surfaced
 * through the shared error card.
 */

/** What [WorkflowEditorPage] edits: the repository plus the tapped file. */
internal data class WorkflowEditorTarget(
    val repo: GithubRepo,
    val file: WorkflowFile
)

/** Workflows landing: pick a repository, then show its workflow files. */
@Composable
internal fun WorkflowsPage(
    ctx: GitHubPageContext,
    workflowFiles: WorkflowFileService,
    selectedRepo: GithubRepo?,
    onSelectRepo: (GithubRepo) -> Unit,
    onBack: () -> Unit,
    onOpenFile: (GithubRepo, WorkflowFile) -> Unit
) {
    var files by remember { mutableStateOf<List<WorkflowFile>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var showPicker by remember { mutableStateOf(false) }

    val repo = selectedRepo

    fun load() {
        val current = repo ?: return
        if (loading) return
        ctx.controller.launchAction("load workflow files") {
            loading = true
            try {
                when (
                    val result = workflowFiles.listWorkflowFiles(
                        owner = current.ownerLogin,
                        repo = current.name
                    )
                ) {
                    is GitHubResult.Ok -> files = result.value
                    is GitHubResult.Error ->
                        if (result.statusCode == 404) {
                            // No .github/workflows directory (or the token
                            // cannot see it): GitHub answers 404, which is
                            // an empty list here, not a failure.
                            files = emptyList()
                        } else {
                            ctx.controller.report("load workflow files", result)
                        }
                }
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(repo?.id) {
        if (repo != null) {
            files = null
            load()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(ctx.snackbarHostState) },
        topBar = {
            GitHubTopBar(
                title = if (repo == null) "Workflow files" else "Workflow files · ${repo.name}",
                onBack = onBack,
                onRefresh = { load() },
                refreshEnabled = repo != null && !loading,
                extraActions = {
                    if (repo != null) {
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(
                                    Icons.Filled.MoreVert,
                                    contentDescription = "Workflows menu"
                                )
                            }
                            DropdownMenu(
                                expanded = menuOpen,
                                onDismissRequest = { menuOpen = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Change repository") },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Filled.FolderOpen,
                                            contentDescription = null
                                        )
                                    },
                                    onClick = {
                                        menuOpen = false
                                        showPicker = true
                                    }
                                )
                            }
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (repo == null) {
            Box(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Description,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Workflow files",
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            text = "Select a repository to view and edit its " +
                                ".github/workflows files.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = { showPicker = true }) {
                            Text("Select repository")
                        }
                    }
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
            ) {
                ctx.error?.let { message ->
                    GitHubErrorCard(
                        message = message,
                        onDismiss = ctx.dismissError,
                        modifier = Modifier.padding(
                            start = 16.dp,
                            top = 8.dp,
                            end = 16.dp
                        )
                    )
                }
                val list = files
                if (list == null) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (list.isEmpty()) {
                            item(key = "empty") {
                                Box(
                                    modifier = Modifier.fillMaxWidth(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    HintText(text = "No workflow files.")
                                }
                            }
                        }
                        items(list, key = { it.path }) { file ->
                            WorkflowFileCard(
                                file = file,
                                onOpen = { onOpenFile(repo, file) }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showPicker) {
        RepoPickerDialog(
            ctx = ctx,
            onDismiss = { showPicker = false },
            onSelect = { picked ->
                showPicker = false
                onSelectRepo(picked)
            }
        )
    }
}

/** One workflow file of the list: name, path and human-readable size. */
@Composable
private fun WorkflowFileCard(
    file: WorkflowFile,
    onOpen: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = file.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${file.path} · ${formatByteSize(file.size)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Human-readable byte size for the workflow file list: "733 B", "1.2 KB",
 * "1 MB" (a whole value drops the decimal). Locale-independent — built from
 * digits, not String.format.
 */
internal fun formatByteSize(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024.0
    var unit = 0
    while (value >= 1024.0 && unit < units.size - 1) {
        value /= 1024.0
        unit++
    }
    return "${oneDecimal(value)} ${units[unit]}"
}

/** At most one decimal, computed without locale-dependent formatting. */
private fun oneDecimal(value: Double): String {
    val tenths = (value * 10.0 + 0.5).toLong()
    val whole = tenths / 10
    val fraction = tenths % 10
    return if (fraction == 0L) whole.toString() else "$whole.$fraction"
}
