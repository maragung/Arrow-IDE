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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.maragung.arrowide.github.GithubCommit
import com.maragung.arrowide.github.GithubIssue
import com.maragung.arrowide.github.GithubPullRequest
import com.maragung.arrowide.github.GithubRepo
import java.io.File

/**
 * Repository pages of the GitHub UI (plan #14, #15): the searchable,
 * paginated repository list; the repository detail with Branches / Commits /
 * Pull Requests / Issues tabs plus clone and destructive delete; the
 * new-repository form; and the repository picker dialog shared with Actions.
 */

/** Repository list with client-side search and "Load more" pagination. */
@Composable
internal fun RepositoriesPage(
    ctx: GitHubPageContext,
    onBack: () -> Unit,
    onOpenRepo: (GithubRepo) -> Unit,
    onNewRepository: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    // null = not loaded yet; the list is non-null once a page arrived.
    var repos by remember { mutableStateOf<List<GithubRepo>?>(null) }
    var nextPage by remember { mutableStateOf(1) }
    var endReached by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }

    fun load(reset: Boolean) {
        if (loading) return
        ctx.controller.launchAction("load repositories") {
            loading = true
            try {
                val page = if (reset) 1 else nextPage
                val pageRepos = ctx.controller.call("load repositories") {
                    ctx.controller.service.listRepositories(page = page)
                }
                if (pageRepos != null) {
                    repos = if (reset) {
                        pageRepos
                    } else {
                        (repos ?: emptyList()) + pageRepos
                    }
                    nextPage = page + 1
                    // An empty page means there is nothing more to fetch.
                    if (pageRepos.isEmpty()) endReached = true
                } else if (reset) {
                    repos = emptyList()
                    endReached = true
                }
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(Unit) { load(reset = true) }

    val trimmedQuery = query.trim()
    val visible = repos?.filter { repo ->
        trimmedQuery.isEmpty() ||
            repo.name.contains(trimmedQuery, ignoreCase = true) ||
            repo.ownerLogin.contains(trimmedQuery, ignoreCase = true) ||
            repo.description?.contains(trimmedQuery, ignoreCase = true) == true
    }

    Scaffold(
        snackbarHost = { SnackbarHost(ctx.snackbarHostState) },
        topBar = {
            GitHubTopBar(
                title = "Repositories",
                onBack = onBack,
                onRefresh = { load(reset = true) },
                refreshEnabled = !loading,
                extraActions = {
                    IconButton(onClick = onNewRepository) {
                        Icon(Icons.Filled.Add, contentDescription = "New repository")
                    }
                }
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
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search repositories") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )
            val list = visible
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
                            HintText(
                                text = if (trimmedQuery.isEmpty()) {
                                    "No repositories found."
                                } else {
                                    "No repositories match \"$trimmedQuery\"."
                                }
                            )
                        }
                    }
                    items(list, key = { it.id }) { repo ->
                        RepoCard(repo = repo, onClick = { onOpenRepo(repo) })
                    }
                    if (list.isNotEmpty()) {
                        if (loading) {
                            item(key = "loading-more") {
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
                            item(key = "load-more") {
                                Box(
                                    modifier = Modifier.fillMaxWidth(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    OutlinedButton(onClick = { load(reset = false) }) {
                                        Text("Load more")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One repository card of the list. */
@Composable
private fun RepoCard(
    repo: GithubRepo,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = repo.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (repo.isPrivate) {
                    Spacer(Modifier.width(8.dp))
                    PrivateBadge()
                }
            }
            val updated = formatRelativeTime(repo.updatedAt)
            Text(
                text = if (updated.isBlank()) {
                    repo.ownerLogin
                } else {
                    "${repo.ownerLogin} · updated $updated"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            val description = repo.description
            if (!description.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** Tabs of the repository detail page. */
private enum class RepoTab(val label: String) {
    Branches("Branches"),
    Commits("Commits"),
    PullRequests("Pull Requests"),
    Issues("Issues")
}

/**
 * Repository detail (plan #15): header card with metadata and the clone
 * action, tabs for branches / commits / pull requests / issues (each loaded
 * on demand), and a destructive delete behind an overflow menu that requires
 * typing the repository name.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RepoDetailPage(
    ctx: GitHubPageContext,
    repo: GithubRepo,
    projectsDir: File,
    cloneRepository: suspend (url: String, destDir: File) -> Boolean,
    onProjectCloned: (File) -> Unit,
    onBack: () -> Unit,
    onDeleted: () -> Unit
) {
    var tab by remember(repo.id) { mutableStateOf(RepoTab.Branches) }
    var tabLoading by remember(repo.id) { mutableStateOf(false) }
    var branches by remember(repo.id) { mutableStateOf<List<String>?>(null) }
    var commits by remember(repo.id) { mutableStateOf<List<GithubCommit>>(emptyList()) }
    var commitsLoaded by remember(repo.id) { mutableStateOf(false) }
    var commitsNextPage by remember(repo.id) { mutableStateOf(1) }
    var commitsEnd by remember(repo.id) { mutableStateOf(false) }
    var pulls by remember(repo.id) { mutableStateOf<List<GithubPullRequest>?>(null) }
    var issues by remember(repo.id) { mutableStateOf<List<GithubIssue>?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var showClone by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }

    fun loadTab(target: RepoTab) {
        ctx.controller.launchAction("load ${target.label.lowercase()}") {
            tabLoading = true
            try {
                when (target) {
                    RepoTab.Branches ->
                        branches = ctx.controller.call("load branches") {
                            ctx.controller.service.listBranches(
                                owner = repo.ownerLogin,
                                repo = repo.name
                            )
                        } ?: branches
                    RepoTab.Commits -> {
                        val page = commitsNextPage
                        val pageCommits = ctx.controller.call("load commits") {
                            ctx.controller.service.listCommits(
                                owner = repo.ownerLogin,
                                repo = repo.name,
                                page = page
                            )
                        }
                        if (pageCommits != null) {
                            commits = if (page == 1) pageCommits else commits + pageCommits
                            commitsNextPage = page + 1
                            if (pageCommits.isEmpty()) commitsEnd = true
                            commitsLoaded = true
                        }
                    }
                    RepoTab.PullRequests ->
                        pulls = ctx.controller.call("load pull requests") {
                            ctx.controller.service.listPullRequests(
                                owner = repo.ownerLogin,
                                repo = repo.name
                            )
                        } ?: pulls
                    RepoTab.Issues ->
                        issues = ctx.controller.call("load issues") {
                            ctx.controller.service.listIssues(
                                owner = repo.ownerLogin,
                                repo = repo.name
                            )
                        } ?: issues
                }
            } finally {
                tabLoading = false
            }
        }
    }

    fun refreshCurrentTab() {
        when (tab) {
            RepoTab.Branches -> branches = null
            RepoTab.Commits -> {
                commits = emptyList()
                commitsLoaded = false
                commitsNextPage = 1
                commitsEnd = false
            }
            RepoTab.PullRequests -> pulls = null
            RepoTab.Issues -> issues = null
        }
        loadTab(tab)
    }

    LaunchedEffect(repo.id, tab) {
        // Load each tab on demand, but do not refetch on every tab switch.
        val alreadyLoaded = when (tab) {
            RepoTab.Branches -> branches != null
            RepoTab.Commits -> commitsLoaded
            RepoTab.PullRequests -> pulls != null
            RepoTab.Issues -> issues != null
        }
        if (!alreadyLoaded) {
            loadTab(tab)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(ctx.snackbarHostState) },
        topBar = {
            GitHubTopBar(
                title = repo.name,
                onBack = onBack,
                onRefresh = { refreshCurrentTab() },
                refreshEnabled = !tabLoading,
                extraActions = {
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(
                                Icons.Filled.MoreVert,
                                contentDescription = "Repository actions"
                            )
                        }
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Delete repository") },
                                leadingIcon = {
                                    Icon(
                                        Icons.Filled.Delete,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                },
                                onClick = {
                                    menuOpen = false
                                    showDelete = true
                                }
                            )
                        }
                    }
                }
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
            RepoHeaderCard(repo = repo, onClone = { showClone = true })
            TabRow(selectedTabIndex = tab.ordinal) {
                RepoTab.values().forEach { entry ->
                    Tab(
                        text = { Text(entry.label) },
                        selected = tab == entry,
                        onClick = { tab = entry }
                    )
                }
            }
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (tab) {
                    RepoTab.Branches -> BranchList(
                        branches = branches,
                        loading = tabLoading
                    )
                    RepoTab.Commits -> CommitList(
                        commits = commits,
                        loaded = commitsLoaded,
                        loading = tabLoading,
                        endReached = commitsEnd,
                        onLoadMore = { loadTab(RepoTab.Commits) }
                    )
                    RepoTab.PullRequests -> PullRequestList(
                        pulls = pulls,
                        loading = tabLoading
                    )
                    RepoTab.Issues -> IssueList(
                        issues = issues,
                        loading = tabLoading
                    )
                }
            }
        }
    }

    if (showClone) {
        CloneDialog(
            repo = repo,
            projectsDir = projectsDir,
            cloneRepository = cloneRepository,
            controller = ctx.controller,
            onProjectCloned = onProjectCloned,
            onDismiss = { showClone = false }
        )
    }

    if (showDelete) {
        DeleteRepositoryDialog(
            repo = repo,
            controller = ctx.controller,
            onDeleted = onDeleted,
            onDismiss = { showDelete = false }
        )
    }
}

/** Repository metadata plus the clone action. */
@Composable
private fun RepoHeaderCard(
    repo: GithubRepo,
    onClone: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${repo.ownerLogin}/${repo.name}",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (repo.isPrivate) {
                    Spacer(Modifier.width(8.dp))
                    PrivateBadge()
                }
            }
            val description = repo.description
            if (!description.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Default branch: ${repo.defaultBranch}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = onClone) { Text("Clone to Projects") }
        }
    }
}

@Composable
private fun BranchList(
    branches: List<String>?,
    loading: Boolean
) {
    val list = branches
    when {
        list == null && loading -> LoadingBox()
        list == null -> Unit
        list.isEmpty() -> EmptyTabBox("No branches.")
        else -> LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(list, key = { it }) { branch ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.CallSplit,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = branch,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun CommitList(
    commits: List<GithubCommit>,
    loaded: Boolean,
    loading: Boolean,
    endReached: Boolean,
    onLoadMore: () -> Unit
) {
    if (!loaded) {
        if (loading) {
            LoadingBox()
        }
        return
    }
    if (commits.isEmpty()) {
        EmptyTabBox("No commits.")
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items(commits, key = { it.sha }) { commit ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = shortSha(commit.sha),
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = commit.message.lineSequence()
                            .firstOrNull { it.isNotBlank() }
                            ?.ifBlank { "(no message)" }
                            ?: "(no message)",
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${commit.authorName} · ${formatRelativeTime(commit.date)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        if (loading) {
            item(key = "commits-loading") { FooterLoading() }
        } else if (!endReached) {
            item(key = "commits-more") {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    OutlinedButton(onClick = onLoadMore) { Text("Load more") }
                }
            }
        }
    }
}

@Composable
private fun PullRequestList(
    pulls: List<GithubPullRequest>?,
    loading: Boolean
) {
    val list = pulls
    when {
        list == null && loading -> LoadingBox()
        list == null -> Unit
        list.isEmpty() -> EmptyTabBox("No pull requests.")
        else -> LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(list, key = { it.number }) { pull ->
                ItemNumberRow(
                    number = pull.number,
                    title = pull.title,
                    state = pull.state,
                    updatedAt = pull.updatedAt
                )
            }
        }
    }
}

@Composable
private fun IssueList(
    issues: List<GithubIssue>?,
    loading: Boolean
) {
    val list = issues
    when {
        list == null && loading -> LoadingBox()
        list == null -> Unit
        list.isEmpty() -> EmptyTabBox("No issues.")
        else -> LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(list, key = { it.number }) { issue ->
                ItemNumberRow(
                    number = issue.number,
                    title = issue.title,
                    state = issue.state,
                    updatedAt = issue.updatedAt
                )
            }
        }
    }
}

/** Shared row shape for pull requests and issues. */
@Composable
private fun ItemNumberRow(
    number: Int,
    title: String,
    state: String,
    updatedAt: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "#$number",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val updated = formatRelativeTime(updatedAt)
            Text(
                text = if (updated.isBlank()) state else "$state · $updated",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun LoadingBox() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun EmptyTabBox(text: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        HintText(text = text)
    }
}

@Composable
private fun FooterLoading() {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(modifier = Modifier.size(24.dp))
    }
}

/**
 * Clone confirmation (plan #14): shows the destination folder under the
 * Projects root, warns when it already exists, runs the coordinator-provided
 * clone with progress, and hands the result to [onProjectCloned].
 */
@Composable
private fun CloneDialog(
    repo: GithubRepo,
    projectsDir: File,
    cloneRepository: suspend (url: String, destDir: File) -> Boolean,
    controller: GitHubController,
    onProjectCloned: (File) -> Unit,
    onDismiss: () -> Unit
) {
    var cloning by remember { mutableStateOf(false) }
    val destDir = File(projectsDir, repo.name)

    AlertDialog(
        onDismissRequest = { if (!cloning) onDismiss() },
        title = { Text("Clone repository?") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text("Clones ${repo.ownerLogin}/${repo.name} into:")
                Spacer(Modifier.height(4.dp))
                Text(
                    text = destDir.absolutePath,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
                if (destDir.exists()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Warning: this folder already exists on this device.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                if (cloning) {
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Cloning…",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    controller.launchAction("clone") {
                        cloning = true
                        try {
                            val url = repo.htmlUrl.removeSuffix("/") + ".git"
                            if (cloneRepository(url, destDir)) {
                                onProjectCloned(destDir)
                            } else {
                                controller.reportDetail(
                                    "Cloning \"${repo.name}\" failed."
                                )
                            }
                        } finally {
                            cloning = false
                        }
                    }
                },
                enabled = !cloning
            ) { Text("Clone") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !cloning) {
                Text("Cancel")
            }
        }
    )
}

/**
 * Destructive delete (plan #15): only reachable from the repository detail
 * overflow menu, and only confirmable after typing the exact repository name.
 */
@Composable
private fun DeleteRepositoryDialog(
    repo: GithubRepo,
    controller: GitHubController,
    onDeleted: () -> Unit,
    onDismiss: () -> Unit
) {
    var typedName by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!deleting) onDismiss() },
        title = { Text("Delete repository?") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "This permanently deletes ${repo.ownerLogin}/${repo.name} " +
                        "on GitHub, including its code, issues and actions. " +
                        "This cannot be undone."
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = typedName,
                    onValueChange = { typedName = it },
                    label = { Text("Type \"${repo.name}\" to confirm") },
                    singleLine = true,
                    enabled = !deleting,
                    isError = typedName.isNotBlank() && typedName.trim() != repo.name,
                    modifier = Modifier.fillMaxWidth()
                )
                if (deleting) {
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Deleting…",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    controller.launchAction("delete repository") {
                        deleting = true
                        try {
                            val deleted = controller.call("delete repository") {
                                controller.service.deleteRepository(
                                    owner = repo.ownerLogin,
                                    repo = repo.name
                                )
                            }
                            if (deleted != null) {
                                onDeleted()
                            }
                        } finally {
                            deleting = false
                        }
                    }
                },
                enabled = !deleting && typedName.trim() == repo.name
            ) {
                Text(
                    text = "Delete repository",
                    color = MaterialTheme.colorScheme.error
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !deleting) {
                Text("Cancel")
            }
        }
    )
}

/** New repository form (plan #15): name, description and private switch. */
@Composable
internal fun NewRepositoryPage(
    ctx: GitHubPageContext,
    onBack: () -> Unit,
    onCreated: (GithubRepo) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var isPrivate by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }

    fun create() {
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) return
        ctx.controller.launchAction("create repository") {
            creating = true
            try {
                val created = ctx.controller.call("create repository") {
                    ctx.controller.service.createRepository(
                        name = trimmedName,
                        description = description.trim().ifBlank { null },
                        isPrivate = isPrivate
                    )
                }
                if (created != null) {
                    onCreated(created)
                }
            } finally {
                creating = false
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(ctx.snackbarHostState) },
        topBar = {
            GitHubTopBar(title = "New repository", onBack = onBack)
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
            ctx.error?.let { message ->
                GitHubErrorCard(message = message, onDismiss = ctx.dismissError)
            }
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Repository name") },
                singleLine = true,
                enabled = !creating,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text("Description (optional)") },
                enabled = !creating,
                minLines = 2,
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "Private", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = "Only you and collaborators can see it",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = isPrivate, onCheckedChange = { isPrivate = it })
            }
            Button(
                onClick = { create() },
                enabled = !creating && name.isNotBlank()
            ) {
                if (creating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text("Create repository")
            }
        }
    }
}

/** Repository picker used by the Actions page (plan #16, #18). */
@Composable
internal fun RepoPickerDialog(
    ctx: GitHubPageContext,
    onDismiss: () -> Unit,
    onSelect: (GithubRepo) -> Unit
) {
    var repos by remember { mutableStateOf<List<GithubRepo>?>(null) }
    var nextPage by remember { mutableStateOf(1) }
    var endReached by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }

    fun load(reset: Boolean) {
        if (loading) return
        ctx.controller.launchAction("load repositories") {
            loading = true
            try {
                val page = if (reset) 1 else nextPage
                val pageRepos = ctx.controller.call("load repositories") {
                    ctx.controller.service.listRepositories(page = page)
                }
                if (pageRepos != null) {
                    repos = if (reset) {
                        pageRepos
                    } else {
                        (repos ?: emptyList()) + pageRepos
                    }
                    nextPage = page + 1
                    if (pageRepos.isEmpty()) endReached = true
                } else if (reset) {
                    repos = emptyList()
                    endReached = true
                }
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(Unit) { load(reset = true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select repository") },
        text = {
            val list = repos
            when {
                list == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Loading…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                list.isEmpty() -> HintText(text = "No repositories found.")
                else -> LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    items(list, key = { it.id }) { repo ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(repo) }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = repo.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = repo.ownerLogin,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (repo.isPrivate) {
                                Spacer(Modifier.width(8.dp))
                                PrivateBadge()
                            }
                        }
                    }
                    if (!endReached && !loading) {
                        item(key = "picker-more") {
                            Box(
                                modifier = Modifier.fillMaxWidth(),
                                contentAlignment = Alignment.Center
                            ) {
                                TextButton(onClick = { load(reset = false) }) {
                                    Text("Load more")
                                }
                            }
                        }
                    }
                    if (loading) {
                        item(key = "picker-loading") {
                            Box(
                                modifier = Modifier.fillMaxWidth(),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
