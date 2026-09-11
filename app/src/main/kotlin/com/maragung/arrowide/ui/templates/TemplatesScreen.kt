package com.maragung.arrowide.ui.templates

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.maragung.arrowide.templates.ProjectTemplate
import com.maragung.arrowide.templates.ProjectTemplates
import com.maragung.arrowide.templates.TemplateCreateResult
import com.maragung.arrowide.templates.WorkflowTemplate
import com.maragung.arrowide.templates.WorkflowTemplates
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Templates screen (plan #47 + #48): two tabs — Projects (create a new
 * project under the Projects root from a template) and Workflows (write a
 * generated GitHub Actions workflow into `<workspace>/.github/workflows/`).
 *
 * Project creation goes through [ProjectTemplates.create] on
 * Dispatchers.IO; workflow files are never overwritten. Errors surface via
 * the controller/error-card/snackbar discipline of the GitHub screens.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TemplatesScreen(
    projectsDir: File,
    workspace: File?,
    toolAvailable: (String) -> Boolean,
    onProjectCreated: (File) -> Unit,
    onWorkflowCreated: (File) -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var error by remember { mutableStateOf<String?>(null) }
    var snackbarMessage by remember { mutableStateOf<String?>(null) }
    var tab by remember { mutableStateOf(TemplatesTab.PROJECTS) }

    // Hoisted so the typed name survives tab switches.
    var projectName by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    var expandedProjectId by remember { mutableStateOf<String?>(null) }
    var addingWorkflowId by remember { mutableStateOf<String?>(null) }

    fun reportError(detail: String) {
        error = detail
        snackbarMessage = templatesExcerpt(detail)
    }

    val controller = TemplatesController(scope, ::reportError)

    LaunchedEffect(snackbarMessage) {
        snackbarMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            snackbarMessage = null
        }
    }

    fun createProject(template: ProjectTemplate) {
        val trimmed = projectName.trim()
        controller.launchAction("create project") {
            creating = true
            try {
                val dir = File(projectsDir, trimmed)
                val result = withContext(Dispatchers.IO) {
                    ProjectTemplates.create(template, dir)
                }
                when (result) {
                    is TemplateCreateResult.Created -> {
                        projectName = ""
                        snackbarMessage = "Project \"${dir.name}\" created"
                        onProjectCreated(dir)
                    }
                    is TemplateCreateResult.Error ->
                        controller.reportDetail(result.message)
                }
            } finally {
                creating = false
            }
        }
    }

    fun addWorkflow(template: WorkflowTemplate, workspaceDir: File, fileNameRaw: String) {
        val fileName = fileNameRaw.trim()
        controller.launchAction("add workflow") {
            addingWorkflowId = template.id
            try {
                val target = File(File(workspaceDir, WORKFLOWS_DIR), fileName)
                val created = withContext(Dispatchers.IO) {
                    if (target.exists()) {
                        false
                    } else {
                        target.parentFile?.mkdirs()
                        target.writeText(WorkflowTemplates.generate(template))
                        true
                    }
                }
                if (created) {
                    snackbarMessage = "Workflow file \"$fileName\" created"
                    onWorkflowCreated(target)
                } else {
                    controller.reportDetail(
                        "A workflow file named \"$fileName\" already exists " +
                            "in .github/workflows."
                    )
                }
            } finally {
                addingWorkflowId = null
            }
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = { TopAppBar(title = { Text("Templates") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            TabRow(selectedTabIndex = tab.ordinal) {
                TemplatesTab.entries.forEach { entry ->
                    Tab(
                        text = { Text(entry.label) },
                        selected = tab == entry,
                        onClick = { tab = entry }
                    )
                }
            }
            error?.let { message ->
                TemplatesErrorCard(
                    message = message,
                    onDismiss = { error = null },
                    modifier = Modifier.padding(
                        start = 16.dp,
                        top = 8.dp,
                        end = 16.dp
                    )
                )
            }
            when (tab) {
                TemplatesTab.PROJECTS -> ProjectsTab(
                    projectsDir = projectsDir,
                    toolAvailable = toolAvailable,
                    name = projectName,
                    onNameChange = { projectName = it },
                    creating = creating,
                    expandedId = expandedProjectId,
                    onToggleExpand = { id ->
                        expandedProjectId = if (expandedProjectId == id) null else id
                    },
                    onCreate = { template -> createProject(template) }
                )

                TemplatesTab.WORKFLOWS -> WorkflowsTab(
                    workspace = workspace,
                    addingId = addingWorkflowId,
                    onAdd = { template, workspaceDir, fileName ->
                        addWorkflow(template, workspaceDir, fileName)
                    }
                )
            }
        }
    }
}

/** Directory (relative to the workspace) workflow files are written to. */
internal const val WORKFLOWS_DIR = ".github/workflows"

private enum class TemplatesTab(val label: String) {
    PROJECTS("Projects"),
    WORKFLOWS("Workflows")
}

/** Projects tab: project-name field plus the template cards (plan #47). */
@Composable
private fun ProjectsTab(
    projectsDir: File,
    toolAvailable: (String) -> Boolean,
    name: String,
    onNameChange: (String) -> Unit,
    creating: Boolean,
    expandedId: String?,
    onToggleExpand: (String) -> Unit,
    onCreate: (ProjectTemplate) -> Unit
) {
    val nameProblem = projectNameError(name, projectsDir)
    val nameValid = name.isNotBlank() && nameProblem == null

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "name-field") {
            Column(modifier = Modifier.fillMaxWidth()) {
                TemplateNameField(
                    name = name,
                    onNameChange = onNameChange,
                    enabled = !creating,
                    problem = if (name.isNotBlank()) nameProblem else null
                )
                Text(
                    text = "New projects are created under ${projectsDir.absolutePath}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        items(ProjectTemplates.all, key = { it.id }) { template ->
            ProjectTemplateCard(
                template = template,
                toolAvailable = toolAvailable,
                expanded = expandedId == template.id,
                onToggle = { onToggleExpand(template.id) },
                nameValid = nameValid,
                creating = creating,
                onCreate = { onCreate(template) }
            )
        }
    }
}

/** Workflows tab (plan #48): needs an open workspace. */
@Composable
private fun WorkflowsTab(
    workspace: File?,
    addingId: String?,
    onAdd: (template: WorkflowTemplate, workspaceDir: File, fileName: String) -> Unit
) {
    if (workspace == null) {
        NoWorkspaceCard(modifier = Modifier.padding(16.dp))
        return
    }
    val currentWorkspace = workspace
    var expandedId by remember(currentWorkspace) { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(WorkflowTemplates.all, key = { it.id }) { template ->
            WorkflowTemplateCard(
                template = template,
                expanded = expandedId == template.id,
                adding = addingId == template.id,
                onToggle = {
                    expandedId = if (expandedId == template.id) null else template.id
                },
                onAdd = { fileName -> onAdd(template, currentWorkspace, fileName) }
            )
        }
    }
}

/**
 * Wraps every action of the Templates screen with [CancellationException]
 * re-thrown and any other exception reported through the error card +
 * snackbar — the same discipline as the GitHub and Source Control screens.
 */
internal class TemplatesController(
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
                onError("Templates $label failed: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }
}

/** First non-blank line of [detail], truncated, for the snackbar. */
internal fun templatesExcerpt(detail: String): String {
    val line = detail.lineSequence().firstOrNull { it.isNotBlank() } ?: return detail
    return if (line.length > 140) line.take(137) + "…" else line
}
