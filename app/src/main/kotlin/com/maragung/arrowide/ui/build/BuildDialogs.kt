package com.maragung.arrowide.ui.build

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.maragung.arrowide.buildsystem.EnvVar

/**
 * Dialogs of the Build screen (plan #22): the `.env` editor. Values are
 * masked while typing with a per-row visibility toggle, and saving requires
 * an explicit confirmation that warns the values will live in plain text
 * inside the project.
 */

/** One editable row of the `.env` dialog (snapshot-observable draft). */
private class EnvVarDraft(initialName: String, initialValue: String) {
    var name by mutableStateOf(initialName)
    var value by mutableStateOf(initialValue)
    var valueVisible by mutableStateOf(false)
}

/**
 * `.env` editor: name/value rows with add/remove. "Save" first asks for the
 * plain-text opt-in, then hands the parsed [EnvVar] list to [onSave] —
 * [looksSensitive] decides each entry's `sensitive` flag so the written file
 * marks secret-looking values.
 */
@Composable
internal fun EditEnvDialog(
    initial: List<EnvVar>,
    saving: Boolean,
    looksSensitive: (String) -> Boolean,
    onDismiss: () -> Unit,
    onSave: (List<EnvVar>) -> Unit
) {
    val rows = remember {
        mutableStateListOf<EnvVarDraft>().apply {
            if (initial.isEmpty()) {
                add(EnvVarDraft("", ""))
            } else {
                initial.forEach { add(EnvVarDraft(it.name, it.value)) }
            }
        }
    }
    var showConfirm by remember { mutableStateOf(false) }

    fun rowError(draft: EnvVarDraft): String? {
        val name = draft.name.trim()
        if (name.isEmpty()) {
            // A fully blank row is simply dropped on save.
            return if (draft.value.isBlank()) null else "Enter a name"
        }
        if ('=' in name) return "Name must not contain '='"
        if ('\n' in name || '\r' in name) return "Name must be a single line"
        return null
    }

    val hasInvalid = rows.any { rowError(it) != null }
    val hasContent = rows.any { it.name.isNotBlank() || it.value.isNotBlank() }

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("Edit .env") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                rows.forEach { draft ->
                    Row(verticalAlignment = Alignment.Top) {
                        OutlinedTextField(
                            value = draft.name,
                            onValueChange = { if (!saving) draft.name = it },
                            label = { Text("Name") },
                            singleLine = true,
                            enabled = !saving,
                            isError = rowError(draft) != null,
                            supportingText = {
                                rowError(draft)?.let { Text(it) }
                            },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                            modifier = Modifier
                                .weight(1f)
                                .padding(top = 4.dp)
                        )
                        IconButton(
                            onClick = {
                                rows.remove(draft)
                                if (rows.isEmpty()) rows.add(EnvVarDraft("", ""))
                            },
                            enabled = !saving
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Delete,
                                contentDescription = "Remove variable",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    OutlinedTextField(
                        value = draft.value,
                        onValueChange = { if (!saving) draft.value = it },
                        label = { Text("Value") },
                        singleLine = true,
                        enabled = !saving,
                        visualTransformation = if (draft.valueVisible) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        trailingIcon = {
                            IconButton(
                                onClick = { draft.valueVisible = !draft.valueVisible },
                                enabled = !saving
                            ) {
                                Icon(
                                    imageVector = if (draft.valueVisible) {
                                        Icons.Filled.VisibilityOff
                                    } else {
                                        Icons.Filled.Visibility
                                    },
                                    contentDescription = if (draft.valueVisible) {
                                        "Hide value"
                                    } else {
                                        "Show value"
                                    }
                                )
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                    )
                    Spacer(Modifier.height(8.dp))
                }
                TextButton(
                    onClick = { rows.add(EnvVarDraft("", "")) },
                    enabled = !saving
                ) { Text("Add variable") }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { showConfirm = true },
                enabled = !saving && hasContent && !hasInvalid
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving) { Text("Cancel") }
        }
    )

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text("Store in plain text?") },
            text = {
                Text(
                    "Secrets in .env are stored in plain text inside the " +
                        "project and may be committed to Git. Anything " +
                        "committed stays visible in the repository history."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showConfirm = false
                        onSave(
                            rows
                                .filter { it.name.isNotBlank() }
                                .map { draft ->
                                    val name = draft.name.trim()
                                    EnvVar(
                                        name = name,
                                        value = draft.value,
                                        sensitive = looksSensitive(name)
                                    )
                                }
                        )
                    }
                ) { Text("Save .env") }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) { Text("Keep editing") }
            }
        )
    }
}
