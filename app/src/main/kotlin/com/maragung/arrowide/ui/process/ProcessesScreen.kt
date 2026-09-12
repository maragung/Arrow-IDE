package com.maragung.arrowide.ui.process

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.maragung.arrowide.process.ProcessManager
import com.maragung.arrowide.terminal.TerminalSessionManager
import java.io.File

/**
 * Background Process Manager (plan #25): every live terminal session as a
 * process row — title, pid, working directory, running/exited state.
 *
 * Output lives in the session's terminal ("View" navigates there); Stop
 * kills the session through [TerminalSessionManager]; "New terminal here"
 * opens a fresh session in the same directory. Only the app's own
 * processes ever appear (they come from the app's own session list).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProcessesScreen(
    processManager: ProcessManager,
    terminalManager: TerminalSessionManager,
    onOpenTerminal: () -> Unit,
    modifier: Modifier = Modifier,
    newSessionMaxSessions: Int = 5,
) {
    val processes by processManager.processes.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var limitMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(limitMessage) {
        limitMessage?.let {
            snackbarHostState.showSnackbar(it)
            limitMessage = null
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = { TopAppBar(title = { Text("Processes") }) },
    ) { padding ->
        if (processes.isEmpty()) {
            Box(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No running processes.\nTerminal sessions appear here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(16.dp),
            ) {
                items(processes, key = { it.sessionId }) { process ->
                    ProcessRow(
                        process = process,
                        onOpenOutput = onOpenTerminal,
                        onStop = {
                            terminalManager.sessions.value
                                .firstOrNull { it.id == process.sessionId }
                                ?.let(terminalManager::killSession)
                        },
                        onNewHere = {
                            try {
                                terminalManager.createSession(
                                    File(process.cwdPath),
                                    newSessionMaxSessions,
                                )
                                onOpenTerminal()
                            } catch (e: IllegalStateException) {
                                limitMessage = e.message
                            }
                        },
                    )
                }
            }
        }
    }
}
