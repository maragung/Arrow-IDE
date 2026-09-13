package com.maragung.arrowide.ui.github

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import com.maragung.arrowide.github.GitHubResult
import com.maragung.arrowide.github.GithubRepo
import com.maragung.arrowide.github.WorkflowFile
import com.maragung.arrowide.github.WorkflowFileService
import kotlinx.coroutines.launch

/**
 * Workflow file editor (plan #17): fetches one `.github/workflows` file and
 * edits it in a monospace field. Saving commits via
 * [WorkflowFileService.saveWorkflowContent] after asking for a commit
 * message (prefilled "Update <filename>").
 *
 * Conflict handling: the save carries the blob sha the file was loaded at,
 * so GitHub answers 409 when it changed on GitHub in the meantime. The
 * service maps that to a message saying so; this page shows save errors
 * verbatim next to the editor and offers "Reload from GitHub", which
 * re-fetches the content AND the new sha. After a successful save the file
 * is re-fetched immediately — the save does not return the new blob sha,
 * and the next save must send it or it would 409.
 */
@Composable
internal fun WorkflowEditorPage(
    ctx: GitHubPageContext,
    workflowFiles: WorkflowFileService,
    repo: GithubRepo,
    file: WorkflowFile,
    onBack: () -> Unit
) {
    // null = not loaded yet; once non-null this is the editable text.
    var text by remember(repo.id, file.path) { mutableStateOf<String?>(null) }
    // Blob sha the text was loaded at; the sha every save must send.
    var sha by remember(repo.id, file.path) { mutableStateOf(file.sha) }
    var loading by remember(repo.id, file.path) { mutableStateOf(false) }
    var saving by remember(repo.id, file.path) { mutableStateOf(false) }
    // Verbatim [GitHubResult.Error.message] of the last save, if it failed.
    var saveError by remember(repo.id, file.path) { mutableStateOf<String?>(null) }
    var showCommitDialog by remember(repo.id, file.path) { mutableStateOf(false) }
    var commitMessage by remember(repo.id, file.path) {
        mutableStateOf(defaultCommitMessage(file))
    }

    val scope = rememberCoroutineScope()

    fun load() {
        ctx.controller.launchAction("load workflow file") {
            loading = true
            try {
                when (
                    val result = workflowFiles.fetchWorkflowContent(
                        owner = repo.ownerLogin,
                        repo = repo.name,
                        path = file.path
                    )
                ) {
                    is GitHubResult.Ok -> {
                        text = result.value.content
                        sha = result.value.sha
                        saveError = null
                    }
                    is GitHubResult.Error ->
                        ctx.controller.report("load workflow file", result)
                }
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(repo.id, file.path) { load() }

    fun save(message: String) {
        val content = text ?: return
        val trimmedMessage = message.trim()
        if (trimmedMessage.isEmpty()) return
        ctx.controller.launchAction("save workflow file") {
            saving = true
            try {
                when (
                    val result = workflowFiles.saveWorkflowContent(
                        owner = repo.ownerLogin,
                        repo = repo.name,
                        path = file.path,
                        content = content,
                        sha = sha,
                        message = trimmedMessage
                    )
                ) {
                    is GitHubResult.Ok -> {
                        saveError = null
                        // The save returns no new sha, so the file is
                        // re-fetched right away: its sha is what the next
                        // save must send, or GitHub would answer 409.
                        when (
                            val refreshed = workflowFiles.fetchWorkflowContent(
                                owner = repo.ownerLogin,
                                repo = repo.name,
                                path = file.path
                            )
                        ) {
                            is GitHubResult.Ok -> {
                                text = refreshed.value.content
                                sha = refreshed.value.sha
                            }
                            // The save itself succeeded; with the stale sha
                            // the next save turns into a 409 with the reload
                            // offer below, which is the honest fallback.
                            is GitHubResult.Error -> Unit
                        }
                        scope.launch {
                            ctx.snackbarHostState.showSnackbar("Saved ${file.name}")
                        }
                    }
                    // Shown verbatim below the editor, with a reload action.
                    is GitHubResult.Error -> saveError = result.message
                }
            } finally {
                saving = false
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(ctx.snackbarHostState) },
        topBar = {
            GitHubTopBar(
                title = file.path,
                onBack = onBack,
                onRefresh = { load() },
                refreshEnabled = !loading && !saving
            )
        }
    ) { padding ->
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
            val content = text
            when {
                loading && content == null -> Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
                content == null -> Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    HintText(text = "The workflow file is not loaded. Tap refresh to retry.")
                }
                else -> OutlinedTextField(
                    value = content,
                    onValueChange = { text = it },
                    enabled = !saving,
                    textStyle = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
            saveError?.let { message ->
                SaveErrorCard(
                    message = message,
                    reloading = loading,
                    onReload = { load() },
                    onDismiss = { saveError = null },
                    modifier = Modifier.padding(
                        start = 16.dp,
                        top = 8.dp,
                        end = 16.dp
                    )
                )
            }
            Button(
                onClick = { showCommitDialog = true },
                enabled = text != null && !saving,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                if (saving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text("Save")
            }
        }
    }

    if (showCommitDialog) {
        AlertDialog(
            onDismissRequest = { if (!saving) showCommitDialog = false },
            title = { Text("Save workflow file") },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Commits the edited ${file.name} to " +
                            "${repo.ownerLogin}/${repo.name} on GitHub."
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = commitMessage,
                        onValueChange = { commitMessage = it },
                        label = { Text("Commit message") },
                        singleLine = true,
                        enabled = !saving,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (saving) {
                        Spacer(Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "Saving…",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showCommitDialog = false
                        save(commitMessage)
                    },
                    enabled = !saving && commitMessage.isNotBlank()
                ) { Text("Commit") }
            },
            dismissButton = {
                TextButton(
                    onClick = { showCommitDialog = false },
                    enabled = !saving
                ) { Text("Cancel") }
            }
        )
    }
}

/**
 * Verbatim save failure below the editor: the service's message (a 409
 * spells out the conflict) plus the reload that re-fetches content and sha.
 */
@Composable
private fun SaveErrorCard(
    message: String,
    reloading: Boolean,
    onReload: () -> Unit,
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
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
            Row {
                TextButton(
                    onClick = onReload,
                    enabled = !reloading
                ) { Text("Reload from GitHub") }
                TextButton(onClick = onDismiss) { Text("Dismiss") }
            }
        }
    }
}

/** "Update ci.yml" — falls back to the path when the name is blank. */
private fun defaultCommitMessage(file: WorkflowFile): String =
    "Update " + file.name.ifBlank { file.path }
