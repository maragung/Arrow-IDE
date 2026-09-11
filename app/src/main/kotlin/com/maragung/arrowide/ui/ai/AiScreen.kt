package com.maragung.arrowide.ui.ai

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.maragung.arrowide.ai.AiFileDiff
import com.maragung.arrowide.ai.AiMessage
import com.maragung.arrowide.ai.AiPermissionRequest
import com.maragung.arrowide.ai.AiProvider
import com.maragung.arrowide.ai.AiResult
import com.maragung.arrowide.ai.AiSessionInfo
import com.maragung.arrowide.ai.OpenCodeClient
import com.maragung.arrowide.ai.OpenCodeInstallState
import com.maragung.arrowide.ai.OpenCodeReleaseInfo
import com.maragung.arrowide.ai.OpenCodeServerState
import com.maragung.arrowide.ai.OpenCodeService
import com.maragung.arrowide.ai.parsePermissionRequest
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * AI agent screen (plan #51, #58, #59, #67): the OpenCode runtime install
 * flow, the chat (sessions, message list, model + agent pickers, streaming
 * with Stop, inline permission approvals, per-reply change viewer) and the
 * provider API-key page.
 *
 * Internal navigation follows the GitHub screen pattern (sealed page model +
 * BackHandler); every long operation goes through [AiController] with busy
 * flags and error card + snackbar reporting. Live updates come from the
 * server's event stream; a bounded poll after each send backs it up.
 */
@Composable
fun AiScreen(
    ai: OpenCodeService,
    workspace: File?,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var page by remember { mutableStateOf(initialAiPage(ai)) }
    var error by remember { mutableStateOf<String?>(null) }
    var snackbarMessage by remember { mutableStateOf<String?>(null) }

    fun reportError(detail: String) {
        error = detail
        snackbarMessage = aiSnackbarExcerpt(detail)
    }

    val controller = AiController(ai, scope, ::reportError)

    LaunchedEffect(snackbarMessage) {
        snackbarMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            snackbarMessage = null
        }
    }

    // System back follows the page hierarchy (pages also offer a back arrow).
    BackHandler(enabled = page == AiPage.Providers) {
        page = parentAiPage(page)
    }

    val ctx = AiPageContext(
        controller = controller,
        snackbarHostState = snackbarHostState,
        error = error,
        dismissError = { error = null }
    )

    Box(modifier = modifier.fillMaxSize()) {
        when (page) {
            AiPage.Install -> InstallPage(ctx, ai, onInstalled = { page = AiPage.Chat })
            AiPage.Chat -> ChatPage(
                ctx = ctx,
                ai = ai,
                workspace = workspace,
                onOpenProviders = { page = AiPage.Providers }
            )
            AiPage.Providers -> ProvidersPage(ctx, ai, onBack = { page = AiPage.Chat })
        }
    }
}

private fun initialAiPage(ai: OpenCodeService): AiPage =
    if (ai.isInstalled()) AiPage.Chat else AiPage.Install

/** Shared scaffold top bar with an optional back arrow and refresh action. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AiTopBar(
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
 * OpenCode install page (plan #51): explains that the AI agent runs the real
 * OpenCode binary locally, shows the latest release, and installs it with
 * progress and failure surfacing through the install state flow.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InstallPage(
    ctx: AiPageContext,
    ai: OpenCodeService,
    onInstalled: () -> Unit
) {
    val installProgress by ai.installProgress.collectAsStateWithLifecycle()
    var release by remember { mutableStateOf<OpenCodeReleaseInfo?>(null) }
    var loadingRelease by remember { mutableStateOf(false) }

    fun loadRelease() {
        if (loadingRelease) return
        ctx.controller.launchAction("load release info") {
            loadingRelease = true
            try {
                release = ctx.controller.call("load release info") {
                    ai.refreshLatestRelease()
                }
            } finally {
                loadingRelease = false
            }
        }
    }

    LaunchedEffect(Unit) { loadRelease() }

    // Once the runtime is on disk, continue into the chat.
    LaunchedEffect(installProgress) {
        if (installProgress.state == OpenCodeInstallState.INSTALLED) {
            onInstalled()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(ctx.snackbarHostState) },
        topBar = { AiTopBar(title = "AI Agent", onBack = null) }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            ctx.error?.let { message ->
                AiErrorCard(message = message, onDismiss = ctx.dismissError)
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Filled.SmartToy,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "AI Agent",
                        style = MaterialTheme.typography.titleLarge
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "The agent runs the real OpenCode binary locally on " +
                            "this device. It reads your open project, plans and edits " +
                            "files, and runs commands — you approve what it may do.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(16.dp))
                    when (val current = release) {
                        null -> if (loadingRelease) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "Checking the latest release…",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            TextButton(onClick = { loadRelease() }) {
                                Text("Check the latest release")
                            }
                        }
                        else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "Latest release",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = current.tagName,
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                text = current.assetName +
                                    " · " + formatAssetSize(current.assetSizeBytes),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = {
                            ctx.controller.launchAction("install") {
                                when (val result = ai.install()) {
                                    is AiResult.Ok -> Unit
                                    is AiResult.Error ->
                                        ctx.controller.report("install", result)
                                }
                            }
                        },
                        enabled = installProgress.state != OpenCodeInstallState.INSTALLING
                    ) {
                        Text("Install OpenCode")
                    }
                }
            }

            if (installProgress.state == OpenCodeInstallState.INSTALLING ||
                installProgress.state == OpenCodeInstallState.FAILED
            ) {
                AiInstallProgressCard(
                    state = installProgress.state,
                    message = installProgress.message,
                    progress = installProgress.progress,
                    onRetry = {
                        ctx.controller.launchAction("install") {
                            when (val result = ai.install()) {
                                is AiResult.Ok -> Unit
                                is AiResult.Error ->
                                    ctx.controller.report("install", result)
                            }
                        }
                    }
                )
            }
        }
    }
}

/**
 * Chat page (plan #51, #58, #59): session management, the message list with
 * model/agent pickers, streaming replies with Stop, permission approvals and
 * the per-reply change viewer. Requires an open workspace and a running
 * OpenCode server.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatPage(
    ctx: AiPageContext,
    ai: OpenCodeService,
    workspace: File?,
    onOpenProviders: () -> Unit
) {
    val serverState by ai.serverState.collectAsStateWithLifecycle()
    val client = ai.client()
    val listState = rememberLazyListState()

    var serverError by remember { mutableStateOf<String?>(null) }
    var startingServer by remember { mutableStateOf(false) }
    var providers by remember { mutableStateOf<List<AiProvider>>(emptyList()) }
    var sessions by remember { mutableStateOf<List<AiSessionInfo>>(emptyList()) }
    var selectedSession by remember { mutableStateOf<AiSessionInfo?>(null) }
    var messages by remember { mutableStateOf<List<AiMessage>>(emptyList()) }
    var permissions by remember { mutableStateOf<List<AiPermissionRequest>>(emptyList()) }
    var loadingMessages by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var streaming by remember { mutableStateOf(false) }
    var respondingTo by remember { mutableStateOf<String?>(null) }
    var showSessions by remember { mutableStateOf(false) }
    var selectedModel by remember { mutableStateOf<String?>(null) }
    var agentOption by remember { mutableStateOf(AI_AGENT_OPTIONS.first()) }
    var input by remember { mutableStateOf("") }
    var diffFor by remember { mutableStateOf<AiMessage?>(null) }
    var diffContent by remember { mutableStateOf<List<AiFileDiff>?>(null) }

    val serverReady = serverError == null && serverState is OpenCodeServerState.Ready

    /** Loads the messages of [sessionId] and refreshes the streaming flag. */
    suspend fun refreshMessages(client: OpenCodeClient, sessionId: String) {
        when (val result = client.messages(sessionId)) {
            is AiResult.Ok -> messages = result.value
            is AiResult.Error -> ctx.controller.report("load messages", result)
        }
        streaming = messages.any { it.role == "assistant" && isAiMessageActive(it) }
    }

    /** Loads providers and the session list once the server is ready. */
    fun loadServerData() {
        ctx.controller.launchAction("load sessions") {
            val currentClient = ai.client()
            if (currentClient == null) return@launchAction
            providers = ctx.controller.call("load providers") {
                currentClient.listProviders()
            } ?: providers
            sessions = ctx.controller.call("load sessions") {
                currentClient.listSessions()
            } ?: sessions
        }
    }

    fun startServer() {
        if (startingServer) return
        startingServer = true
        ctx.controller.launchAction("start server") {
            try {
                // Run the server IN the open workspace so the agent works on
                // the current project; switching workspaces restarts it there.
                when (val state = ai.ensureServer(workspace)) {
                    is OpenCodeServerState.Failed -> serverError = state.reason
                    else -> serverError = null
                }
            } finally {
                startingServer = false
            }
        }
    }

    // Start (or verify) the local OpenCode server when entering the chat —
    // and again whenever the open workspace changes.
    LaunchedEffect(workspace) { startServer() }

    // React to server-state transitions reported by the service itself.
    LaunchedEffect(serverState) {
        when (val state = serverState) {
            is OpenCodeServerState.Failed ->
                if (serverError == null) serverError = state.reason
            is OpenCodeServerState.Ready -> {
                serverError = null
                if (providers.isEmpty() && sessions.isEmpty()) {
                    loadServerData()
                }
            }
            else -> Unit
        }
    }

    fun refresh() {
        ctx.controller.launchAction("refresh") {
            val currentClient = ai.client() ?: return@launchAction
            sessions = ctx.controller.call("load sessions") {
                currentClient.listSessions()
            } ?: sessions
            val session = selectedSession
            if (session != null) {
                refreshMessages(currentClient, session.id)
            }
        }
    }

    fun selectSession(session: AiSessionInfo) {
        showSessions = false
        selectedSession = session
        messages = emptyList()
        permissions = emptyList()
        streaming = false
        loadingMessages = true
        ctx.controller.launchAction("load messages") {
            try {
                val currentClient = ai.client()
                if (currentClient != null) {
                    refreshMessages(currentClient, session.id)
                }
            } finally {
                loadingMessages = false
            }
        }
    }

    fun newSession() {
        showSessions = false
        ctx.controller.launchAction("create session") {
            val currentClient = ai.client()
            if (currentClient == null) {
                ctx.controller.reportDetail("AI server is not running.")
                return@launchAction
            }
            val session = ctx.controller.call("create session") {
                currentClient.createSession()
            } ?: return@launchAction
            sessions = listOf(session) + sessions.filterNot { it.id == session.id }
            selectSession(session)
        }
    }

    fun deleteSession(session: AiSessionInfo) {
        ctx.controller.launchAction("delete session") {
            val currentClient = ai.client()
            if (currentClient == null) {
                ctx.controller.reportDetail("AI server is not running.")
                return@launchAction
            }
            if (ctx.controller.call("delete session") {
                    currentClient.deleteSession(session.id)
                } == null
            ) {
                return@launchAction
            }
            sessions = sessions.filterNot { it.id == session.id }
            if (selectedSession?.id == session.id) {
                selectedSession = sessions.firstOrNull()
                messages = emptyList()
                permissions = emptyList()
                streaming = false
                selectedSession?.let { next ->
                    loadingMessages = true
                    try {
                        refreshMessages(currentClient, next.id)
                    } finally {
                        loadingMessages = false
                    }
                }
            }
        }
    }

    /**
     * Bounded poll that keeps the message list fresh while a reply streams:
     * stops when nothing changed between two polls and no part is running,
     * hard-caps after ~5 minutes. Backs up the event subscription.
     */
    suspend fun pollUntilSettled(currentClient: OpenCodeClient, sessionId: String) {
        try {
            var previous: String? = null
            var stableCount = 0
            repeat(200) {
                delay(1500)
                when (val result = currentClient.messages(sessionId)) {
                    is AiResult.Ok -> {
                        messages = result.value
                        val active = result.value.any { message ->
                            message.role == "assistant" && isAiMessageActive(message)
                        }
                        val signature = signatureOf(result.value)
                        stableCount = if (signature == previous) stableCount + 1 else 0
                        previous = signature
                        if (!active && stableCount >= 1) return
                    }
                    is AiResult.Error -> {
                        ctx.controller.report("load messages", result)
                        return
                    }
                }
            }
        } finally {
            streaming = false
            // The session title is usually derived from the first exchange.
            currentClient.listSessions().let { result ->
                if (result is AiResult.Ok) {
                    sessions = result.value
                }
            }
        }
    }

    fun send() {
        val text = input.trim()
        if (text.isEmpty() || sending || streaming) return
        val currentClient = ai.client()
        if (currentClient == null) {
            ctx.controller.reportDetail("AI server is not running.")
            return
        }
        input = ""
        sending = true
        ctx.controller.launchAction("send message") {
            try {
                var session = selectedSession
                if (session == null) {
                    session = ctx.controller.call("create session") {
                        currentClient.createSession()
                    }
                    if (session == null) {
                        // Let the user retry without retyping.
                        input = text
                        return@launchAction
                    }
                    sessions = listOf(session) + sessions.filterNot { it.id == session.id }
                    selectedSession = session
                }
                when (
                    val result = currentClient.sendMessage(
                        session.id,
                        text,
                        model = selectedModel,
                        agent = agentOption.id
                    )
                ) {
                    is AiResult.Ok -> {
                        streaming = true
                        pollUntilSettled(currentClient, session.id)
                    }
                    is AiResult.Error -> {
                        ctx.controller.report("send message", result)
                        refreshMessages(currentClient, session.id)
                    }
                }
            } finally {
                sending = false
            }
        }
    }

    fun stop() {
        val session = selectedSession ?: return
        val currentClient = ai.client() ?: return
        ctx.controller.launchAction("stop agent") {
            when (val result = currentClient.abort(session.id)) {
                is AiResult.Ok -> {
                    streaming = false
                    refreshMessages(currentClient, session.id)
                }
                is AiResult.Error -> ctx.controller.report("stop agent", result)
            }
        }
    }

    fun respond(permission: AiPermissionRequest, allow: Boolean, rememberChoice: Boolean) {
        val currentClient = ai.client()
        if (currentClient == null) {
            ctx.controller.reportDetail("AI server is not running.")
            return
        }
        respondingTo = permission.id
        ctx.controller.launchAction("respond to permission") {
            try {
                when (
                    val result = currentClient.respondToPermission(
                        permission.sessionId,
                        permission.id,
                        allow,
                        rememberChoice
                    )
                ) {
                    is AiResult.Ok ->
                        permissions = permissions.filterNot { it.id == permission.id }
                    is AiResult.Error ->
                        ctx.controller.report("respond to permission", result)
                }
            } finally {
                respondingTo = null
            }
        }
    }

    // Live updates from the server's SSE event stream (message.updated,
    // permission.updated, session.updated), throttled — real events only;
    // the poll after each send backs this up.
    LaunchedEffect(client) {
        if (client == null) return@LaunchedEffect
        val lastRefresh = AtomicLong(0L)
        try {
            // Runs until this effect's scope is cancelled (leaving the page).
            client.events { eventName, dataJson ->
                when {
                    eventName.contains("permission", ignoreCase = true) -> {
                        val permission = parsePermissionRequest(dataJson)
                        if (permission != null && permission.id.isNotBlank()) {
                            val session = selectedSession
                            if (session == null || permission.sessionId == session.id) {
                                permissions =
                                    permissions.filterNot { it.id == permission.id } +
                                        permission
                            }
                        }
                    }
                    eventName.contains("message", ignoreCase = true) -> {
                        val session = selectedSession ?: return@events
                        val now = System.currentTimeMillis()
                        if (now - lastRefresh.get() >= 700) {
                            lastRefresh.set(now)
                            when (val result = client.messages(session.id)) {
                                is AiResult.Ok -> messages = result.value
                                is AiResult.Error -> Unit
                            }
                        }
                    }
                    eventName.contains("session", ignoreCase = true) -> {
                        val now = System.currentTimeMillis()
                        if (now - lastRefresh.get() >= 700) {
                            lastRefresh.set(now)
                            when (val result = client.listSessions()) {
                                is AiResult.Ok -> sessions = result.value
                                is AiResult.Error -> Unit
                            }
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The event stream died unexpectedly; the poll after each send
            // and the refresh action keep the chat usable.
        }
    }

    // Load the diff for the message whose "Changes" action was tapped.
    LaunchedEffect(diffFor) {
        val message = diffFor ?: return@LaunchedEffect
        val session = selectedSession
        val currentClient = ai.client()
        if (session == null || currentClient == null) {
            diffFor = null
            return@LaunchedEffect
        }
        diffContent = null
        when (val result = currentClient.sessionDiff(session.id, messageId = message.id)) {
            is AiResult.Ok -> diffContent = result.value
            is AiResult.Error -> {
                ctx.controller.report("load changes", result)
                diffFor = null
            }
        }
    }

    // Follow the conversation as it grows.
    LaunchedEffect(signatureOf(messages), permissions.size) {
        val last = listState.layoutInfo.totalItemsCount - 1
        if (last >= 0) {
            listState.animateScrollToItem(last)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(ctx.snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = selectedSession?.title?.takeIf { it.isNotBlank() }
                            ?: "AI Agent",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = { showSessions = true },
                        enabled = serverReady && workspace != null
                    ) {
                        Icon(Icons.Filled.Forum, contentDescription = "Sessions")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { refresh() },
                        enabled = serverReady && !sending && !streaming
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                    }
                    Box {
                        var menuOpen by remember { mutableStateOf(false) }
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("API keys") },
                                leadingIcon = {
                                    Icon(Icons.Filled.Key, contentDescription = null)
                                },
                                onClick = {
                                    menuOpen = false
                                    onOpenProviders()
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
                AiErrorCard(
                    message = message,
                    onDismiss = ctx.dismissError,
                    modifier = Modifier.padding(
                        start = 16.dp, top = 8.dp, end = 16.dp
                    )
                )
            }

            val serverFailure = serverError
            when {
                workspace == null -> EmptyAiWorkspaceState(Modifier.weight(1f))
                serverFailure != null -> AiServerFailedCard(
                    reason = serverFailure,
                    busy = startingServer,
                    onRetry = {
                        serverError = null
                        startServer()
                    },
                    modifier = Modifier.weight(1f)
                )
                !serverReady -> AiLoadingBox(
                    modifier = Modifier.weight(1f),
                    label = "Starting AI server…"
                )
                else -> {
                    // Model and agent (mode) pickers (plan #58).
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ModelDropdown(
                            providers = providers,
                            selected = selectedModel,
                            enabled = !sending,
                            onSelect = { selectedModel = it },
                            modifier = Modifier.weight(1f)
                        )
                        AgentDropdown(
                            selected = agentOption,
                            enabled = !sending,
                            onSelect = { agentOption = it }
                        )
                    }

                    Box(modifier = Modifier.weight(1f)) {
                        if (loadingMessages && messages.isEmpty()) {
                            AiLoadingBox(label = "Loading messages…")
                        } else {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (messages.isEmpty()) {
                                    item(key = "empty") {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 32.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            AiHintText(
                                                "Ask the agent about this project. " +
                                                    "Plan designs a change; Agent edits files."
                                            )
                                        }
                                    }
                                }
                                items(messages, key = { it.id }) { message ->
                                    MessageBubble(
                                        message = message,
                                        onShowChanges =
                                            if (message.role == "assistant") {
                                                { diffFor = message }
                                            } else {
                                                null
                                            },
                                        changesEnabled = true
                                    )
                                }
                                permissions.forEach { permission ->
                                    item(key = "permission-${permission.id}") {
                                        PermissionCard(
                                            permission = permission,
                                            busy = respondingTo == permission.id,
                                            onRespond = { allow, rememberChoice ->
                                                respond(permission, allow, rememberChoice)
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Input row: send on the send key, Stop while streaming.
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                start = 16.dp, top = 4.dp,
                                end = 16.dp, bottom = 12.dp
                            ),
                        verticalAlignment = Alignment.Bottom
                    ) {
                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it },
                            label = { Text("Message") },
                            enabled = !sending,
                            modifier = Modifier.weight(1f),
                            minLines = 1,
                            maxLines = 5,
                            keyboardOptions = KeyboardOptions(
                                imeAction = ImeAction.Send
                            ),
                            keyboardActions = KeyboardActions(
                                onSend = { send() }
                            )
                        )
                        Spacer(Modifier.width(8.dp))
                        if (streaming) {
                            IconButton(
                                onClick = { stop() },
                                enabled = !sending
                            ) {
                                Icon(
                                    Icons.Filled.Stop,
                                    contentDescription = "Stop generating"
                                )
                            }
                        } else {
                            IconButton(
                                onClick = { send() },
                                enabled = !sending && input.isNotBlank()
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.Send,
                                    contentDescription = "Send message"
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showSessions) {
        SessionsDialog(
            sessions = sessions,
            selectedSessionId = selectedSession?.id,
            busy = !serverReady || sending || streaming,
            onDismiss = { showSessions = false },
            onSelect = { selectSession(it) },
            onNewSession = { newSession() },
            onDeleteSession = { deleteSession(it) }
        )
    }

    val diffMessage = diffFor
    if (diffMessage != null) {
        AiDiffSheet(
            diff = diffContent,
            onDismiss = {
                diffFor = null
                diffContent = null
            }
        )
    }
}

/**
 * Provider API-key page (plan #51, #67): per-provider expandable rows with a
 * masked key field. The key is sent to the local OpenCode server as
 * {"type":"api","key":…}, cleared immediately after a successful save and
 * never echoed back.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProvidersPage(
    ctx: AiPageContext,
    ai: OpenCodeService,
    onBack: () -> Unit
) {
    var providers by remember { mutableStateOf<List<AiProvider>?>(null) }
    var auth by remember { mutableStateOf<Map<String, List<String>>?>(null) }
    var expandedId by remember { mutableStateOf<String?>(null) }
    var keyInput by remember { mutableStateOf("") }
    var keyVisible by remember { mutableStateOf(false) }
    var savingFor by remember { mutableStateOf<String?>(null) }

    fun load() {
        ctx.controller.launchAction("load providers") {
            val currentClient = ai.client()
            if (currentClient == null) {
                ctx.controller.reportDetail("AI server is not running.")
                return@launchAction
            }
            providers = ctx.controller.call("load providers") {
                currentClient.listProviders()
            } ?: providers
            auth = ctx.controller.call("load provider auth") {
                currentClient.listProviderAuth()
            } ?: auth
        }
    }

    LaunchedEffect(Unit) { load() }

    fun saveKey(provider: AiProvider) {
        val key = keyInput.trim()
        if (key.isEmpty()) return
        val currentClient = ai.client()
        if (currentClient == null) {
            ctx.controller.reportDetail("AI server is not running.")
            return
        }
        savingFor = provider.id
        ctx.controller.launchAction("save API key") {
            try {
                // Built with a JSON builder so the key is always escaped
                // correctly; the value is never logged or echoed (plan #67).
                val body = buildJsonObject {
                    put("type", "api")
                    put("key", key)
                }.toString()
                when (val result = currentClient.setProviderAuth(provider.id, body)) {
                    is AiResult.Ok -> {
                        // Drop the in-memory copy immediately.
                        keyInput = ""
                        keyVisible = false
                        auth = ctx.controller.call("load provider auth") {
                            currentClient.listProviderAuth()
                        } ?: auth
                        ctx.snackbarHostState.showSnackbar(
                            "API key saved for " +
                                provider.name.ifBlank { provider.id }
                        )
                    }
                    is AiResult.Error ->
                        ctx.controller.report("save API key", result)
                }
            } finally {
                savingFor = null
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(ctx.snackbarHostState) },
        topBar = {
            AiTopBar(
                title = "AI Providers",
                onBack = onBack,
                onRefresh = { load() },
                refreshEnabled = savingFor == null
            )
        }
    ) { padding ->
        val currentProviders = providers
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ctx.error?.let { message ->
                item(key = "error") {
                    AiErrorCard(message = message, onDismiss = ctx.dismissError)
                }
            }

            if (currentProviders == null) {
                item(key = "loading") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
            } else if (currentProviders.isEmpty()) {
                item(key = "empty") {
                    AiHintText("No AI providers are available.")
                }
            } else {
                items(currentProviders, key = { it.id }) { provider ->
                    val authMap = auth
                    ProviderCard(
                        provider = provider,
                        hasKey = authMap?.get(provider.id)?.isNotEmpty() == true,
                        authChecked = authMap != null,
                        expanded = expandedId == provider.id,
                        busy = savingFor == provider.id,
                        keyValue = keyInput,
                        onKeyChange = { keyInput = it },
                        keyVisible = keyVisible,
                        onToggleKeyVisibility = { keyVisible = !keyVisible },
                        onToggleExpanded = {
                            keyInput = ""
                            keyVisible = false
                            expandedId = if (expandedId == provider.id) null else provider.id
                        },
                        onSave = { saveKey(provider) }
                    )
                }
            }
        }
    }
}

/** One expandable provider row with the masked API-key field (plan #67). */
@Composable
private fun ProviderCard(
    provider: AiProvider,
    hasKey: Boolean,
    authChecked: Boolean,
    expanded: Boolean,
    busy: Boolean,
    keyValue: String,
    onKeyChange: (String) -> Unit,
    keyVisible: Boolean,
    onToggleKeyVisibility: () -> Unit,
    onToggleExpanded: () -> Unit,
    onSave: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.Key,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = provider.name.ifBlank { provider.id },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = when {
                            !authChecked -> "${provider.models.size} models"
                            hasKey -> "API key configured"
                            else -> "No API key · ${provider.models.size} models"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (authChecked && hasKey) {
                            MaterialTheme.colorScheme.tertiary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
                Icon(
                    imageVector = if (expanded) {
                        Icons.Filled.KeyboardArrowUp
                    } else {
                        Icons.Filled.KeyboardArrowDown
                    },
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (expanded) {
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = keyValue,
                    onValueChange = onKeyChange,
                    label = { Text("API key") },
                    singleLine = true,
                    enabled = !busy,
                    visualTransformation = if (keyVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    trailingIcon = {
                        IconButton(
                            onClick = onToggleKeyVisibility,
                            enabled = !busy
                        ) {
                            Icon(
                                imageVector = if (keyVisible) {
                                    Icons.Filled.VisibilityOff
                                } else {
                                    Icons.Filled.Visibility
                                },
                                contentDescription = if (keyVisible) {
                                    "Hide key"
                                } else {
                                    "Show key"
                                }
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "The key is stored by OpenCode on this device and is " +
                        "never shown again.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onSave,
                    enabled = !busy && keyValue.isNotBlank()
                ) {
                    if (busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("Save API key")
                }
            }
        }
    }
}
