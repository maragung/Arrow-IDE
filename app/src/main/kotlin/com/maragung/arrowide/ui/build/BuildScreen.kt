package com.maragung.arrowide.ui.build

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import com.maragung.arrowide.buildsystem.BuildCommand
import com.maragung.arrowide.buildsystem.BuildSystemDetector
import com.maragung.arrowide.buildsystem.DetectedBuildSystem
import com.maragung.arrowide.buildsystem.EnvVar
import com.maragung.arrowide.buildsystem.ProjectEnvironment
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Build screen (plan #24 + #33 + #22): build-system detection for the open
 * workspace, one-tap Run / Build / Test / Git Pull / Git Push / Actions
 * actions, per-system command cards, and the `.env` environment card.
 *
 * Commands run in the terminal via [onRunInTerminal]; detection and all file
 * IO happen on Dispatchers.IO with the controller/error-card/snackbar
 * discipline of the GitHub screens.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BuildScreen(
    workspace: File?,
    detector: BuildSystemDetector,
    environment: ProjectEnvironment,
    toolAvailable: (String) -> Boolean,
    onRunInTerminal: (command: String, cwd: File) -> Unit,
    onGitPull: () -> Unit,
    onGitPush: () -> Unit,
    onOpenActions: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var error by remember { mutableStateOf<String?>(null) }
    var snackbarMessage by remember { mutableStateOf<String?>(null) }

    fun reportError(detail: String) {
        error = detail
        snackbarMessage = buildExcerpt(detail)
    }

    val controller = BuildController(scope, ::reportError)

    LaunchedEffect(snackbarMessage) {
        snackbarMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            snackbarMessage = null
        }
    }

    if (workspace == null) {
        Box(modifier = modifier.fillMaxSize()) {
            EmptyBuildState()
        }
        return
    }
    val currentWorkspace = workspace

    var refreshTick by remember { mutableStateOf(0) }
    var detecting by remember(currentWorkspace) { mutableStateOf(true) }
    var loaded by remember(currentWorkspace) { mutableStateOf(false) }
    var systems by remember(currentWorkspace) {
        mutableStateOf<List<DetectedBuildSystem>>(emptyList())
    }
    var envVars by remember(currentWorkspace) { mutableStateOf<List<EnvVar>?>(null) }
    // Revealed .env values are in-memory only and cleared on every reload.
    val revealedEnv = remember(currentWorkspace) { mutableStateMapOf<String, Boolean>() }
    var showEditEnv by remember { mutableStateOf(false) }
    var savingEnv by remember { mutableStateOf(false) }

    fun load() {
        controller.launchAction("detect build systems") {
            detecting = true
            try {
                val detected = withContext(Dispatchers.IO) { detector.detect(currentWorkspace) }
                val parsed = withContext(Dispatchers.IO) {
                    environment.parseDotEnv(File(currentWorkspace, ENV_FILE_NAME))
                }
                systems = detected
                envVars = parsed
                revealedEnv.clear()
                loaded = true
            } finally {
                detecting = false
            }
        }
    }

    LaunchedEffect(currentWorkspace, refreshTick) { load() }

    fun runCommand(command: BuildCommand) {
        onRunInTerminal(command.command, currentWorkspace)
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Build") },
                actions = {
                    IconButton(
                        onClick = { refreshTick++ },
                        enabled = !detecting && !savingEnv
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
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
            error?.let { message ->
                BuildErrorCard(
                    message = message,
                    onDismiss = { error = null },
                    modifier = Modifier.padding(
                        start = 16.dp,
                        top = 8.dp,
                        end = 16.dp
                    )
                )
            }
            if (!loaded) {
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
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item(key = "quick-actions") {
                        QuickActionsCard(
                            systems = systems,
                            toolAvailable = toolAvailable,
                            onRunCommand = ::runCommand,
                            onGitPull = onGitPull,
                            onGitPush = onGitPush,
                            onOpenActions = onOpenActions
                        )
                    }
                    if (systems.isEmpty()) {
                        item(key = "no-systems") { NoBuildSystemCard() }
                    }
                    items(systems) { system ->
                        BuildSystemCard(
                            system = system,
                            toolAvailable = toolAvailable,
                            onRunCommand = ::runCommand
                        )
                    }
                    item(key = "environment") {
                        EnvironmentCard(
                            envVars = envVars,
                            revealed = revealedEnv,
                            onToggleReveal = { name ->
                                revealedEnv[name] = !(revealedEnv[name] ?: false)
                            },
                            onEdit = { showEditEnv = true }
                        )
                    }
                    val sensitiveNames = envVars.orEmpty()
                        .filter { it.sensitive || environment.looksSensitive(it.name) }
                        .map { it.name }
                    if (sensitiveNames.isNotEmpty()) {
                        item(key = "env-warning") {
                            EnvSecretsWarningCard(names = sensitiveNames)
                        }
                    }
                }
            }
        }
    }

    if (showEditEnv) {
        EditEnvDialog(
            initial = envVars ?: emptyList(),
            saving = savingEnv,
            looksSensitive = { name -> environment.looksSensitive(name) },
            onDismiss = { if (!savingEnv) showEditEnv = false },
            onSave = { vars ->
                controller.launchAction("save .env") {
                    savingEnv = true
                    try {
                        withContext(Dispatchers.IO) {
                            environment.writeDotEnv(
                                File(currentWorkspace, ENV_FILE_NAME),
                                vars
                            )
                        }
                        showEditEnv = false
                        snackbarMessage = ".env saved"
                        load()
                    } finally {
                        savingEnv = false
                    }
                }
            }
        )
    }
}

/** The dotenv file read/written by the environment card (plan #22). */
internal const val ENV_FILE_NAME = ".env"

/**
 * Wraps every action of the Build screen with [CancellationException]
 * re-thrown and any other exception reported through the error card +
 * snackbar — the same discipline as the GitHub and Source Control screens.
 */
internal class BuildController(
    private val coroutineScope: CoroutineScope,
    private val onError: (String) -> Unit
) {
    /** Surfaces an arbitrary failure message through the error paths. */
    fun reportDetail(detail: String) {
        onError(detail)
    }

    /** Fire-and-forget action with exception reporting. */
    fun launchAction(label: String, action: suspend () -> Unit) {
        coroutineScope.launch {
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError("Build $label failed: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }
}

/** First non-blank line of [detail], truncated, for the snackbar. */
internal fun buildExcerpt(detail: String): String {
    val line = detail.lineSequence().firstOrNull { it.isNotBlank() } ?: return detail
    return if (line.length > 140) line.take(137) + "…" else line
}
