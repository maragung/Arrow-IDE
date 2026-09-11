package com.maragung.arrowide.ui.nav

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.maragung.arrowide.data.SettingsStore
import com.maragung.arrowide.git.GitOutcome
import com.maragung.arrowide.ui.ai.AiScreen
import com.maragung.arrowide.ui.build.BuildScreen
import com.maragung.arrowide.ui.editor.EditorScreen
import com.maragung.arrowide.ui.explorer.ExplorerScreen
import com.maragung.arrowide.ui.github.GitHubScreen
import com.maragung.arrowide.ui.home.HomeScreen
import com.maragung.arrowide.ui.scm.SourceControlScreen
import com.maragung.arrowide.ui.settings.SecretsScreen
import com.maragung.arrowide.ui.settings.SettingsScreen
import com.maragung.arrowide.ui.templates.TemplatesScreen
import com.maragung.arrowide.ui.terminal.TerminalScreen
import com.maragung.arrowide.ui.tools.ToolsScreen
import kotlinx.coroutines.launch

private data class TopDestination(
    val route: String,
    val label: String,
    val icon: ImageVector
)

private val destinations = listOf(
    TopDestination("home", "Home", Icons.Filled.Home),
    TopDestination("explorer", "Explorer", Icons.Filled.FolderOpen),
    TopDestination("editor", "Editor", Icons.Filled.Code),
    TopDestination("terminal", "Terminal", Icons.Filled.Terminal),
    TopDestination("scm", "Git", Icons.Filled.CallSplit),
    TopDestination("github", "GitHub", Icons.Filled.Public),
    TopDestination("ai", "AI", Icons.Filled.AutoAwesome),
    TopDestination("build", "Build", Icons.Filled.PlayArrow),
    TopDestination("templates", "Templates", Icons.Filled.CreateNewFolder),
    TopDestination("tools", "Tools", Icons.Filled.Build),
    TopDestination("settings", "Settings", Icons.Filled.Settings)
)

/**
 * Routes pinned to the phone bottom bar; the rest surface through the
 * "More" sheet. The tablet rail always shows everything.
 */
private val primaryRoutes = setOf("home", "explorer", "terminal", "scm", "settings")

/**
 * Root composable of the app shell.
 *
 * Adaptive navigation (plan #41): on wide screens (>= 840dp) a permanent
 * [NavigationRail] sits on the left (tablet layout); on narrower screens a
 * [NavigationBar] sits at the bottom (phone layout). Either way the same
 * destinations are reachable: Home, Explorer, Editor, Terminal, Git,
 * GitHub, Tools, Settings.
 *
 * Editor and Terminal route to their real subsystem screens
 * ([com.maragung.arrowide.ui.editor.EditorScreen],
 * [com.maragung.arrowide.ui.terminal.TerminalScreen]), which read their
 * state from the app-scoped [ArrowAppContainer] singletons.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArrowIDEApp(
    container: ArrowAppContainer,
    modifier: Modifier = Modifier
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    var showMoreSheet by remember { mutableStateOf(false) }
    val primary = destinations.filter { it.route in primaryRoutes }
    val secondary = destinations.filter { it.route !in primaryRoutes }

    // NavHost already pops its internal back stack on system back; this makes
    // the behavior explicit and keeps it correct alongside top-level navigation.
    BackHandler(enabled = navController.previousBackStackEntry != null) {
        navController.popBackStack()
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val useRail = maxWidth >= 840.dp

        Scaffold(
            bottomBar = {
                if (!useRail) {
                    NavigationBar {
                        primary.forEach { destination ->
                            NavigationBarItem(
                                icon = {
                                    Icon(
                                        destination.icon,
                                        contentDescription = destination.label
                                    )
                                },
                                label = { Text(destination.label) },
                                selected = currentRoute == destination.route,
                                onClick = { navController.navigateTopLevel(destination.route) }
                            )
                        }
                        NavigationBarItem(
                            icon = {
                                Icon(Icons.Filled.MoreHoriz, contentDescription = "More")
                            },
                            label = { Text("More") },
                            // Highlight "More" while one of its destinations is open.
                            selected = currentRoute != null &&
                                secondary.any { it.route == currentRoute },
                            onClick = { showMoreSheet = true }
                        )
                    }
                }
            }
        ) { padding ->
            if (showMoreSheet) {
                ModalBottomSheet(onDismissRequest = { showMoreSheet = false }) {
                    Text(
                        text = "More",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(start = 24.dp, bottom = 8.dp)
                    )
                    secondary.forEach { destination ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showMoreSheet = false
                                    navController.navigateTopLevel(destination.route)
                                }
                                .padding(horizontal = 24.dp, vertical = 14.dp)
                        ) {
                            Icon(
                                destination.icon,
                                contentDescription = null,
                                tint = if (currentRoute == destination.route) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                }
                            )
                            Spacer(Modifier.width(16.dp))
                            Text(
                                text = destination.label,
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (currentRoute == destination.route) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                }
                            )
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                if (useRail) {
                    NavigationRail(
                        header = {
                            Icon(
                                Icons.Filled.ArrowForward,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .padding(top = 16.dp)
                                    .size(28.dp)
                            )
                        }
                    ) {
                        destinations.forEach { destination ->
                            NavigationRailItem(
                                icon = {
                                    Icon(
                                        destination.icon,
                                        contentDescription = destination.label
                                    )
                                },
                                label = { Text(destination.label) },
                                selected = currentRoute == destination.route,
                                onClick = { navController.navigateTopLevel(destination.route) }
                            )
                        }
                    }
                }

                NavHost(
                    navController = navController,
                    startDestination = "home",
                    modifier = Modifier.weight(1f)
                ) {
                    composable("home") {
                        HomeScreen(
                            workspaceManager = container.workspaceManager,
                            onWorkspaceOpened = {
                                navController.navigateTopLevel("explorer")
                            },
                            onOpenTerminal = {
                                navController.navigateTopLevel("terminal")
                            }
                        )
                    }
                    composable("explorer") {
                        ExplorerScreen(
                            workspaceManager = container.workspaceManager,
                            onOpenFile = { file ->
                                container.editorTabManager.openFile(file)
                                navController.navigateTopLevel("editor")
                            }
                        )
                    }
                    composable("editor") {
                        EditorScreen(tabManager = container.editorTabManager)
                    }
                    composable("terminal") {
                        // New sessions open in the current workspace (plan #23);
                        // limits & font size come from user settings (plan #45).
                        val workspace by container.workspaceManager.currentWorkspace
                            .collectAsState()
                        val settings by container.settingsStore.settings
                            .collectAsStateWithLifecycle(
                                initialValue = SettingsStore.AppSettings()
                            )
                        TerminalScreen(
                            manager = container.terminalSessionManager,
                            newSessionCwd = workspace,
                            maxSessions = settings.maxTerminalSessions,
                            fontSizeDp = settings.terminalFontSize
                        )
                    }
                    composable("scm") {
                        // Source Control operates on the open project (plan #11).
                        val workspace by container.workspaceManager.currentWorkspace
                            .collectAsState()
                        SourceControlScreen(
                            git = container.gitService,
                            workspace = workspace
                        )
                    }
                    composable("github") {
                        // GitHub (plan #12-#16): connect with a PAT, browse
                        // repos, clone into ~/projects, inspect Actions runs.
                        GitHubScreen(
                            github = container.githubService,
                            projectsDir = container.workspaceManager.projectsDir,
                            onProjectCloned = { project ->
                                container.workspaceManager.setCurrentWorkspace(project)
                                navController.navigateTopLevel("explorer")
                            },
                            cloneRepository = { url, destDir ->
                                val outcome = container.gitService
                                    .repositoryFor(destDir)
                                    .clone(url, destDir)
                                outcome is GitOutcome.Ok
                            }
                        )
                    }
                    composable("ai") {
                        // AI coding agent (plan #51+): real OpenCode binary
                        // served locally; the agent works on the open workspace.
                        val workspace by container.workspaceManager.currentWorkspace
                            .collectAsState()
                        AiScreen(ai = container.openCodeService, workspace = workspace)
                    }
                    composable("build") {
                        // Build & one-tap actions (plan #24 + #33): commands
                        // derived from the project's own files, run in a real
                        // terminal session in the workspace.
                        val workspace by container.workspaceManager.currentWorkspace
                            .collectAsState()
                        val settings by container.settingsStore.settings
                            .collectAsStateWithLifecycle(
                                initialValue = SettingsStore.AppSettings()
                            )
                        val scope = rememberCoroutineScope()
                        BuildScreen(
                            workspace = workspace,
                            detector = container.buildSystemDetector,
                            environment = container.projectEnvironment,
                            toolAvailable = { toolId ->
                                container.toolchainManager.isAvailable(toolId) ?: false
                            },
                            onRunInTerminal = { command, cwd ->
                                container.terminalSessionManager
                                    .createSession(cwd, settings.maxTerminalSessions)
                                    .write((command + "\r").toByteArray())
                                navController.navigateTopLevel("terminal")
                            },
                            onGitPull = {
                                scope.launch {
                                    workspace?.let {
                                        container.gitService.repositoryFor(it).pull()
                                    }
                                }
                            },
                            onGitPush = {
                                scope.launch {
                                    workspace?.let {
                                        container.gitService.repositoryFor(it).push()
                                    }
                                }
                            },
                            onOpenActions = { navController.navigateTopLevel("github") }
                        )
                    }
                    composable("templates") {
                        // Project & workflow templates (plan #47 + #48).
                        val workspace by container.workspaceManager.currentWorkspace
                            .collectAsState()
                        TemplatesScreen(
                            projectsDir = container.workspaceManager.projectsDir,
                            workspace = workspace,
                            toolAvailable = { toolId ->
                                container.toolchainManager.isAvailable(toolId) ?: false
                            },
                            onProjectCreated = { project ->
                                container.workspaceManager.setCurrentWorkspace(project)
                                navController.navigateTopLevel("explorer")
                            },
                            onWorkflowCreated = { file ->
                                container.editorTabManager.openFile(file)
                                navController.navigateTopLevel("editor")
                            }
                        )
                    }
                    composable("secrets") {
                        // Local secrets manager (plan #21), opened from Settings.
                        SecretsScreen(store = container.secretStore)
                    }
                    composable("tools") {
                        ToolsScreen(manager = container.toolchainManager)
                    }
                    composable("settings") {
                        SettingsScreen(
                            store = container.settingsStore,
                            onOpenSecrets = { navController.navigateTopLevel("secrets") }
                        )
                    }
                }
            }
        }
    }
}

/** Top-level navigation: single instance, restores previously selected tab state. */
private fun NavHostController.navigateTopLevel(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
