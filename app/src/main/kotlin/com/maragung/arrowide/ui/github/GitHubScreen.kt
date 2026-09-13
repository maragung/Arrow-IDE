package com.maragung.arrowide.ui.github

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.maragung.arrowide.github.GitHubResult
import com.maragung.arrowide.github.GitHubService
import com.maragung.arrowide.github.GithubRepo
import com.maragung.arrowide.github.GithubUser
import com.maragung.arrowide.github.WorkflowFileService
import java.io.File

/**
 * GitHub screen (plan #13-#17, #19): token-based connect flow, the account
 * hub, and internal navigation to repositories (browse / detail / clone /
 * create / delete), Actions (runs, jobs, logs, run-workflow), the log
 * viewer, and the workflow file browser/editor (plan #17, shown only when
 * the caller wires a [WorkflowFileService]).
 *
 * The Personal Access Token lives only in the connect field: it is masked,
 * cleared immediately after a successful connect, and never echoed back in
 * errors or snackbars (plan #12).
 */
@Composable
fun GitHubScreen(
    github: GitHubService,
    projectsDir: File,
    onProjectCloned: (File) -> Unit,
    cloneRepository: suspend (url: String, destDir: File) -> Boolean,
    workflowFiles: WorkflowFileService? = null,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var page by remember { mutableStateOf(initialPage(github)) }
    var user by remember { mutableStateOf<GithubUser?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var snackbarMessage by remember { mutableStateOf<String?>(null) }
    // Selected repository of the Actions page, hoisted so it survives
    // navigating into a run detail and back.
    var actionsRepo by remember { mutableStateOf<GithubRepo?>(null) }
    // Workflow browser/editor (plan #17): overlays the page model (GitHubPage
    // is not extended), so their back handling is separate. The selected
    // repository is hoisted like [actionsRepo].
    var workflowsOpen by remember { mutableStateOf(false) }
    var workflowsRepo by remember { mutableStateOf<GithubRepo?>(null) }
    var workflowEditor by remember { mutableStateOf<WorkflowEditorTarget?>(null) }

    fun reportError(detail: String) {
        error = detail
        snackbarMessage = snackbarExcerpt(detail)
    }

    val controller = GitHubController(github, scope, ::reportError)

    LaunchedEffect(snackbarMessage) {
        snackbarMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            snackbarMessage = null
        }
    }

    // System back follows the page hierarchy (pages also offer a back arrow).
    BackHandler(enabled = page !is GitHubPage.Account && page !is GitHubPage.Connect) {
        page = parentPage(page)
    }

    // The workflow browser and editor overlay the page model; system back
    // closes the topmost one first (the last enabled handler wins).
    BackHandler(enabled = workflowsOpen && workflowFiles != null) {
        workflowsOpen = false
    }
    BackHandler(enabled = workflowEditor != null) {
        workflowEditor = null
    }

    val ctx = GitHubPageContext(
        controller = controller,
        snackbarHostState = snackbarHostState,
        error = error,
        dismissError = { error = null }
    )

    Box(modifier = modifier.fillMaxSize()) {
        when (val current = page) {
        GitHubPage.Connect -> ConnectPage(
            ctx = ctx,
            onConnected = { connected ->
                user = connected
                page = GitHubPage.Account
            }
        )

        GitHubPage.Account -> AccountPage(
            ctx = ctx,
            user = user,
            onUserLoaded = { user = it },
            onSessionExpired = { page = GitHubPage.Connect },
            onNavigate = { page = it },
            onOpenWorkflows = if (workflowFiles != null) {
                { workflowsOpen = true }
            } else {
                null
            },
            onDisconnected = {
                user = null
                actionsRepo = null
                workflowsOpen = false
                workflowsRepo = null
                workflowEditor = null
                page = GitHubPage.Connect
            }
        )

        GitHubPage.Repositories -> RepositoriesPage(
            ctx = ctx,
            onBack = { page = parentPage(page) },
            onOpenRepo = { page = GitHubPage.RepoDetail(it) },
            onNewRepository = { page = GitHubPage.NewRepository }
        )

        is GitHubPage.RepoDetail -> RepoDetailPage(
            ctx = ctx,
            repo = current.repo,
            projectsDir = projectsDir,
            cloneRepository = cloneRepository,
            onProjectCloned = onProjectCloned,
            onBack = { page = parentPage(page) },
            onDeleted = { page = GitHubPage.Repositories }
        )

        GitHubPage.NewRepository -> NewRepositoryPage(
            ctx = ctx,
            onBack = { page = parentPage(page) },
            onCreated = { repo ->
                snackbarMessage = "Repository \"${repo.name}\" created"
                page = GitHubPage.Repositories
            }
        )

        GitHubPage.Actions -> ActionsPage(
            ctx = ctx,
            selectedRepo = actionsRepo,
            onSelectRepo = { actionsRepo = it },
            onBack = { page = parentPage(page) },
            onOpenRun = { repo, run -> page = GitHubPage.RunDetail(repo, run) }
        )

        is GitHubPage.RunDetail -> RunDetailPage(
            ctx = ctx,
            repo = current.repo,
            run = current.run,
            onBack = { page = parentPage(page) },
            onOpenJob = { job ->
                page = GitHubPage.LogViewer(current.repo, current.run, job)
            }
        )

        is GitHubPage.LogViewer -> LogViewerPage(
            ctx = ctx,
            repo = current.repo,
            run = current.run,
            job = current.job,
            onBack = { page = parentPage(page) }
        )
        }

        // Workflow browser/editor (plan #17), drawn over the current page.
        // Only reachable (and only rendered) when the caller wired a service.
        val workflowService = workflowFiles
        if (workflowsOpen && workflowService != null) {
            WorkflowsPage(
                ctx = ctx,
                workflowFiles = workflowService,
                selectedRepo = workflowsRepo,
                onSelectRepo = { workflowsRepo = it },
                onBack = { workflowsOpen = false },
                onOpenFile = { repo, file ->
                    workflowEditor = WorkflowEditorTarget(repo, file)
                }
            )
        }
        val editorTarget = workflowEditor
        if (editorTarget != null && workflowService != null) {
            WorkflowEditorPage(
                ctx = ctx,
                workflowFiles = workflowService,
                repo = editorTarget.repo,
                file = editorTarget.file,
                onBack = { workflowEditor = null }
            )
        }
    }
}

private fun initialPage(github: GitHubService): GitHubPage =
    if (github.hasToken()) GitHubPage.Account else GitHubPage.Connect

/** Shared scaffold top bar with a back arrow and a refresh action. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GitHubTopBar(
    title: String,
    onBack: (() -> Unit)?,
    refreshEnabled: Boolean = true,
    onRefresh: (() -> Unit)? = null,
    extraActions: (@Composable () -> Unit)? = null
) {
    TopAppBar(
        title = {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back"
                    )
                }
            }
        },
        actions = {
            if (onRefresh != null) {
                IconButton(onClick = onRefresh, enabled = refreshEnabled) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                }
            }
            extraActions?.invoke()
        }
    )
}

/**
 * Not-connected state (plan #13): centered card with a masked PAT field and
 * the connect button. The token is cleared as soon as the service has it and
 * is never displayed or echoed again (plan #12).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectPage(
    ctx: GitHubPageContext,
    onConnected: (GithubUser) -> Unit
) {
    var token by remember { mutableStateOf("") }
    var tokenVisible by remember { mutableStateOf(false) }
    var connecting by remember { mutableStateOf(false) }

    fun connect() {
        val candidate = token.trim()
        if (candidate.isEmpty()) return
        ctx.controller.launchAction("connect") {
            connecting = true
            try {
                when (val result = ctx.controller.service.connect(candidate)) {
                    is GitHubResult.Ok -> {
                        // Drop the in-memory copy immediately (plan #12).
                        token = ""
                        tokenVisible = false
                        onConnected(result.value)
                    }
                    is GitHubResult.Error ->
                        ctx.controller.report("connect", result)
                }
            } finally {
                connecting = false
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(ctx.snackbarHostState) },
        topBar = {
            GitHubTopBar(title = "GitHub", onBack = null)
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
                    modifier = Modifier.padding(16.dp)
                )
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
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
                            imageVector = Icons.Filled.Public,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "GitHub",
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            text = "Not connected",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(16.dp))
                        OutlinedTextField(
                            value = token,
                            onValueChange = { token = it },
                            label = { Text("Personal access token") },
                            singleLine = true,
                            enabled = !connecting,
                            visualTransformation = if (tokenVisible) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            trailingIcon = {
                                IconButton(
                                    onClick = { tokenVisible = !tokenVisible },
                                    enabled = !connecting
                                ) {
                                    Icon(
                                        imageVector = if (tokenVisible) {
                                            Icons.Filled.VisibilityOff
                                        } else {
                                            Icons.Filled.Visibility
                                        },
                                        contentDescription = if (tokenVisible) {
                                            "Hide token"
                                        } else {
                                            "Show token"
                                        }
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Create a token with the repo and workflow scopes, " +
                                "then paste it here. It is stored encrypted on this " +
                                "device and never shown again.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(
                            onClick = { connect() },
                            enabled = !connecting && token.isNotBlank()
                        ) {
                            if (connecting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                            Text("Connect GitHub")
                        }
                    }
                }
            }
        }
    }
}

/**
 * Connected account hub (plan #13): account header with letter avatar,
 * disconnect (with confirmation), and navigation cards for Repositories,
 * Actions, New repository, and Workflow files (plan #17 — the card only
 * appears when [onOpenWorkflows] is non-null, i.e. a WorkflowFileService
 * was wired into the screen).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccountPage(
    ctx: GitHubPageContext,
    user: GithubUser?,
    onUserLoaded: (GithubUser) -> Unit,
    onSessionExpired: () -> Unit,
    onNavigate: (GitHubPage) -> Unit,
    onOpenWorkflows: (() -> Unit)? = null,
    onDisconnected: () -> Unit
) {
    var loading by remember { mutableStateOf(user == null) }
    var showDisconnect by remember { mutableStateOf(false) }

    fun loadUser() {
        ctx.controller.launchAction("load account") {
            loading = true
            try {
                when (val result = ctx.controller.service.currentUser()) {
                    is GitHubResult.Ok -> onUserLoaded(result.value)
                    is GitHubResult.Error ->
                        if (result.statusCode == 401) {
                            // Stale or revoked token: back to the connect flow.
                            onSessionExpired()
                        } else {
                            ctx.controller.report("load account", result)
                        }
                }
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(Unit) {
        if (ctx.controller.service.hasToken()) {
            loadUser()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(ctx.snackbarHostState) },
        topBar = {
            GitHubTopBar(
                title = "GitHub",
                onBack = null,
                onRefresh = { loadUser() },
                refreshEnabled = !loading
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ctx.error?.let { message ->
                item(key = "error") {
                    GitHubErrorCard(message = message, onDismiss = ctx.dismissError)
                }
            }

            item(key = "account") {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        LetterAvatar(login = user?.login ?: "?")
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = user?.login ?: "Connected",
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            val name = user?.name
                            if (!name.isNullOrBlank()) {
                                Text(
                                    text = name,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (loading) {
                                Text(
                                    text = "Loading account…",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        OutlinedButton(
                            onClick = { showDisconnect = true },
                            enabled = !loading
                        ) { Text("Disconnect") }
                    }
                }
            }

            item(key = "nav-repositories") {
                NavigationCard(
                    icon = Icons.Filled.FolderOpen,
                    title = "Repositories",
                    subtitle = "Browse, clone, create and delete repositories",
                    onClick = { onNavigate(GitHubPage.Repositories) }
                )
            }
            item(key = "nav-actions") {
                NavigationCard(
                    icon = Icons.Filled.PlayArrow,
                    title = "Actions",
                    subtitle = "Workflow runs, jobs and logs",
                    onClick = { onNavigate(GitHubPage.Actions) }
                )
            }
            if (onOpenWorkflows != null) {
                item(key = "nav-workflows") {
                    NavigationCard(
                        icon = Icons.Filled.Description,
                        title = "Workflow files",
                        subtitle = "View and edit .github/workflows files",
                        onClick = onOpenWorkflows
                    )
                }
            }
            item(key = "nav-new") {
                NavigationCard(
                    icon = Icons.Filled.Add,
                    title = "New repository",
                    subtitle = "Create a repository under your account",
                    onClick = { onNavigate(GitHubPage.NewRepository) }
                )
            }
        }
    }

    if (showDisconnect) {
        AlertDialog(
            onDismissRequest = { showDisconnect = false },
            title = { Text("Disconnect GitHub?") },
            text = {
                Text(
                    "The stored token is removed from this device. " +
                        "Your repositories on GitHub are not affected."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDisconnect = false
                        ctx.controller.launchAction("disconnect") {
                            when (val result = ctx.controller.service.disconnect()) {
                                is GitHubResult.Ok -> onDisconnected()
                                is GitHubResult.Error ->
                                    ctx.controller.report("disconnect", result)
                            }
                        }
                    }
                ) { Text("Disconnect") }
            },
            dismissButton = {
                TextButton(onClick = { showDisconnect = false }) { Text("Cancel") }
            }
        )
    }
}

/** One navigation card of the account hub. */
@Composable
private fun NavigationCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
