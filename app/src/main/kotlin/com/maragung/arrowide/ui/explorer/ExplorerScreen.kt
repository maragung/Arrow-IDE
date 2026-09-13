package com.maragung.arrowide.ui.explorer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.maragung.arrowide.archive.isArchiveName
import com.maragung.arrowide.workspace.FileEntry
import com.maragung.arrowide.workspace.WorkspaceManager
import java.io.File

/**
 * File explorer for the current workspace: an expandable tree with lazy
 * per-directory loading, indent guides, type-based icons, and
 * create / rename / delete actions (all confirmed through dialogs).
 * Shows an empty state when no workspace is open.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExplorerScreen(
    workspaceManager: WorkspaceManager,
    onOpenFile: (File) -> Unit,
    modifier: Modifier = Modifier,
    onOpenArchive: (File) -> Unit = {},
) {
    val viewModel: ExplorerViewModel = viewModel { ExplorerViewModel(workspaceManager) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    var dialog by remember { mutableStateOf<ExplorerDialog>(ExplorerDialog.None) }

    LaunchedEffect(state.error) {
        state.error?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearError()
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(state.workspace?.name ?: "Explorer") },
                actions = {
                    if (state.workspace != null) {
                        IconButton(onClick = { dialog = ExplorerDialog.NewFile("") }) {
                            Icon(Icons.Filled.NoteAdd, contentDescription = "New file")
                        }
                        IconButton(onClick = { dialog = ExplorerDialog.NewFolder("") }) {
                            Icon(Icons.Filled.CreateNewFolder, contentDescription = "New folder")
                        }
                        IconButton(onClick = { viewModel.refresh() }) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                        }
                    }
                }
            )
        }
    ) { padding ->
        val workspace = state.workspace
        if (workspace == null) {
            EmptyExplorerState(modifier = Modifier.padding(padding))
        } else {
            LazyColumn(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
            ) {
                if (state.rows.isEmpty()) {
                    item {
                        Text(
                            text = "This workspace is empty. Create a file or folder to get started.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
                items(state.rows, key = { it.entry.path }) { row ->
                    FileTreeRow(
                        row = row,
                        onToggle = { viewModel.toggle(row.entry.path) },
                        onOpenFile = { entry ->
                            onOpenFile(File(workspace, entry.path))
                        },
                        onNewFile = { parentPath ->
                            dialog = ExplorerDialog.NewFile(parentPath)
                        },
                        onNewFolder = { parentPath ->
                            dialog = ExplorerDialog.NewFolder(parentPath)
                        },
                        onRename = { dialog = ExplorerDialog.Rename(row.entry.path, row.entry.name) },
                        onDelete = {
                            dialog = ExplorerDialog.Delete(
                                path = row.entry.path,
                                name = row.entry.name,
                                isDirectory = row.entry.isDirectory
                            )
                        },
                        onOpenArchive = { entry ->
                            workspace?.let { ws -> onOpenArchive(File(ws, entry.path)) }
                        }
                    )
                }
            }
        }
    }

    when (val current = dialog) {
        ExplorerDialog.None -> Unit
        is ExplorerDialog.NewFile -> NameInputDialog(
            title = "New file",
            label = "File name",
            confirmText = "Create",
            initialName = "",
            onDismiss = { dialog = ExplorerDialog.None },
            onConfirm = { name ->
                viewModel.createFile(current.parentPath, name)
                dialog = ExplorerDialog.None
            }
        )
        is ExplorerDialog.NewFolder -> NameInputDialog(
            title = "New folder",
            label = "Folder name",
            confirmText = "Create",
            initialName = "",
            onDismiss = { dialog = ExplorerDialog.None },
            onConfirm = { name ->
                viewModel.createFolder(current.parentPath, name)
                dialog = ExplorerDialog.None
            }
        )
        is ExplorerDialog.Rename -> NameInputDialog(
            title = "Rename",
            label = "New name",
            confirmText = "Rename",
            initialName = current.currentName,
            onDismiss = { dialog = ExplorerDialog.None },
            onConfirm = { name ->
                viewModel.rename(current.path, name)
                dialog = ExplorerDialog.None
            }
        )
        is ExplorerDialog.Delete -> AlertDialog(
            onDismissRequest = { dialog = ExplorerDialog.None },
            title = { Text("Delete ${current.name}?") },
            text = {
                Text(
                    if (current.isDirectory) {
                        "Delete \"${current.name}\" and everything inside it? This cannot be undone."
                    } else {
                        "Delete \"${current.name}\"? This cannot be undone."
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(current.path)
                    dialog = ExplorerDialog.None
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { dialog = ExplorerDialog.None }) { Text("Cancel") }
            }
        )
    }
}

/** Dialog state for the explorer's create / rename / delete flows. */
private sealed interface ExplorerDialog {
    data object None : ExplorerDialog
    data class NewFile(val parentPath: String) : ExplorerDialog
    data class NewFolder(val parentPath: String) : ExplorerDialog
    data class Rename(val path: String, val currentName: String) : ExplorerDialog
    data class Delete(val path: String, val name: String, val isDirectory: Boolean) : ExplorerDialog
}

@Composable
private fun FileTreeRow(
    row: ExplorerViewModel.TreeRow,
    onToggle: () -> Unit,
    onOpenFile: (FileEntry) -> Unit,
    onNewFile: (String) -> Unit,
    onNewFolder: (String) -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onOpenArchive: (FileEntry) -> Unit = {},
) {
    val entry = row.entry
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clickable { if (entry.isDirectory) onToggle() else onOpenFile(entry) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Indent guides, one per nesting level.
        repeat(row.depth) {
            Box(
                modifier = Modifier
                    .padding(start = 10.dp)
                    .width(1.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )
        }
        Spacer(Modifier.width(10.dp))
        Icon(
            imageVector = iconFor(entry, row.expanded),
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = if (entry.isDirectory) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
        Spacer(Modifier.width(10.dp))
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 6.dp)
        ) {
            Text(
                text = entry.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = if (entry.isDirectory) "Directory" else formatSize(entry.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        RowActions(
            entry = entry,
            onNewFile = onNewFile,
            onNewFolder = onNewFolder,
            onRename = onRename,
            onDelete = onDelete,
            onOpenArchive = onOpenArchive
        )
        Spacer(Modifier.width(8.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RowActions(
    entry: FileEntry,
    onNewFile: (String) -> Unit,
    onNewFolder: (String) -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onOpenArchive: (FileEntry) -> Unit = {},
) {
    Box {
        var menuOpen by remember { mutableStateOf(false) }
        IconButton(
            onClick = { menuOpen = true },
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                Icons.Filled.MoreVert,
                contentDescription = "Actions for ${entry.name}",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false }
        ) {
            if (entry.isDirectory) {
                DropdownMenuItem(
                    text = { Text("New file") },
                    leadingIcon = { Icon(Icons.Filled.NoteAdd, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onNewFile(entry.path)
                    }
                )
                DropdownMenuItem(
                    text = { Text("New folder") },
                    leadingIcon = { Icon(Icons.Filled.CreateNewFolder, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onNewFolder(entry.path)
                    }
                )
            }
            if (!entry.isDirectory && isArchiveName(entry.name)) {
                DropdownMenuItem(
                    text = { Text("View archive") },
                    leadingIcon = { Icon(Icons.Filled.FolderOpen, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onOpenArchive(entry)
                    }
                )
            }
            DropdownMenuItem(
                text = { Text("Rename") },
                leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                onClick = {
                    menuOpen = false
                    onRename()
                }
            )
            DropdownMenuItem(
                text = { Text("Delete") },
                leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                onClick = {
                    menuOpen = false
                    onDelete()
                }
            )
        }
    }
}

@Composable
private fun EmptyExplorerState(modifier: Modifier = Modifier) {
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
            text = "Open or create a project from the Home screen to browse its files.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NameInputDialog(
    title: String,
    label: String,
    confirmText: String,
    initialName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(label) },
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim()) },
                enabled = name.isNotBlank()
            ) { Text(confirmText) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

private fun iconFor(entry: FileEntry, expanded: Boolean): ImageVector = when {
    entry.isDirectory -> if (expanded) Icons.Filled.FolderOpen else Icons.Filled.Folder
    else -> when (entry.name.substringAfterLast('.', "").lowercase()) {
        "kt", "kts", "java", "py", "c", "cpp", "cc", "h", "hpp",
        "js", "ts", "jsx", "tsx", "sh", "bash", "rs", "go", "rb", "php", "gradle" -> Icons.Filled.Code
        "json", "xml", "yml", "yaml", "toml", "properties", "ini", "cfg", "conf" -> Icons.Filled.DataObject
        "md", "markdown", "txt", "log" -> Icons.Filled.Description
        else -> Icons.Filled.InsertDriveFile
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> "%.1f KB".format(java.util.Locale.US, bytes / 1024f)
    else -> "%.1f MB".format(java.util.Locale.US, bytes / (1024f * 1024f))
}
