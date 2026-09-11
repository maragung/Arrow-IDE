package com.maragung.arrowide.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.maragung.arrowide.workspace.ProjectInfo
import com.maragung.arrowide.workspace.WorkspaceManager
import kotlinx.coroutines.launch
import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * Home screen: current-workspace banner, project list, and project actions
 * (create / open / delete-with-confirmation) plus quick actions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    workspaceManager: WorkspaceManager,
    onWorkspaceOpened: () -> Unit,
    onOpenTerminal: () -> Unit,
    modifier: Modifier = Modifier,
    onNewFile: () -> Unit = {}
) {
    val viewModel: HomeViewModel = viewModel { HomeViewModel(workspaceManager) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var showCreateDialog by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<ProjectInfo?>(null) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Arrow IDE") },
                actions = {
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh projects")
                    }
                    IconButton(onClick = { showCreateDialog = true }) {
                        Icon(Icons.Filled.Add, contentDescription = "New project")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            WorkspaceBanner(
                workspace = state.currentWorkspace,
                onOpenTerminal = onOpenTerminal,
                onNewFile = onNewFile,
                onClose = { viewModel.closeWorkspace() }
            )

            if (state.error != null && !showCreateDialog) {
                Text(
                    text = state.error ?: "",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Text(
                text = "Projects",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )

            if (state.projects.isEmpty() && !state.loading) {
                Text(
                    text = "No projects yet. Create one to get started.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            state.projects.forEach { project ->
                ProjectRow(
                    project = project,
                    onOpen = {
                        scope.launch {
                            if (viewModel.openProject(project.name)) {
                                onWorkspaceOpened()
                            }
                        }
                    },
                    onDelete = { deleting = project }
                )
            }
        }
    }

    if (showCreateDialog) {
        CreateProjectDialog(
            error = state.error,
            onDismiss = {
                showCreateDialog = false
                viewModel.clearError()
            },
            onConfirm = { name ->
                scope.launch {
                    if (viewModel.createProject(name)) {
                        showCreateDialog = false
                        viewModel.clearError()
                    }
                }
            }
        )
    }

    deleting?.let { project ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete project?") },
            text = {
                Text("Delete \"${project.name}\" and all of its files? This cannot be undone.")
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteProject(project.name)
                    deleting = null
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun WorkspaceBanner(
    workspace: File?,
    onOpenTerminal: () -> Unit,
    onNewFile: () -> Unit,
    onClose: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Workspace",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            if (workspace != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = workspace.name,
                    style = MaterialTheme.typography.titleLarge
                )
                Text(
                    text = workspace.absolutePath,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onOpenTerminal) {
                        Icon(Icons.Filled.Terminal, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("New terminal here")
                    }
                    OutlinedButton(onClick = onNewFile) {
                        Icon(Icons.Filled.NoteAdd, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("New file")
                    }
                }
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = onClose) { Text("Close workspace") }
            } else {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "No workspace open",
                    style = MaterialTheme.typography.titleLarge
                )
                Text(
                    text = "Open or create a project below to start working.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ProjectRow(
    project: ProjectInfo,
    onOpen: () -> Unit,
    onDelete: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpen)
                .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp)
            ) {
                Text(
                    text = project.name,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "Modified ${formatDate(project.lastModified)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "Delete ${project.name}",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateProjectDialog(
    error: String?,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New project") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Project name") },
                    singleLine = true
                )
                if (error != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(name) },
                enabled = name.isNotBlank()
            ) { Text("Create") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

private fun formatDate(millis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis))
