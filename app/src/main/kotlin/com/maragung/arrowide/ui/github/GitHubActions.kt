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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.maragung.arrowide.github.GithubContentEntry
import com.maragung.arrowide.github.GithubJob
import com.maragung.arrowide.github.GithubRepo
import com.maragung.arrowide.github.GithubWorkflowRun

/**
 * GitHub Actions pages (plan #16, #18): repository picker entry, the
 * paginated workflow-run list with per-run Cancel / Re-run failed actions,
 * the run detail with jobs and steps, and the run-workflow dialog
 * (file + branch selection → workflow dispatch).
 */

/** Actions landing: pick a repository, then show its workflow runs. */
@Composable
internal fun ActionsPage(
    ctx: GitHubPageContext,
    selectedRepo: GithubRepo?,
    onSelectRepo: (GithubRepo) -> Unit,
    onBack: () -> Unit,
    onOpenRun: (GithubRepo, GithubWorkflowRun) -> Unit
) {
    var runs by remember { mutableStateOf<List<GithubWorkflowRun>?>(null) }
    var nextPage by remember { mutableStateOf(1) }
    var endReached by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var actionBusy by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var showPicker by remember { mutableStateOf(false) }
    var showRunWorkflow by remember { mutableStateOf(false) }

    val repo = selectedRepo

    fun load(reset: Boolean) {
        val current = repo ?: return
        if (loading) return
        ctx.controller.launchAction("load workflow runs") {
            loading = true
            try {
                val page = if (reset) 1 else nextPage
                val pageRuns = ctx.controller.call("load workflow runs") {
                    ctx.controller.service.listWorkflowRuns(
                        owner = current.ownerLogin,
                        repo = current.name,
                        page = page
                    )
                }
                if (pageRuns != null) {
                    runs = if (reset) pageRuns else (runs ?: emptyList()) + pageRuns
                    nextPage = page + 1
                    if (pageRuns.isEmpty()) endReached = true
                } else if (reset) {
                    runs = emptyList()
                    endReached = true
                }
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(repo?.id) {
        if (repo != null) {
            runs = null
            nextPage = 1
            endReached = false
            load(reset = true)
        }
    }

    fun cancelRun(run: GithubWorkflowRun) {
        val current = repo ?: return
        ctx.controller.launchAction("cancel run") {
            actionBusy = true
            try {
                ctx.controller.call("cancel run") {
                    ctx.controller.service.cancelRun(
                        owner = current.ownerLogin,
                        repo = current.name,
                        runId = run.id
                    )
                }
                load(reset = true)
            } finally {
                actionBusy = false
            }
        }
    }

    fun rerunFailed(run: GithubWorkflowRun) {
        val current = repo ?: return
        ctx.controller.launchAction("re-run failed jobs") {
            actionBusy = true
            try {
                ctx.controller.call("re-run failed jobs") {
                    ctx.controller.service.rerunFailedJobs(
                        owner = current.ownerLogin,
                        repo = current.name,
                        runId = run.id
                    )
                }
                load(reset = true)
            } finally {
                actionBusy = false
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(ctx.snackbarHostState) },
        topBar = {
            GitHubTopBar(
                title = if (repo == null) "Actions" else "Actions · ${repo.name}",
                onBack = onBack,
                onRefresh = { load(reset = true) },
                refreshEnabled = repo != null && !loading,
                extraActions = {
                    if (repo != null) {
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(
                                    Icons.Filled.MoreVert,
                                    contentDescription = "Actions menu"
                                )
                            }
                            DropdownMenu(
                                expanded = menuOpen,
                                onDismissRequest = { menuOpen = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Run workflow") },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Filled.PlayArrow,
                                            contentDescription = null
                                        )
                                    },
                                    onClick = {
                                        menuOpen = false
                                        showRunWorkflow = true
                                    }
                                )
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
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "GitHub Actions",
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            text = "Select a repository to view its workflow runs, " +
                                "jobs and logs.",
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
                val list = runs
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
                                HintText(text = "No workflow runs found.")
                            }
                        }
                        items(list, key = { it.id }) { run ->
                            WorkflowRunCard(
                                run = run,
                                enabled = !actionBusy && !loading,
                                onOpen = { onOpenRun(repo, run) },
                                onCancel = { cancelRun(run) },
                                onRerun = { rerunFailed(run) }
                            )
                        }
                        if (list.isNotEmpty()) {
                            if (loading) {
                                item(key = "runs-loading") {
                                    Box(
                                        modifier = Modifier.fillMaxWidth(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                }
                            } else if (!endReached) {
                                item(key = "runs-more") {
                                    Box(
                                        modifier = Modifier.fillMaxWidth(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        OutlinedButton(
                                            onClick = { load(reset = false) }
                                        ) { Text("Load more") }
                                    }
                                }
                            }
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

    if (showRunWorkflow && repo != null) {
        RunWorkflowDialog(
            ctx = ctx,
            repo = repo,
            onDismiss = { showRunWorkflow = false },
            onDispatched = {
                showRunWorkflow = false
                load(reset = true)
            }
        )
    }
}

/** One workflow run (plan #16): status, branch, commit, timing, actions. */
@Composable
private fun WorkflowRunCard(
    run: GithubWorkflowRun,
    enabled: Boolean,
    onOpen: () -> Unit,
    onCancel: () -> Unit,
    onRerun: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RunStatusIcon(
                    status = run.status,
                    conclusion = run.conclusion
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = run.name.ifBlank { "Run #${run.id}" },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val stateLabel = if (isRunActive(run.status)) {
                        statusLabel(run.status)
                    } else {
                        conclusionLabel(run.conclusion)
                    }
                    val branch = run.headBranch
                    val sha = shortSha(run.headSha)
                    val where = listOfNotNull(
                        branch?.takeIf { it.isNotBlank() },
                        sha.takeIf { it.isNotBlank() }
                    ).joinToString(" · ")
                    Text(
                        text = if (where.isBlank()) stateLabel else "$stateLabel · $where",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val started = formatRelativeTime(run.createdAt)
                    val duration = formatRunDuration(run.createdAt, run.updatedAt)
                    val timing = listOfNotNull(
                        started.takeIf { it.isNotBlank() }?.let { "Started $it" },
                        duration.takeIf { it.isNotBlank() }
                    ).joinToString(" · ")
                    if (timing.isNotBlank()) {
                        Text(
                            text = timing,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            if (isRunActive(run.status) || canRerun(run.conclusion)) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (isRunActive(run.status)) {
                        OutlinedButton(
                            onClick = onCancel,
                            enabled = enabled
                        ) { Text("Cancel") }
                    }
                    if (canRerun(run.conclusion)) {
                        OutlinedButton(
                            onClick = onRerun,
                            enabled = enabled
                        ) { Text("Re-run failed") }
                    }
                }
            }
        }
    }
}

/**
 * Run detail (plan #16): run summary plus its jobs; each job lists its steps
 * and opens the log viewer (plan #19) when tapped.
 */
@Composable
internal fun RunDetailPage(
    ctx: GitHubPageContext,
    repo: GithubRepo,
    run: GithubWorkflowRun,
    onBack: () -> Unit,
    onOpenJob: (GithubJob) -> Unit
) {
    var jobs by remember(repo.id, run.id) { mutableStateOf<List<GithubJob>?>(null) }
    var loading by remember(repo.id, run.id) { mutableStateOf(false) }

    fun loadJobs() {
        ctx.controller.launchAction("load jobs") {
            loading = true
            try {
                jobs = ctx.controller.call("load jobs") {
                    ctx.controller.service.listRunJobs(
                        owner = repo.ownerLogin,
                        repo = repo.name,
                        runId = run.id
                    )
                } ?: jobs
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(repo.id, run.id) { loadJobs() }

    Scaffold(
        snackbarHost = { SnackbarHost(ctx.snackbarHostState) },
        topBar = {
            GitHubTopBar(
                title = "Run #${run.id}",
                onBack = onBack,
                onRefresh = { loadJobs() },
                refreshEnabled = !loading
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
            RunSummaryCard(run = run)
            val list = jobs
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
                if (list.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        HintText(text = "No jobs found for this run.")
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(list, key = { it.id }) { job ->
                            JobCard(job = job, onOpenLogs = { onOpenJob(job) })
                        }
                    }
                }
            }
        }
    }
}

/** Summary header of a workflow run. */
@Composable
private fun RunSummaryCard(run: GithubWorkflowRun) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RunStatusIcon(status = run.status, conclusion = run.conclusion)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = run.name.ifBlank { "Run #${run.id}" },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = if (isRunActive(run.status)) {
                            statusLabel(run.status)
                        } else {
                            conclusionLabel(run.conclusion)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            val branch = run.headBranch
            if (!branch.isNullOrBlank()) {
                Text(
                    text = "Branch: $branch",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            val sha = shortSha(run.headSha)
            if (sha.isNotBlank()) {
                Text(
                    text = "Commit: $sha",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            val started = formatRelativeTime(run.createdAt)
            val duration = formatRunDuration(run.createdAt, run.updatedAt)
            val timing = listOfNotNull(
                started.takeIf { it.isNotBlank() }?.let { "Started $it" },
                duration.takeIf { it.isNotBlank() }?.let { "Duration $it" }
            ).joinToString(" · ")
            if (timing.isNotBlank()) {
                Text(
                    text = timing,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** One job with its steps; tapping the header opens the log viewer. */
@Composable
private fun JobCard(
    job: GithubJob,
    onOpenLogs: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenLogs)
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RunStatusIcon(status = job.status, conclusion = job.conclusion)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = job.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val duration = formatRunDuration(job.startedAt, job.completedAt)
                    val label = statusLabel(job.status)
                    Text(
                        text = if (duration.isBlank()) {
                            label
                        } else {
                            "$label · $duration"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = "Logs",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            if (job.steps.isNotEmpty()) {
                Column(
                    modifier = Modifier.padding(
                        start = 16.dp,
                        end = 16.dp,
                        bottom = 12.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    job.steps.forEach { step ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RunStatusIcon(
                                status = step.status,
                                conclusion = step.conclusion
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = step.name,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Run-workflow flow (plan #18 lite): pick a workflow file from
 * .github/workflows, pick a branch, then dispatch. Optional inputs are not
 * part of this lite version.
 */
@Composable
private fun RunWorkflowDialog(
    ctx: GitHubPageContext,
    repo: GithubRepo,
    onDismiss: () -> Unit,
    onDispatched: () -> Unit
) {
    var files by remember(repo.id) { mutableStateOf<List<GithubContentEntry>?>(null) }
    var branches by remember(repo.id) { mutableStateOf<List<String>?>(null) }
    var selectedFile by remember(repo.id) { mutableStateOf<GithubContentEntry?>(null) }
    var selectedBranch by remember(repo.id) { mutableStateOf<String?>(null) }
    var dispatching by remember { mutableStateOf(false) }

    LaunchedEffect(repo.id) {
        ctx.controller.launchAction("load workflow files") {
            files = ctx.controller.call("load workflow files") {
                ctx.controller.service.listWorkflowFiles(
                    owner = repo.ownerLogin,
                    repo = repo.name
                )
            }?.filter { entry ->
                entry.type == "file" &&
                    (entry.name.endsWith(".yml") || entry.name.endsWith(".yaml"))
            } ?: files
            branches = ctx.controller.call("load branches") {
                ctx.controller.service.listBranches(
                    owner = repo.ownerLogin,
                    repo = repo.name
                )
            } ?: branches
        }
    }

    fun dispatch() {
        val file = selectedFile
        val branch = selectedBranch
        if (file == null || branch == null) return
        ctx.controller.launchAction("dispatch workflow") {
            dispatching = true
            try {
                val dispatched = ctx.controller.call("dispatch workflow") {
                    ctx.controller.service.dispatchWorkflow(
                        owner = repo.ownerLogin,
                        repo = repo.name,
                        workflowFileName = file.name,
                        ref = branch
                    )
                }
                if (dispatched != null) {
                    onDispatched()
                }
            } finally {
                dispatching = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!dispatching) onDismiss() },
        title = { Text("Run workflow") },
        text = {
            val fileList = files
            val branchList = branches
            when {
                fileList == null || branchList == null -> Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Loading workflows and branches…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                else -> LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    item(key = "header-workflow") {
                        SectionLabel("Workflow")
                    }
                    if (fileList.isEmpty()) {
                        item(key = "no-workflows") {
                            HintText(text = "No workflow files in .github/workflows.")
                        }
                    } else {
                        items(fileList, key = { it.path }) { file ->
                            SelectableRow(
                                label = file.path,
                                selected = selectedFile?.path == file.path,
                                onClick = { selectedFile = file }
                            )
                        }
                    }
                    item(key = "header-branch") {
                        SectionLabel("Branch")
                    }
                    if (branchList.isEmpty()) {
                        item(key = "no-branches") {
                            HintText(text = "No branches found.")
                        }
                    } else {
                        items(branchList, key = { it }) { branch ->
                            SelectableRow(
                                label = branch,
                                selected = selectedBranch == branch,
                                onClick = { selectedBranch = branch }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { dispatch() },
                enabled = !dispatching && selectedFile != null && selectedBranch != null
            ) {
                if (dispatching) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                }
                Text("Run workflow")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !dispatching) {
                Text("Cancel")
            }
        }
    )
}

/** Small section label inside the run-workflow dialog. */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
    )
}

/** Tappable row with a check mark when selected. */
@Composable
private fun SelectableRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (selected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
        } else {
            Spacer(Modifier.size(18.dp))
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
