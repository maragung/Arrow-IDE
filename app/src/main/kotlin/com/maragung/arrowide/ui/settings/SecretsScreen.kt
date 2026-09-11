package com.maragung.arrowide.ui.settings

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.maragung.arrowide.secrets.SecretStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Secrets screen (plan #21): manage the Keystore-encrypted secret store —
 * list, reveal (in-memory only), add, edit and delete named secrets.
 *
 * Values are never written to logs or snackbars; error and confirmation
 * messages contain names only. All store IO runs on Dispatchers.IO.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecretsScreen(
    store: SecretStore,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // null = still loading.
    var names by remember { mutableStateOf<List<String>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var snackbarMessage by remember { mutableStateOf<String?>(null) }
    // Revealed values live in memory only; toggling back re-masks them.
    val revealedValues = remember { mutableStateMapOf<String, String>() }

    var showAdd by remember { mutableStateOf(false) }
    var editName by remember { mutableStateOf<String?>(null) }
    var editValue by remember { mutableStateOf("") }
    var editReady by remember { mutableStateOf(false) }
    var deleteName by remember { mutableStateOf<String?>(null) }
    var secretBusy by remember { mutableStateOf(false) }

    fun reportError(detail: String) {
        error = detail
        snackbarMessage = secretsExcerpt(detail)
    }

    fun launchAction(label: String, action: suspend () -> Unit) {
        scope.launch {
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reportError("Secrets $label failed: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    fun refresh() {
        launchAction("load") {
            names = withContext(Dispatchers.IO) { store.names() }
        }
    }

    fun toggleReveal(name: String) {
        if (revealedValues.containsKey(name)) {
            revealedValues.remove(name)
        } else {
            launchAction("read \"$name\"") {
                val value = withContext(Dispatchers.IO) { store.get(name) }
                if (value == null) {
                    reportError("Secret \"$name\" could not be read from this device.")
                } else {
                    revealedValues[name] = value
                }
            }
        }
    }

    fun save(name: String, value: String) {
        launchAction("save \"$name\"") {
            secretBusy = true
            try {
                withContext(Dispatchers.IO) { store.set(name, value) }
                revealedValues.remove(name)
                snackbarMessage = "Secret \"$name\" saved"
                refresh()
            } finally {
                secretBusy = false
            }
        }
    }

    fun delete(name: String) {
        launchAction("delete \"$name\"") {
            secretBusy = true
            try {
                withContext(Dispatchers.IO) { store.set(name, null) }
                revealedValues.remove(name)
                snackbarMessage = "Secret \"$name\" deleted"
                refresh()
            } finally {
                secretBusy = false
            }
        }
    }

    fun openEdit(name: String) {
        editName = name
        editReady = false
        editValue = ""
        launchAction("read \"$name\"") {
            val value = withContext(Dispatchers.IO) { store.get(name) }
            if (value == null) {
                reportError("Secret \"$name\" could not be read from this device.")
                editName = null
            } else {
                editValue = value
                editReady = true
            }
        }
    }

    LaunchedEffect(Unit) { refresh() }

    LaunchedEffect(snackbarMessage) {
        snackbarMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            snackbarMessage = null
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Secrets") },
                actions = {
                    IconButton(
                        onClick = { showAdd = true },
                        enabled = names != null && !secretBusy
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = "Add secret")
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
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            SectionHeader("Stored secrets")
            Text(
                text = "Values are encrypted with the Android Keystore and never " +
                    "leave this device. Revealing a value keeps it in memory " +
                    "only until it is masked again.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            error?.let { message ->
                SecretsErrorCard(
                    message = message,
                    onDismiss = { error = null }
                )
                Spacer(Modifier.height(8.dp))
            }
            val list = names
            when {
                list == null -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Loading…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                list.isEmpty() -> Text(
                    text = "No secrets stored yet. Add one to keep tokens and " +
                        "keys out of your project files.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                else -> list.forEach { name ->
                    SecretRow(
                        name = name,
                        revealedValue = revealedValues[name],
                        enabled = !secretBusy,
                        onToggleReveal = { toggleReveal(name) },
                        onEdit = { openEdit(name) },
                        onDelete = { deleteName = name }
                    )
                }
            }
            Spacer(Modifier.padding(bottom = 16.dp))
        }
    }

    if (showAdd) {
        AddSecretDialog(
            existingNames = names.orEmpty(),
            busy = secretBusy,
            onDismiss = { if (!secretBusy) showAdd = false },
            onSave = { name, value ->
                showAdd = false
                save(name, value)
            }
        )
    }

    editName?.let { name ->
        EditSecretDialog(
            name = name,
            value = editValue,
            onValueChange = { editValue = it },
            ready = editReady,
            busy = secretBusy,
            onDismiss = {
                if (!secretBusy) {
                    editName = null
                    editReady = false
                }
            },
            onSave = { newValue ->
                editName = null
                editReady = false
                save(name, newValue)
            }
        )
    }

    deleteName?.let { name ->
        AlertDialog(
            onDismissRequest = { if (!secretBusy) deleteName = null },
            title = { Text("Delete secret?") },
            text = {
                Text("\"$name\" is removed from this device. This cannot be undone.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteName = null
                        delete(name)
                    },
                    enabled = !secretBusy
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { deleteName = null },
                    enabled = !secretBusy
                ) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
    )
}

/** Collapsible error card, mirroring the Settings/GitHub pattern. */
@Composable
private fun SecretsErrorCard(
    message: String,
    onDismiss: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Secrets error",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onDismiss) { Text("Dismiss") }
            }
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

/** One stored secret: name, masked-or-revealed value, reveal/edit/delete. */
@Composable
private fun SecretRow(
    name: String,
    revealedValue: String?,
    enabled: Boolean,
    onToggleReveal: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 6.dp)
        ) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = revealedValue ?: "••••••••",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        IconButton(onClick = onToggleReveal, enabled = enabled) {
            Icon(
                imageVector = if (revealedValue != null) {
                    Icons.Filled.VisibilityOff
                } else {
                    Icons.Filled.Visibility
                },
                contentDescription = if (revealedValue != null) {
                    "Hide value"
                } else {
                    "Show value"
                },
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onEdit, enabled = enabled) {
            Icon(
                imageVector = Icons.Filled.Edit,
                contentDescription = "Edit secret",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onDelete, enabled = enabled) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = "Delete secret",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Validation matching [com.maragung.arrowide.secrets.SecretNameValidation]:
 * non-blank, no path separators, no relative references. Returns null when
 * the name is valid.
 */
private fun secretNameError(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return "Enter a name"
    if ('/' in trimmed || '\\' in trimmed) {
        return "Name must not contain path separators"
    }
    if (trimmed == "." || ".." in trimmed) return "Invalid name"
    return null
}

/** Add-secret dialog: name plus masked value with a visibility toggle. */
@Composable
private fun AddSecretDialog(
    existingNames: List<String>,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSave: (name: String, value: String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }
    var valueVisible by remember { mutableStateOf(false) }
    val trimmedName = name.trim()
    val nameProblem = secretNameError(name)
    val overwrites = trimmedName.isNotEmpty() &&
        trimmedName in existingNames && nameProblem == null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add secret") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    enabled = !busy,
                    isError = name.isNotBlank() && nameProblem != null,
                    supportingText = {
                        when {
                            name.isNotBlank() && nameProblem != null -> Text(nameProblem)
                            overwrites -> Text("A secret with this name exists and will be replaced")
                            else -> Text("Stored encrypted on this device")
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text("Value") },
                    singleLine = true,
                    enabled = !busy,
                    visualTransformation = if (valueVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    trailingIcon = {
                        IconButton(
                            onClick = { valueVisible = !valueVisible },
                            enabled = !busy
                        ) {
                            Icon(
                                imageVector = if (valueVisible) {
                                    Icons.Filled.VisibilityOff
                                } else {
                                    Icons.Filled.Visibility
                                },
                                contentDescription = if (valueVisible) {
                                    "Hide value"
                                } else {
                                    "Show value"
                                }
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(trimmedName, value) },
                enabled = !busy && trimmedName.isNotEmpty() && nameProblem == null
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") }
        }
    )
}

/** Edit-value dialog: masked field with visibility toggle, prefilled. */
@Composable
private fun EditSecretDialog(
    name: String,
    value: String,
    onValueChange: (String) -> Unit,
    ready: Boolean,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var valueVisible by remember(name) { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit secret") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(8.dp))
                if (!ready) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Reading value…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    label = { Text("Value") },
                    singleLine = true,
                    enabled = ready && !busy,
                    visualTransformation = if (valueVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    trailingIcon = {
                        IconButton(
                            onClick = { valueVisible = !valueVisible },
                            enabled = ready && !busy
                        ) {
                            Icon(
                                imageVector = if (valueVisible) {
                                    Icons.Filled.VisibilityOff
                                } else {
                                    Icons.Filled.Visibility
                                },
                                contentDescription = if (valueVisible) {
                                    "Hide value"
                                } else {
                                    "Show value"
                                }
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(value) },
                enabled = ready && !busy
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") }
        }
    )
}

/** First non-blank line of [detail], truncated, for the snackbar. */
private fun secretsExcerpt(detail: String): String {
    val line = detail.lineSequence().firstOrNull { it.isNotBlank() } ?: return detail
    return if (line.length > 140) line.take(137) + "…" else line
}
