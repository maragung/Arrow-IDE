package com.maragung.arrowide.ui.process

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.maragung.arrowide.process.ListeningPort
import com.maragung.arrowide.process.PortScanner

/**
 * Port Manager (plan #27/#28): the app's own listening TCP sockets, each
 * with its localhost URL, owning session and open / copy / stop actions.
 * The scan comes from [PortScanner] (real /proc data — only this app's
 * uid), refreshed on entry and via the refresh button; the session list is
 * re-read so port -> session mapping stays current.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PortsScreen(
    portScanner: PortScanner,
    sessionIdsToTitles: (List<Int>) -> List<String>,
    onKillSessions: (List<Int>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostState() }
    var refreshKey by remember { mutableIntStateOf(0) }
    var ports by remember { mutableStateOf<List<ListeningPort>>(emptyList()) }
    var message by remember { mutableStateOf<String?>(null) }

    // Re-scan on entry, on manual refresh. The actual session list is
    // supplied by the caller through ports' sessionIds each scan.
    LaunchedEffect(refreshKey) {
        ports = portScanner.scan(emptyList())
    }

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            message = null
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Ports") },
                actions = {
                    IconButton(onClick = { refreshKey++ }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh ports")
                    }
                },
            )
        },
    ) { padding ->
        if (ports.isEmpty()) {
            Box(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No listening ports.\nStart a dev server in the terminal (e.g. npm run dev).",
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
                items(ports, key = { "${it.protocol}-${it.port}" }) { port ->
                    PortRow(
                        port = port,
                        ownerTitles = sessionIdsToTitles(port.sessionIds),
                        onOpen = {
                            val url = "http://127.0.0.1:${port.port}"
                            try {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(url)),
                                )
                            } catch (e: Exception) {
                                message = "No browser available for $url"
                            }
                        },
                        onCopy = {
                            val url = "http://127.0.0.1:${port.port}"
                            clipboard.setText(AnnotatedString(url))
                            message = "Copied $url"
                        },
                        onStop = { onKillSessions(port.sessionIds) },
                    )
                }
            }
        }
    }
}

/** One listening-port card: port, URL, owner, open/copy/stop actions. */
@Composable
private fun PortRow(
    port: ListeningPort,
    ownerTitles: List<String>,
    onOpen: () -> Unit,
    onCopy: () -> Unit,
    onStop: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${port.port}  (${port.protocol})",
                        style = MaterialTheme.typography.bodyLarge,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(
                        text = "http://127.0.0.1:${port.port}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = when {
                            ownerTitles.isNotEmpty() -> ownerTitles.joinToString(", ")
                            else -> "unknown process"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = onOpen) {
                    Icon(Icons.Filled.Public, contentDescription = "Open in browser")
                }
                IconButton(onClick = onCopy) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = "Copy URL")
                }
                if (port.sessionIds.isNotEmpty()) {
                    IconButton(onClick = onStop) {
                        Icon(Icons.Filled.Close, contentDescription = "Stop process")
                    }
                }
            }
        }
    }
}
