package com.maragung.arrowide.ui.nav

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import com.maragung.arrowide.ui.editor.EditorScreen
import com.maragung.arrowide.ui.explorer.ExplorerScreen
import com.maragung.arrowide.ui.home.HomeScreen
import com.maragung.arrowide.ui.scm.SourceControlScreen
import com.maragung.arrowide.ui.settings.SettingsScreen
import com.maragung.arrowide.ui.terminal.TerminalScreen
import com.maragung.arrowide.ui.tools.ToolsScreen

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
    TopDestination("tools", "Tools", Icons.Filled.Build),
    TopDestination("settings", "Settings", Icons.Filled.Settings)
)

/**
 * Root composable of the app shell.
 *
 * Adaptive navigation (plan #41): on wide screens (>= 840dp) a permanent
 * [NavigationRail] sits on the left (tablet layout); on narrower screens a
 * [NavigationBar] sits at the bottom (phone layout). Either way the same six
 * destinations are reachable: Home, Explorer, Editor, Terminal, Tools,
 * Settings.
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
                        destinations.forEach { destination ->
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
                    }
                }
            }
        ) { padding ->
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
                    composable("tools") {
                        ToolsScreen(manager = container.toolchainManager)
                    }
                    composable("settings") {
                        SettingsScreen(store = container.settingsStore)
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
