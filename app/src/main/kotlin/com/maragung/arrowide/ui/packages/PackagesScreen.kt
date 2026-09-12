package com.maragung.arrowide.ui.packages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.maragung.arrowide.packages.ManagedPackage
import com.maragung.arrowide.packages.PackageCommand
import com.maragung.arrowide.packages.PackageEcosystem
import com.maragung.arrowide.packages.ProjectPackageManager
import java.io.File

/**
 * Package Manager UI (plan #29): declared dependencies of the open
 * project (package.json / requirements.txt) with add / update / remove
 * actions. Every action ends in a REAL package-manager command shown for
 * confirmation and executed in a real terminal session via
 * [onRunInTerminal] — nothing is simulated (plan #49).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PackagesScreen(
    workspace: File?,
    packageManager: ProjectPackageManager,
    onRunInTerminal: (String, File) -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    var packages by remember { mutableStateOf<List<ManagedPackage>?>(null) }
    var ecosystems by remember { mutableStateOf<List<PackageEcosystem>>(emptyList()) }
    var refreshKey by remember { mutableStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    var addDialog by remember { mutableStateOf(false) }
    var pendingCommand by remember { mutableStateOf<PackageCommand?>(null) }

    LaunchedEffect(workspace, refreshKey) {
        packages = null
        ecosystems = workspace?.let(packageManager::detect).orEmpty()
        packages = workspace?.let { packageManager.listPackages(it) } ?: emptyList()
    }

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            message = null
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Packages") },
                actions = {
                    if (workspace != null && ecosystems.isNotEmpty()) {
                        IconButton(onClick = { addDialog = true }) {
                            Icon(Icons.Filled.Add, contentDescription = "Add package")
                        }
                    }
                    IconButton(onClick = { refreshKey++ }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh packages")
                    }
                },
            )
        },
    ) { padding ->
        when {
            workspace == null -> Empty(padding, "Open a project first.")
            packages == null -> Loading(padding)
            ecosystems.isEmpty() -> Empty(
                padding,
                "No package manifest found (package.json or requirements.txt).",
            )
            else -> PackageList(
                padding = padding,
                packages = packages.orEmpty(),
                onUpdate = { pkg ->
                    packageManager.updateCommand(pkg.ecosystem, pkg.name, workspace)?.let {
                        pendingCommand = it
                    } ?: run { message = missingToolMessage(pkg.ecosystem) }
                },
                onRemove = { pkg ->
                    packageManager.removeCommand(pkg.ecosystem, pkg.name, workspace)?.let {
                        pendingCommand = it
                    } ?: run { message = missingToolMessage(pkg.ecosystem) }
                },
            )
        }
    }

    if (addDialog && workspace != null) {
        val ws = workspace
        AddPackageDialog(
            ecosystems = ecosystems,
            onDismiss = { addDialog = false },
            onConfirm = { ecosystem, name ->
                addDialog = false
                packageManager.installCommand(ecosystem, name, ws)?.let {
                    pendingCommand = it
                } ?: run { message = missingToolMessage(ecosystem) }
            },
        )
    }

    pendingCommand?.let { command ->
        val ws = workspace ?: return@let
        ConfirmCommandDialog(
            command = command,
            onDismiss = { pendingCommand = null },
            onRun = {
                pendingCommand = null
                onRunInTerminal(command.command, ws)
            },
        )
    }
}

private fun missingToolMessage(ecosystem: PackageEcosystem): String = when (ecosystem) {
    PackageEcosystem.NODE -> "Install Node.js in Tools first"
    PackageEcosystem.PYTHON -> "Install Python in Tools first"
}

@Composable
private fun Loading(padding: androidx.compose.foundation.layout.PaddingValues) {
    Box(
        modifier = Modifier
            .padding(padding)
            .fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun Empty(padding: androidx.compose.foundation.layout.PaddingValues, text: String) {
    Box(
        modifier = Modifier
            .padding(padding)
            .fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PackageList(
    padding: androidx.compose.foundation.layout.PaddingValues,
    packages: List<ManagedPackage>,
    onUpdate: (ManagedPackage) -> Unit,
    onRemove: (ManagedPackage) -> Unit,
) {
    val grouped = packages.groupBy { it.ecosystem }
    LazyColumn(
        modifier = Modifier
            .padding(padding)
            .fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(16.dp),
    ) {
        grouped.forEach { (ecosystem, list) ->
            item(key = "header-$ecosystem") {
                Text(
                    text = ecosystemLabel(ecosystem),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            items(list, key = { "${it.ecosystem}-${it.name}" }) { pkg ->
                PackageRow(pkg = pkg, onUpdate = onUpdate, onRemove = onRemove)
            }
        }
    }
}

@Composable
private fun PackageRow(
    pkg: ManagedPackage,
    onUpdate: (ManagedPackage) -> Unit,
    onRemove: (ManagedPackage) -> Unit,
) {
    androidx.compose.material3.Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = pkg.name + if (pkg.isDev) "  (dev)" else "",
                    style = MaterialTheme.typography.bodyLarge,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = pkg.version ?: "—",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { onUpdate(pkg) }) { Text("Update") }
            TextButton(onClick = { onRemove(pkg) }) { Text("Remove") }
        }
    }
}

@Composable
private fun AddPackageDialog(
    ecosystems: List<PackageEcosystem>,
    onDismiss: () -> Unit,
    onConfirm: (PackageEcosystem, String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var ecosystem by remember { mutableStateOf(ecosystems.first()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add package") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (ecosystems.size > 1) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ecosystems.forEach { candidate ->
                            androidx.compose.material3.FilterChip(
                                selected = ecosystem == candidate,
                                onClick = { ecosystem = candidate },
                                label = { Text(ecosystemLabel(candidate)) },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Package name") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(ecosystem, name.trim()) },
                enabled = name.isNotBlank(),
            ) { Text("Next") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun ConfirmCommandDialog(
    command: PackageCommand,
    onDismiss: () -> Unit,
    onRun: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(command.label) },
        text = {
            Text(
                text = command.command,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(onClick = onRun) { Text("Run in terminal") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

private fun ecosystemLabel(ecosystem: PackageEcosystem): String = when (ecosystem) {
    PackageEcosystem.NODE -> "Node"
    PackageEcosystem.PYTHON -> "Python"
}
