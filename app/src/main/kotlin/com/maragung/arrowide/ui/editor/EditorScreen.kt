package com.maragung.arrowide.ui.editor

import android.graphics.Typeface
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.maragung.arrowide.editor.EditorTab
import com.maragung.arrowide.editor.EditorTabManager
import com.maragung.arrowide.editor.FileContent
import com.maragung.arrowide.editor.FileContentIO
import com.maragung.arrowide.editor.FileEncoding
import com.maragung.arrowide.editor.LineEnding
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.event.SelectionChangeEvent
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.EditorSearcher
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Files above this size are opened read-only with a warning banner. */
private const val LARGE_FILE_BYTES: Long = 1024L * 1024L

/**
 * The code editor surface: a scrollable tab strip on top, a single Sora
 * Editor [CodeEditor] below, bound to the active tab of [tabManager].
 *
 * Features:
 * - syntax highlighting via TextMate (assets under `assets/textmate/`), dark
 *   TextMate color scheme when the app theme is dark;
 * - monospace typeface, word wrap off, line numbers on;
 * - text changes mark the tab dirty; the Save button (and, when
 *   [autoSaveOnTabSwitch] is on, switching tabs) writes the buffer back with
 *   [FileContentIO] (atomic, encoding/BOM/line-ending preserving);
 * - unsaved buffers survive tab switches (the buffer is re-bound, not
 *   re-read from disk);
 * - undo/redo wired to the editor's undo manager;
 * - a togglable find bar (next/previous match, case sensitivity toggle);
 * - an "Ask AI" action (enabled while text is selected) that hands the
 *   selected code plus the file name to [onAskAi] as a ready-made context
 *   prompt;
 * - files larger than 1 MB open read-only with a warning banner;
 * - cursor position and live buffer text are continuously pushed into
 *   [tabManager], so the app shell can persist
 *   [EditorTabManager.snapshotForRecovery] at onStop.
 *
 * @param tabManager the shared tab manager (owned by the app shell / a
 *   ViewModel; outlives this composable).
 * @param autoSaveOnTabSwitch when true, a dirty tab is saved automatically
 *   when the user switches away from it. Default off.
 * @param onAskAi invoked with a context prompt (see [buildInlineAiPrompt])
 *   built from the active file's name and the current editor selection.
 *   Default no-op; the app shell wires this to AI chat navigation.
 */
@Composable
fun EditorScreen(
    tabManager: EditorTabManager,
    modifier: Modifier = Modifier,
    autoSaveOnTabSwitch: Boolean = false,
    onAskAi: (prompt: String) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val tabs by tabManager.tabs.collectAsState()
    val activeTab by tabManager.activeTab.collectAsState()

    // Follows the app's active theme (dark => darcula TextMate scheme).
    val darkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f

    // ---- TextMate one-time initialization --------------------------------
    var textMateReady by remember { mutableStateOf(TextMateSetup.isInitialized) }
    LaunchedEffect(Unit) {
        if (!TextMateSetup.isInitialized) {
            TextMateSetup.initialize(context)
        }
        textMateReady = TextMateSetup.isInitialized
    }

    // ---- per-tab loaded state ---------------------------------------------
    // FileContent of the active tab once read from disk (null while loading).
    var activeFileContent by remember { mutableStateOf<FileContent?>(null) }
    // Tab id the current activeFileContent/loadError belong to.
    var loadedTabId by remember { mutableStateOf<String?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var isLargeFile by remember { mutableStateOf(false) }

    // Per-tab disk metadata (encoding/BOM/line ending) so saving round-trips.
    val fileMetas = remember { mutableMapOf<String, FileContent>() }

    // Current cursor of the editor (status line; also pushed to tabManager).
    var cursorLine by remember { mutableStateOf(0) }
    var cursorColumn by remember { mutableStateOf(0) }
    // Whether the editor currently has a non-collapsed selection. The text
    // itself is read from the live editor only when Ask AI is pressed, so
    // selection events stay cheap.
    var hasSelection by remember { mutableStateOf(false) }

    // Find bar state.
    var showFindBar by remember { mutableStateOf(false) }
    var findQuery by remember { mutableStateOf("") }
    var matchCase by remember { mutableStateOf(false) }

    // ---- editor view + binding bookkeeping --------------------------------
    val holder = remember { EditorViewHolder() }
    var editorState by remember { mutableStateOf<CodeEditor?>(null) }
    val previousTab = remember { mutableStateOf<EditorTab?>(null) }

    fun saveActiveTab() {
        val tab = activeTab ?: return
        saveTabNow(tab, tabManager, fileMetas, scope) { success, message ->
            saveError = if (success) null else message
        }
    }

    // Load the active tab's content (and auto-save the previous tab).
    LaunchedEffect(activeTab?.id) {
        val previous = previousTab.value
        if (autoSaveOnTabSwitch && previous != null && previous.isDirty) {
            saveTabNow(previous, tabManager, fileMetas, scope) { _, _ -> }
        }
        previousTab.value = activeTab

        activeFileContent = null
        loadError = null
        saveError = null
        isLargeFile = false
        val tab = activeTab ?: return@LaunchedEffect
        // An unsaved buffer survives tab switches: re-bind it instead of
        // re-reading the (stale) file from disk.
        val buffered = tabManager.contentOf(tab.id)
        val read = withContext(Dispatchers.IO) {
            runCatching { FileContentIO.read(tab.file) }
        }
        if (tabManager.activeTab.value?.id != tab.id) return@LaunchedEffect // stale load
        loadedTabId = tab.id
        read.fold(
            onSuccess = { content ->
                fileMetas[tab.id] = content
                activeFileContent = if (tab.isDirty && buffered != null) {
                    FileContent(
                        text = buffered,
                        lineEnding = content.lineEnding,
                        encoding = content.encoding,
                        hadBom = content.hadBom
                    )
                } else {
                    content
                }
                isLargeFile = tab.file.length() > LARGE_FILE_BYTES
            },
            onFailure = { e ->
                loadError = e.message ?: "Failed to read ${tab.displayName}"
            }
        )
    }

    Column(modifier = modifier.fillMaxSize()) {
        EditorTabStrip(
            tabs = tabs,
            activeTabId = activeTab?.id,
            onActivate = { tabManager.setActive(it) },
            onClose = { tabManager.closeTab(it) }
        )

        EditorToolbar(
            canSave = activeTab?.isDirty == true,
            canUndo = editorState?.canUndo() == true,
            canRedo = editorState?.canRedo() == true,
            findActive = showFindBar,
            canAskAi = hasSelection && activeTab != null,
            onSave = { saveActiveTab() },
            onUndo = { editorState?.undo() },
            onRedo = { editorState?.redo() },
            onToggleFind = {
                showFindBar = !showFindBar
                if (!showFindBar) editorState?.searcher?.stopSearch()
            },
            onAskAi = {
                val editor = editorState
                val tab = activeTab
                // Read the live selection at press time (it may have changed
                // since the last recomposition); a collapsed selection is a
                // no-op.
                if (editor != null && tab != null && editor.cursor.isSelected) {
                    val selection = editor.text.substring(
                        editor.cursor.left,
                        editor.cursor.right
                    )
                    if (selection.isNotEmpty()) {
                        onAskAi(buildInlineAiPrompt(tab.displayName, selection))
                    }
                }
            }
        )

        if (showFindBar) {
            FindBar(
                query = findQuery,
                matchCase = matchCase,
                onQueryChange = { query ->
                    findQuery = query
                    val editor = editorState
                    if (editor != null) {
                        if (query.isEmpty()) {
                            editor.searcher.stopSearch()
                        } else {
                            runCatching {
                                editor.searcher.search(
                                    query,
                                    EditorSearcher.SearchOptions(!matchCase, false)
                                )
                            }
                        }
                    }
                },
                onMatchCaseToggle = {
                    matchCase = !matchCase
                    val editor = editorState
                    val query = findQuery
                    if (editor != null && query.isNotEmpty()) {
                        runCatching {
                            editor.searcher.search(
                                query,
                                EditorSearcher.SearchOptions(!matchCase, false)
                            )
                        }
                    }
                },
                onNext = { editorState?.searcher?.gotoNext() },
                onPrevious = { editorState?.searcher?.gotoPrevious() },
                onClose = {
                    showFindBar = false
                    editorState?.searcher?.stopSearch()
                }
            )
        }

        if (isLargeFile) {
            WarningBanner("File is larger than 1 MB — opened read-only")
        }
        loadError?.let { WarningBanner(it) }
        saveError?.let { WarningBanner("Save failed: $it") }

        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    CodeEditor(ctx).apply {
                        // Static configuration.
                        setTypefaceText(Typeface.MONOSPACE)
                        setWordwrap(false)
                        setLineNumberEnabled(true)
                        setTextSize(14f)
                        setColorScheme(TextMateSetup.createColorScheme())

                        subscribeEvent(ContentChangeEvent::class.java) { event, _ ->
                            val tabId = holder.activeTabId ?: return@subscribeEvent
                            if (event.action == ContentChangeEvent.ACTION_SET_NEW_TEXT) {
                                // Our own programmatic setText(); content is
                                // pushed explicitly when binding a tab.
                                if (!holder.programmaticChange) {
                                    tabManager.updateContent(tabId, text.toString())
                                }
                            } else {
                                tabManager.markDirty(tabId)
                                tabManager.updateContent(tabId, text.toString())
                            }
                            // Note: no explicit recomposition trigger needed
                            // here; markDirty emits a new tabs value, which
                            // recomposes this screen (refreshing undo/redo
                            // button enabled states).
                        }
                        subscribeEvent(SelectionChangeEvent::class.java) { _, _ ->
                            val tabId = holder.activeTabId ?: return@subscribeEvent
                            val line = cursor.leftLine
                            val column = cursor.leftColumn
                            tabManager.updateCursor(tabId, line, column)
                            cursorLine = line
                            cursorColumn = column
                            hasSelection = cursor.isSelected
                        }

                        holder.editor = this
                        editorState = this
                    }
                },
                update = { editor ->
                    val tab = activeTab
                    holder.activeTabId = tab?.id
                    editor.visibility = if (tab == null) View.INVISIBLE else View.VISIBLE

                    // Dark TextMate scheme when the app theme is dark, plain
                    // scheme otherwise.
                    if (holder.colorSchemeIsDark != darkTheme) {
                        holder.colorSchemeIsDark = darkTheme
                        editor.setColorScheme(
                            if (darkTheme) {
                                TextMateSetup.createColorScheme()
                            } else {
                                EditorColorScheme()
                            }
                        )
                    }

                    if (tab != null && loadedTabId == tab.id) {
                        // Bind content once per tab (or after a reload).
                        val content = activeFileContent
                        if (content != null && holder.boundContentTabId != tab.id) {
                            holder.boundContentTabId = tab.id
                            holder.programmaticChange = true
                            editor.setEditable(!isLargeFile && loadError == null)
                            editor.setText(content.text)
                            // Restore the cursor when re-binding an unsaved
                            // buffer (the cursor was recorded against exactly
                            // this text); fresh loads start at the top.
                            val savedCursor =
                                if (tab.isDirty) tabManager.cursorOf(tab.id) else null
                            if (savedCursor != null) {
                                editor.setSelection(savedCursor.first, savedCursor.second)
                            } else {
                                editor.setSelection(0, 0)
                            }
                            holder.programmaticChange = false
                            tabManager.updateContent(tab.id, content.text)
                            if (savedCursor == null) {
                                tabManager.updateCursor(tab.id, 0, 0)
                            }
                        }
                        // Bind the TextMate language once per tab.
                        if (textMateReady && holder.boundLanguageTabId != tab.id) {
                            holder.boundLanguageTabId = tab.id
                            val language = if (tab.languageId.isNotEmpty()) {
                                runCatching { TextMateLanguage.create(tab.languageId, true) }
                                    .getOrNull()
                            } else {
                                null
                            }
                            // setEditorLanguage(null) falls back to EmptyLanguage.
                            editor.setEditorLanguage(language)
                        }
                    } else if (tab == null) {
                        holder.boundContentTabId = null
                        holder.boundLanguageTabId = null
                    }
                }
            )

            // Empty state overlay (no tabs open).
            if (activeTab == null) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Open a file from the Explorer",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Status line.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 8.dp, vertical = 2.dp)
        ) {
            Text(
                text = "Ln ${cursorLine + 1}, Col ${cursorColumn + 1}",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = activeTab?.let { if (it.isDirty) "Modified" else "Saved" } ?: "No file",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    // Release the editor when leaving composition for good.
    DisposableEffect(Unit) {
        onDispose {
            holder.editor?.release()
            holder.editor = null
            editorState = null
        }
    }
}

/**
 * Persists [tab]'s buffer via [FileContentIO] (atomic write that preserves the
 * encoding/BOM/line ending recorded when the file was read) and clears the
 * tab's dirty flag on success. [onResult] is invoked with (success, message).
 */
private fun saveTabNow(
    tab: EditorTab,
    tabManager: EditorTabManager,
    fileMetas: Map<String, FileContent>,
    scope: CoroutineScope,
    onResult: (Boolean, String?) -> Unit
) {
    val text = tabManager.contentOf(tab.id)
    if (text == null) {
        onResult(false, "no buffered content for ${tab.displayName}")
        return
    }
    val meta = fileMetas[tab.id]
    val content = FileContent(
        text = text,
        lineEnding = meta?.lineEnding ?: LineEnding.LF,
        encoding = meta?.encoding ?: FileEncoding.UTF_8,
        hadBom = meta?.hadBom ?: false
    )
    scope.launch {
        val ok = withContext(Dispatchers.IO) {
            runCatching { FileContentIO.write(tab.file, content) }.isSuccess
        }
        if (ok) {
            tabManager.markClean(tab.id)
            onResult(true, null)
        } else {
            onResult(false, "could not write ${tab.file.path}")
        }
    }
}

/** Selections longer than this are truncated in [buildInlineAiPrompt]. */
private const val INLINE_AI_MAX_SELECTION_CHARS: Int = 8000

/**
 * Builds the Inline AI context prompt (plan #88) from the active file's name
 * and the currently selected code: a "Context — file <name>:" header followed
 * by the selected code inside a triple-backtick fence, ending with a blank
 * line.
 *
 * This is the context block only — the AI screen pre-fills it into the chat
 * input and the user's question is added on top. Selections longer than
 * [INLINE_AI_MAX_SELECTION_CHARS] are truncated honestly: the marker
 * "... (truncated)" is appended so the AI knows code was cut.
 */
internal fun buildInlineAiPrompt(fileName: String, selection: String): String {
    val code = if (selection.length > INLINE_AI_MAX_SELECTION_CHARS) {
        selection.take(INLINE_AI_MAX_SELECTION_CHARS) + "... (truncated)"
    } else {
        selection
    }
    return "Context — file $fileName:\n```\n$code\n```\n\n"
}

/** Bookkeeping attached to the single long-lived [CodeEditor]. Main-thread only. */
private class EditorViewHolder {
    var editor: CodeEditor? = null
    /** Tab id whose text changes are currently attributed to. */
    var activeTabId: String? = null
    /** Tab id whose content was last pushed via setText. */
    var boundContentTabId: String? = null
    /** Tab id whose language was last bound. */
    var boundLanguageTabId: String? = null
    /** True while our own setText() dispatches its ContentChangeEvent. */
    var programmaticChange: Boolean = false
    /** Whether the applied color scheme is the dark one. */
    var colorSchemeIsDark: Boolean? = null
}

@Composable
private fun EditorTabStrip(
    tabs: List<EditorTab>,
    activeTabId: String?,
    onActivate: (String) -> Unit,
    onClose: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (tabs.isEmpty()) {
            Text(
                text = "No open files",
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        tabs.forEach { tab ->
            val isActive = tab.id == activeTabId
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (isActive) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            MaterialTheme.colorScheme.surface
                        }
                    )
                    .clickable { onActivate(tab.id) }
                    .padding(start = 10.dp, end = 2.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = tab.displayName,
                    fontSize = 13.sp,
                    fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isActive) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1
                )
                if (tab.isDirty) {
                    Spacer(Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
                IconButton(
                    onClick = { onClose(tab.id) },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Close ${tab.displayName}",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun EditorToolbar(
    canSave: Boolean,
    canUndo: Boolean,
    canRedo: Boolean,
    findActive: Boolean,
    canAskAi: Boolean,
    onSave: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onToggleFind: () -> Unit,
    onAskAi: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onSave, enabled = canSave) {
            Icon(Icons.Filled.Save, contentDescription = "Save")
        }
        IconButton(onClick = onUndo, enabled = canUndo) {
            Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo")
        }
        IconButton(onClick = onRedo, enabled = canRedo) {
            Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Redo")
        }
        IconButton(onClick = onAskAi, enabled = canAskAi) {
            Icon(Icons.Filled.AutoAwesome, contentDescription = "Ask AI about selection")
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onToggleFind) {
            Icon(
                Icons.Filled.Search,
                contentDescription = "Find in file",
                tint = if (findActive) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}

@Composable
private fun FindBar(
    query: String,
    matchCase: Boolean,
    onQueryChange: (String) -> Unit,
    onMatchCaseToggle: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onClose: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f),
            singleLine = true,
            placeholder = { Text("Find in file") },
            textStyle = MaterialTheme.typography.bodyMedium
        )
        Text(
            text = "Aa",
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(
                    if (matchCase) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        Color.Transparent
                    }
                )
                .clickable { onMatchCaseToggle() }
                .padding(horizontal = 8.dp, vertical = 6.dp),
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (matchCase) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
        IconButton(onClick = onPrevious) {
            Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Previous match")
        }
        IconButton(onClick = onNext) {
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Next match")
        }
        IconButton(onClick = onClose) {
            Icon(Icons.Filled.Close, contentDescription = "Close find bar")
        }
    }
}

@Composable
private fun WarningBanner(message: String) {
    Text(
        text = message,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onErrorContainer
    )
}
