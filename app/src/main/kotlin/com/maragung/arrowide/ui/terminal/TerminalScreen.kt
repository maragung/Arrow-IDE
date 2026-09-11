package com.maragung.arrowide.ui.terminal

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maragung.arrowide.terminal.TerminalSession
import com.maragung.arrowide.terminal.TerminalSessionManager
import java.io.File

/**
 * Full terminal panel: session selector bar on top, the terminal view in
 * the middle, and a special-keys row at the bottom.
 *
 * @param manager       session manager to render
 * @param newSessionCwd working directory for newly created sessions; null
 *                      falls back to the manager's default (terminal home).
 *                      The app shell passes the current workspace root here.
 * @param maxSessions   concurrent-session limit enforced by the manager
 * @param fontSizeDp    monospace font size in dp
 * @param autoCreate    when true, one session is created automatically the
 *                      first time the panel is shown with none open
 */
@Composable
fun TerminalScreen(
    manager: TerminalSessionManager,
    modifier: Modifier = Modifier,
    newSessionCwd: File? = null,
    maxSessions: Int = TerminalDefaults.MAX_SESSIONS,
    fontSizeDp: Int = TerminalDefaults.FONT_SIZE_DP,
    autoCreate: Boolean = true,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    val sessions by manager.sessions.collectAsState()
    var selectedId by remember { mutableStateOf<Int?>(null) }
    var terminalView by remember { mutableStateOf<ArrowTerminalView?>(null) }
    var autoCreated by remember { mutableStateOf(false) }

    val selected: TerminalSession? =
        sessions.firstOrNull { it.id == selectedId } ?: sessions.lastOrNull()

    fun createSession() {
        val cwd = newSessionCwd ?: manager.defaultCwd
        try {
            val session = manager.createSession(cwd, maxSessions)
            selectedId = session.id
        } catch (e: IllegalStateException) {
            Toast.makeText(context, e.message, Toast.LENGTH_SHORT).show()
        }
    }

    // Keep a valid selection whenever the list changes.
    LaunchedEffect(sessions) {
        if (sessions.none { it.id == selectedId }) {
            selectedId = sessions.lastOrNull()?.id
        }
    }

    // Open the first session automatically.
    LaunchedEffect(autoCreate) {
        if (autoCreate && !autoCreated && sessions.isEmpty()) {
            autoCreated = true
            createSession()
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        SessionBar(
            sessions = sessions,
            selected = selected,
            onSelect = { selectedId = it.id },
            onNew = { createSession() },
            onClose = { selected?.let(manager::closeSession) },
            onClear = { selected?.write(CLEAR_SCREEN_SEQUENCE) },
            onRestart = {
                val current = selected ?: return@SessionBar
                val cwd = current.cwd
                try {
                    manager.killSession(current)
                    val session = manager.createSession(cwd, maxSessions)
                    selectedId = session.id
                } catch (e: IllegalStateException) {
                    Toast.makeText(context, e.message, Toast.LENGTH_SHORT).show()
                }
            },
        )

        HorizontalDivider()

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color(TerminalDefaults.BACKGROUND_COLOR)),
        ) {
            val session = selected
            if (session != null) {
                key(session.id) {
                    TerminalViewCompat(
                        session = session,
                        modifier = Modifier.fillMaxSize(),
                        fontSizeDp = fontSizeDp,
                        onViewHolder = { terminalView = it },
                    )
                }
            } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    TextButton(onClick = { createSession() }) {
                        Text("No terminal sessions — tap to open one")
                    }
                }
            }
        }

        SpecialKeysRow(
            session = selected,
            view = terminalView,
            onCopy = {
                selected?.let { clipboard.setText(AnnotatedString(it.getTranscriptText())) }
            },
            onPaste = {
                val text = clipboard.getText()?.text
                if (!text.isNullOrEmpty()) {
                    selected?.write(text)
                }
            },
            onToggleKeyboard = {
                val view = terminalView
                if (view != null) {
                    view.requestFocus()
                    view.showSoftInput()
                }
            },
        )
    }
}

@Composable
private fun SessionBar(
    sessions: List<TerminalSession>,
    selected: TerminalSession?,
    onSelect: (TerminalSession) -> Unit,
    onNew: () -> Unit,
    onClose: () -> Unit,
    onClear: () -> Unit,
    onRestart: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val selectedTitle by (selected?.title
        ?: remember { kotlinx.coroutines.flow.MutableStateFlow("") }).collectAsState()
    val exitCode by (selected?.exitCode
        ?: remember { kotlinx.coroutines.flow.MutableStateFlow<Int?>(null) }).collectAsState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            TextButton(onClick = { menuOpen = true }) {
                Text(
                    text = sessionLabel(sessions.size, selectedTitle, exitCode),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                if (sessions.isEmpty()) {
                    DropdownMenuItem(
                        text = { Text("No sessions") },
                        onClick = { menuOpen = false },
                    )
                }
                for (session in sessions) {
                    DropdownMenuItem(
                        text = { Text(sessionTabLabel(session)) },
                        onClick = {
                            onSelect(session)
                            menuOpen = false
                        },
                    )
                }
            }
        }

        Text(
            text = "${sessions.size}",
            style = MaterialTheme.typography.labelSmall,
            color = LocalContentColor.current.copy(alpha = 0.6f),
            modifier = Modifier.padding(start = 2.dp),
        )

        Box(modifier = Modifier.weight(1f))

        IconButton(onClick = onNew) {
            Icon(Icons.Filled.Add, contentDescription = "New session")
        }
        IconButton(onClick = onClose, enabled = selected != null) {
            Icon(Icons.Filled.Close, contentDescription = "Close session")
        }
        IconButton(onClick = onClear, enabled = selected != null) {
            Icon(Icons.Filled.Clear, contentDescription = "Clear screen")
        }
        IconButton(onClick = onRestart, enabled = selected != null) {
            Icon(Icons.Filled.Refresh, contentDescription = "Restart session")
        }
    }
}

@Composable
private fun SpecialKeysRow(
    session: TerminalSession?,
    view: ArrowTerminalView?,
    onCopy: () -> Unit,
    onPaste: () -> Unit,
    onToggleKeyboard: () -> Unit,
) {
    val ctrlActive by (session?.ctrlModifier
        ?: remember { kotlinx.coroutines.flow.MutableStateFlow(false) }).collectAsState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .heightIn(min = 40.dp)
            .padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        KeyButton(
            text = "CTRL",
            highlighted = ctrlActive,
            enabled = session != null,
        ) {
            session?.let { it.setCtrlModifier(!it.ctrlModifier.value) }
        }
        for (special in SpecialKey.entries) {
            KeyButton(
                text = special.label,
                highlighted = ctrlActive && special != SpecialKey.ESC && special != SpecialKey.TAB,
                enabled = session != null,
            ) {
                val current = session ?: return@KeyButton
                current.write(
                    special.bytes(
                        appMode = view?.getKeypadApplicationMode() ?: false,
                        ctrl = ctrlActive,
                    ),
                )
            }
        }
        IconButton(onClick = onCopy, enabled = session != null) {
            Icon(Icons.Filled.ContentCopy, contentDescription = "Copy transcript")
        }
        IconButton(onClick = onPaste, enabled = session != null) {
            Icon(Icons.Filled.ContentPaste, contentDescription = "Paste")
        }
        IconButton(onClick = onToggleKeyboard) {
            Icon(Icons.Filled.Keyboard, contentDescription = "Show keyboard")
        }
    }
}

@Composable
private fun KeyButton(
    text: String,
    highlighted: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
        modifier = Modifier.heightIn(min = 36.dp),
    ) {
        Text(
            text = text,
            fontSize = 12.sp,
            fontWeight = if (highlighted) FontWeight.Bold else FontWeight.Normal,
            color = if (highlighted) {
                MaterialTheme.colorScheme.primary
            } else {
                LocalContentColor.current
            },
        )
    }
}

/** Special keys offered in the bottom row. */
private enum class SpecialKey(val label: String) {
    ESC("ESC"),
    TAB("TAB"),
    UP("↑"),
    DOWN("↓"),
    LEFT("←"),
    RIGHT("→"),
    HOME("HOME"),
    END("END"),
    PGUP("PGUP"),
    PGDN("PGDN"),
    SLASH("/"),
    MINUS("-"),
    PIPE("|"),
    ;

    /**
     * The bytes sent for this key. Application cursor-key mode (set by
     * programs like vim) changes arrow/home/end sequences; a held CTRL
     * modifier produces the modified variants.
     */
    fun bytes(appMode: Boolean, ctrl: Boolean): ByteArray = when (this) {
        ESC -> "\u001b"
        TAB -> "\t"
        UP -> when {
            ctrl -> "\u001b[1;5A"
            appMode -> "\u001bOA"
            else -> "\u001b[A"
        }
        DOWN -> when {
            ctrl -> "\u001b[1;5B"
            appMode -> "\u001bOB"
            else -> "\u001b[B"
        }
        RIGHT -> when {
            ctrl -> "\u001b[1;5C"
            appMode -> "\u001bOC"
            else -> "\u001b[C"
        }
        LEFT -> when {
            ctrl -> "\u001b[1;5D"
            appMode -> "\u001bOD"
            else -> "\u001b[D"
        }
        HOME -> when {
            ctrl -> "\u001b[1;5H"
            appMode -> "\u001bOH"
            else -> "\u001b[H"
        }
        END -> when {
            ctrl -> "\u001b[1;5F"
            appMode -> "\u001bOF"
            else -> "\u001b[F"
        }
        PGUP -> if (ctrl) "\u001b[5;5~" else "\u001b[5~"
        PGDN -> if (ctrl) "\u001b[6;5~" else "\u001b[6~"
        SLASH -> "/"
        MINUS -> "-"
        PIPE -> "|"
    }.toByteArray()
}

private const val CLEAR_SCREEN_SEQUENCE: String = "\u001b[3J\u001b[2J\u001b[H"

private fun sessionLabel(count: Int, title: String, exitCode: Int?): String {
    val suffix = exitCode?.let { " (exited: $it)" } ?: ""
    return if (title.isBlank()) "Terminal ($count)$suffix" else "$title$suffix"
}

private fun sessionTabLabel(session: TerminalSession): String {
    val exit = if (session.isAlive) "" else " (exited)"
    return "#${session.id} ${session.title.value}$exit"
}
