package com.maragung.arrowide.ui.scm

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.maragung.arrowide.git.GitBranches
import com.maragung.arrowide.git.GitLogEntry
import com.maragung.arrowide.git.GitOutcome
import com.maragung.arrowide.git.GitRepository
import com.maragung.arrowide.git.GitService
import com.maragung.arrowide.git.GitStatus
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Source Control screen (plan #11): status of the current workspace's git
 * repository with staging, committing, fetching/pulling/pushing, branch
 * switching, stashing and diff viewing — all as real git operations through
 * [GitService]/[GitRepository].
 *
 * Every action goes through [launchGitAction], which serializes operations
 * (one [ScmState.runningOp] at a time, buttons disabled while running) and
 * refreshes status, branches and the recent-commits log after each mutation.
 * [GitOutcome.Error] results and unexpected exceptions surface as a snackbar
 * excerpt plus a collapsible error card.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceControlScreen(
    git: GitService,
    workspace: File?,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // Cheap process check; computed once per service instance, not per frame.
    val gitInstalled = remember(git) { git.isGitInstalled() }

    val repository = remember(git, workspace) {
        workspace?.let { git.repositoryFor(it) }
    }

    // Screen state is reset when the workspace (and with it the repository) changes.
    var state by remember(repository) { mutableStateOf(ScmState()) }
    var snackbarMessage by remember { mutableStateOf<String?>(null) }
    var commitMessage by remember(repository) { mutableStateOf("") }
    var dialog by remember { mutableStateOf<ScmDialog>(ScmDialog.None) }
    var diffRequest by remember { mutableStateOf<DiffRequest?>(null) }
    var diffText by remember { mutableStateOf<String?>(null) }

    fun reportGitError(op: String, error: GitOutcome.Error, hint: String? = null) {
        val detail = buildString {
            append("git ")
            append(op)
            append(" failed (exit ")
            append(error.exitCode)
            append(")")
            val stderr = error.stderr.trim()
            if (stderr.isNotBlank()) {
                append('\n')
                append(stderr)
            }
            if (hint != null) {
                append('\n')
                append(hint)
            }
        }
        state = state.copy(error = detail)
        snackbarMessage = snackbarExcerpt(detail)
    }

    /** Runs a git call and reports [GitOutcome.Error] through the screen's error paths. */
    suspend fun <T> runGitOp(op: String, block: suspend () -> GitOutcome<T>): T? =
        when (val outcome = block()) {
            is GitOutcome.Ok -> outcome.value
            is GitOutcome.Error -> {
                reportGitError(op, outcome)
                null
            }
        }

    /** Reloads status, branches and the recent-commits log. */
    suspend fun refresh() {
        val repo = repository ?: return
        when (val result = repo.status()) {
            is GitOutcome.Ok -> state = state.copy(
                statusChecked = true,
                isRepo = result.value != null,
                status = result.value
            )
            is GitOutcome.Error -> {
                reportGitError("status", result)
                state = state.copy(statusChecked = false, isRepo = false, status = null)
            }
        }
        if (state.isRepo) {
            when (val result = repo.branches()) {
                is GitOutcome.Ok -> state = state.copy(branches = result.value)
                is GitOutcome.Error -> reportGitError("branch", result)
            }
            // A failing log (e.g. empty repository) is not worth a snackbar.
            when (val result = repo.log(maxCount = 10)) {
                is GitOutcome.Ok -> state = state.copy(recentCommits = result.value)
                is GitOutcome.Error -> state = state.copy(recentCommits = emptyList())
            }
        } else {
            state = state.copy(branches = null, recentCommits = emptyList())
        }
    }

    fun launchGitAction(op: String, action: suspend (GitRepository) -> Unit) {
        val repo = repository ?: return
        if (state.runningOp != null) return
        scope.launch {
            state = state.copy(runningOp = op)
            try {
                action(repo)
                refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val detail = "git $op failed: ${e.message ?: e.javaClass.simpleName}"
                state = state.copy(error = detail)
                snackbarMessage = snackbarExcerpt(detail)
            } finally {
                state = state.copy(runningOp = null)
            }
        }
    }

    // Initial (and workspace-change) load.
    LaunchedEffect(repository) {
        if (repository != null) {
            try {
                refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Already reported through the error card if possible.
                state = state.copy(statusChecked = false, isRepo = false, status = null)
            }
        }
    }

    // Snackbar (short excerpt) for the latest failure; full text stays in the error card.
    LaunchedEffect(snackbarMessage) {
        snackbarMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            snackbarMessage = null
        }
    }

    // Load the diff for the tapped entry while the sheet is open.
    LaunchedEffect(diffRequest) {
        val request = diffRequest ?: return@LaunchedEffect
        val repo = repository ?: return@LaunchedEffect
        diffText = null
        val outcome = if (request.staged) {
            repo.diffStaged(request.path)
        } else {
            repo.diffUnstaged(request.path)
        }
        when (outcome) {
            is GitOutcome.Ok -> diffText = outcome.value.ifBlank { "No textual changes." }
            is GitOutcome.Error -> {
                reportGitError("diff", outcome)
                diffRequest = null
            }
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Source Control") },
                actions = {
                    if (gitInstalled && repository != null) {
                        IconButton(
                            onClick = { launchGitAction("refresh") {} },
                            enabled = state.runningOp == null
                        ) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Refresh status")
                        }
                        if (state.isRepo) {
                            Box {
                                var menuOpen by remember { mutableStateOf(false) }
                                IconButton(
                                    onClick = { menuOpen = true },
                                    enabled = state.runningOp == null
                                ) {
                                    Icon(Icons.Filled.MoreVert, contentDescription = "Stash actions")
                                }
                                DropdownMenu(
                                    expanded = menuOpen,
                                    onDismissRequest = { menuOpen = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Stash changes") },
                                        leadingIcon = {
                                            Icon(Icons.Filled.Layers, contentDescription = null)
                                        },
                                        onClick = {
                                            menuOpen = false
                                            launchGitAction("stash push") { repo ->
                                                runGitOp("stash push") { repo.stashPush() }
                                            }
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Stash pop") },
                                        leadingIcon = {
                                            Icon(Icons.Filled.Unarchive, contentDescription = null)
                                        },
                                        onClick = {
                                            menuOpen = false
                                            launchGitAction("stash pop") { repo ->
                                                runGitOp("stash pop") { repo.stashPop() }
                                            }
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("View stashes") },
                                        leadingIcon = {
                                            Icon(Icons.Filled.List, contentDescription = null)
                                        },
                                        onClick = {
                                            menuOpen = false
                                            dialog = ScmDialog.StashList
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            )
        }
    ) { padding ->
        when {
            workspace == null -> EmptySourceControlState(Modifier.padding(padding))
            !gitInstalled -> GitNotInstalledCard(Modifier.padding(padding))
            !state.statusChecked -> {
                val error = state.error
                if (error != null) {
                    ScmErrorCard(
                        message = error,
                        onDismiss = { state = state.copy(error = null) },
                        modifier = Modifier
                            .padding(padding)
                            .fillMaxSize()
                            .padding(16.dp)
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .padding(padding)
                            .fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }
            !state.isRepo -> NotARepositoryCard(
                modifier = Modifier.padding(padding),
                busy = state.runningOp != null,
                onInitialize = {
                    launchGitAction("init") { repo ->
                        runGitOp("init") { repo.init() }
                    }
                }
            )
            else -> {
                val status = state.status
                val staged = status?.staged.orEmpty()
                val conflicted = status?.conflicted.orEmpty()
                val unstaged = status?.unstaged.orEmpty()
                val untracked = status?.untracked.orEmpty()

                LazyColumn(
                    modifier = Modifier
                        .padding(padding)
                        .fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    state.error?.let { message ->
                        item(key = "error") {
                            ScmErrorCard(
                                message = message,
                                onDismiss = { state = state.copy(error = null) }
                            )
                        }
                    }

                    item(key = "branch-header") {
                        BranchHeaderCard(
                            status = status,
                            runningOp = state.runningOp,
                            onFetch = {
                                launchGitAction("fetch") { repo ->
                                    runGitOp("fetch") { repo.fetch() }
                                }
                            },
                            onPull = {
                                launchGitAction("pull") { repo ->
                                    runGitOp("pull") { repo.pull() }
                                }
                            },
                            onPush = {
                                launchGitAction("push") { repo ->
                                    val branch = status?.branch
                                    // First push of a new branch needs --set-upstream.
                                    val trackUpstream =
                                        branch != null && status?.upstream == null
                                    runGitOp("push") {
                                        repo.push(
                                            branch = branch,
                                            setUpstream = trackUpstream
                                        )
                                    }
                                }
                            }
                        )
                    }

                    item(key = "commit") {
                        CommitCard(
                            message = commitMessage,
                            onMessageChange = { commitMessage = it },
                            stagedCount = staged.size,
                            busy = state.runningOp != null,
                            onCommit = {
                                val message = commitMessage.trim()
                                if (message.isNotEmpty()) {
                                    launchGitAction("commit") { repo ->
                                        when (val result = repo.commit(message)) {
                                            is GitOutcome.Ok -> commitMessage = ""
                                            is GitOutcome.Error -> {
                                                val identityHint =
                                                    if (result.stderr.contains("user.name") ||
                                                        result.stderr.contains("user.email")
                                                    ) {
                                                        "Set your name and email in Settings → Git, then try again."
                                                    } else {
                                                        null
                                                    }
                                                reportGitError("commit", result, hint = identityHint)
                                            }
                                        }
                                    }
                                }
                            }
                        )
                    }

                    item(key = "branches") {
                        BranchCard(
                            branches = state.branches,
                            currentBranch = status?.branch,
                            busy = state.runningOp != null,
                            onSwitch = { dialog = ScmDialog.Branches }
                        )
                    }

                    if (staged.isNotEmpty()) {
                        item(key = "staged-header") {
                            ScmSectionHeader(
                                title = "Staged Changes",
                                count = staged.size,
                                actionLabel = "Unstage All",
                                actionEnabled = state.runningOp == null,
                                onAction = {
                                    launchGitAction("unstage") { repo ->
                                        runGitOp("unstage") { repo.unstage(staged.map { it.path }) }
                                    }
                                }
                            )
                        }
                        items(staged, key = { "staged-${it.path}" }) { entry ->
                            ChangeEntryRow(
                                path = entry.path,
                                statusCode = entry.statusCode,
                                renamedFrom = entry.renamedFrom,
                                onOpenDiff = { diffRequest = DiffRequest(entry.path, staged = true) },
                                actionIcon = Icons.Filled.Remove,
                                actionDescription = "Unstage ${entry.path}",
                                onAction = {
                                    launchGitAction("unstage") { repo ->
                                        runGitOp("unstage") { repo.unstage(listOf(entry.path)) }
                                    }
                                },
                                actionEnabled = state.runningOp == null
                            )
                        }
                    }

                    if (conflicted.isNotEmpty()) {
                        item(key = "conflicts-header") {
                            ScmSectionHeader(title = "Conflicts", count = conflicted.size)
                        }
                        items(conflicted, key = { "conflict-$it" }) { path ->
                            ChangeEntryRow(
                                path = path,
                                statusCode = 'U',
                                renamedFrom = null,
                                onOpenDiff = { diffRequest = DiffRequest(path, staged = false) },
                                actionIcon = Icons.Filled.Add,
                                actionDescription = "Mark $path resolved",
                                onAction = {
                                    launchGitAction("stage") { repo ->
                                        runGitOp("stage") { repo.stage(listOf(path)) }
                                    }
                                },
                                actionEnabled = state.runningOp == null
                            )
                        }
                    }

                    if (unstaged.isNotEmpty()) {
                        item(key = "unstaged-header") {
                            ScmSectionHeader(
                                title = "Changes",
                                count = unstaged.size,
                                actionLabel = "Stage All",
                                actionEnabled = state.runningOp == null,
                                onAction = {
                                    launchGitAction("stage") { repo ->
                                        runGitOp("stage") { repo.stageAll() }
                                    }
                                }
                            )
                        }
                        items(unstaged, key = { "unstaged-${it.path}" }) { entry ->
                            ChangeEntryRow(
                                path = entry.path,
                                statusCode = entry.statusCode,
                                renamedFrom = entry.renamedFrom,
                                onOpenDiff = { diffRequest = DiffRequest(entry.path, staged = false) },
                                actionIcon = Icons.Filled.Add,
                                actionDescription = "Stage ${entry.path}",
                                onAction = {
                                    launchGitAction("stage") { repo ->
                                        runGitOp("stage") { repo.stage(listOf(entry.path)) }
                                    }
                                },
                                actionEnabled = state.runningOp == null
                            )
                        }
                    }

                    if (untracked.isNotEmpty()) {
                        item(key = "untracked-header") {
                            ScmSectionHeader(title = "Untracked", count = untracked.size)
                        }
                        items(untracked, key = { "untracked-$it" }) { path ->
                            ChangeEntryRow(
                                path = path,
                                statusCode = null,
                                renamedFrom = null,
                                onOpenDiff = null,
                                actionIcon = Icons.Filled.Add,
                                actionDescription = "Stage $path",
                                onAction = {
                                    launchGitAction("stage") { repo ->
                                        runGitOp("stage") { repo.stage(listOf(path)) }
                                    }
                                },
                                actionEnabled = state.runningOp == null
                            )
                        }
                    }

                    if (staged.isEmpty() && conflicted.isEmpty() &&
                        unstaged.isEmpty() && untracked.isEmpty()
                    ) {
                        item(key = "clean") {
                            Text(
                                text = "No changes. Working tree clean.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    item(key = "commits-header") {
                        ScmSectionHeader(title = "Recent Commits", count = state.recentCommits.size)
                    }
                    if (state.recentCommits.isEmpty()) {
                        item(key = "no-commits") {
                            Text(
                                text = "No commits yet.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        items(state.recentCommits, key = { "commit-${it.sha}" }) { entry ->
                            CommitRow(entry)
                        }
                    }
                }
            }
        }
    }

    when (dialog) {
        ScmDialog.None -> Unit
        ScmDialog.Branches -> BranchSwitchDialog(
            branches = state.branches,
            currentBranch = state.status?.branch,
            busy = state.runningOp != null,
            onDismiss = { dialog = ScmDialog.None },
            onCheckout = { branch ->
                dialog = ScmDialog.None
                launchGitAction("checkout") { repo ->
                    runGitOp("checkout") { repo.checkout(branch) }
                }
            },
            onCreate = { name ->
                dialog = ScmDialog.None
                launchGitAction("create branch") { repo ->
                    runGitOp("create branch") { repo.createBranch(name) }
                }
            }
        )
        ScmDialog.StashList -> {
            val repo = repository
            if (repo != null) {
                StashListDialog(
                    repository = repo,
                    onDismiss = { dialog = ScmDialog.None }
                )
            }
        }
    }

    diffRequest?.let { request ->
        DiffSheet(
            title = request.path,
            staged = request.staged,
            diff = diffText,
            onDismiss = {
                diffRequest = null
                diffText = null
            }
        )
    }
}

/** Dialog state for the source control flows. */
private sealed interface ScmDialog {
    data object None : ScmDialog
    data object Branches : ScmDialog
    data object StashList : ScmDialog
}

/** A diff the user asked to see: the file path and whether it is staged. */
private data class DiffRequest(val path: String, val staged: Boolean)

/** Snapshot of the screen; recreated when the workspace changes. */
private data class ScmState(
    /** True once a status call succeeded (even if it reported "not a repository"). */
    val statusChecked: Boolean = false,
    val isRepo: Boolean = false,
    val status: GitStatus? = null,
    val branches: GitBranches? = null,
    val recentCommits: List<GitLogEntry> = emptyList(),
    /** Label of the currently running git operation; buttons disable while set. */
    val runningOp: String? = null,
    val error: String? = null
)

/** First line plus the first non-blank stderr line, truncated for the snackbar. */
private fun snackbarExcerpt(detail: String): String {
    val lines = detail.lines()
    val headline = lines.firstOrNull() ?: return detail
    val stderrLine = lines.drop(1).firstOrNull { it.isNotBlank() }
    val text = if (stderrLine != null) "$headline: $stderrLine" else headline
    return if (text.length > 140) text.take(137) + "…" else text
}
