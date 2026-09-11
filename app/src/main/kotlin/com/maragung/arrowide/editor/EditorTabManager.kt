package com.maragung.arrowide.editor

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns the set of open editor tabs. Pure Kotlin (no Android dependencies) so
 * it is unit-testable on the JVM.
 *
 * The UI layer pushes the live text/cursor of each tab back into the manager
 * ([updateContent], [updateCursor]); that keeps [snapshotForRecovery] able to
 * produce dirty buffer contents without any reference to the editor view.
 *
 * All mutating methods are synchronized; flows are safe to collect from any
 * thread.
 */
class EditorTabManager {

    private val _tabs = MutableStateFlow<List<EditorTab>>(emptyList())

    /** All open tabs, in strip order. */
    val tabs: StateFlow<List<EditorTab>> = _tabs.asStateFlow()

    private val _activeTab = MutableStateFlow<EditorTab?>(null)

    /** The currently active tab, or null when nothing is open. */
    val activeTab: StateFlow<EditorTab?> = _activeTab.asStateFlow()

    /** canonical path -> tab id, for dedupe in [openFile]. */
    private val pathIndex = ConcurrentHashMap<String, String>()

    /** Latest in-memory text per tab id (pushed by the editor UI). */
    private val contents = ConcurrentHashMap<String, String>()

    /** Latest cursor (line, column) per tab id (pushed by the editor UI). */
    private val cursors = ConcurrentHashMap<String, Pair<Int, Int>>()

    /**
     * Opens [file] (deduplicating by canonical path) and activates its tab.
     *
     * If a tab for the same canonical file already exists it is re-activated
     * and returned; no second tab is created.
     *
     * @throws IOException if the canonical path of [file] cannot be resolved.
     */
    @Throws(IOException::class)
    fun openFile(file: File): EditorTab = synchronized(this) {
        val canonical = file.canonicalFile
        val existingId = pathIndex[canonical.path]
        if (existingId != null) {
            val existing = _tabs.value.firstOrNull { it.id == existingId }
            if (existing != null) {
                setActiveLocked(existing.id)
                return existing
            }
            // Stale index entry (tab was closed); fall through and re-open.
            pathIndex.remove(canonical.path)
        }
        val tab = EditorTab(
            id = UUID.randomUUID().toString(),
            file = canonical,
            displayName = canonical.name,
            languageId = LanguageRegistry.languageFor(canonical)?.scopeName ?: "",
            isDirty = false
        )
        _tabs.value = _tabs.value + tab
        pathIndex[canonical.path] = tab.id
        _activeTab.value = tab
        return tab
    }

    /** Closes the tab with [id] (no-op if unknown) and activates a neighbor. */
    fun closeTab(id: String) = synchronized(this) {
        val index = _tabs.value.indexOfFirst { it.id == id }
        if (index < 0) return
        val closed = _tabs.value[index]
        val newTabs = _tabs.value.filterNot { it.id == id }
        _tabs.value = newTabs
        pathIndex.remove(closed.file.path)
        contents.remove(id)
        cursors.remove(id)
        if (_activeTab.value?.id == id) {
            // Activate the tab that took the closed one's place, else the last.
            _activeTab.value = newTabs.getOrNull(index.coerceAtMost(newTabs.lastIndex))
        }
    }

    /** Activates the tab with [id] (no-op if unknown). */
    fun setActive(id: String) = synchronized(this) {
        setActiveLocked(id)
    }

    private fun setActiveLocked(id: String) {
        _activeTab.value = _tabs.value.firstOrNull { it.id == id } ?: return
    }

    /** Marks the tab with [id] as having unsaved changes (no-op if unknown). */
    fun markDirty(id: String) = synchronized(this) {
        updateTabLocked(id) { it.copy(isDirty = true) }
    }

    /** Marks the tab with [id] as saved (no-op if unknown). */
    fun markClean(id: String) = synchronized(this) {
        updateTabLocked(id) { it.copy(isDirty = false) }
    }

    /**
     * Pushes the current in-memory [text] of a tab. Called by the editor UI on
     * every content change; used by saving and by [snapshotForRecovery].
     */
    fun updateContent(id: String, text: String) {
        if (id in tabIds()) contents[id] = text
    }

    /**
     * Pushes the current cursor position of a tab (0-based [line]/[column]).
     * Called by the editor UI on selection changes.
     */
    fun updateCursor(id: String, line: Int, column: Int) {
        if (id in tabIds()) cursors[id] = line to column
    }

    /** Latest in-memory text of the tab, or null if never pushed. */
    fun contentOf(id: String): String? = contents[id]

    /** Latest cursor of the tab, or null. */
    fun cursorOf(id: String): Pair<Int, Int>? = cursors[id]

    /** True/false dirty state of the tab, or null if unknown id. */
    fun isDirty(id: String): Boolean? = _tabs.value.firstOrNull { it.id == id }?.isDirty

    /** The open tab backing [file] (matched by canonical path), if any. */
    fun tabFor(file: File): EditorTab? {
        val canonical = runCatching { file.canonicalFile }.getOrDefault(file)
        val id = pathIndex[canonical.path] ?: return null
        return _tabs.value.firstOrNull { it.id == id }
    }

    /**
     * Captures the unsaved (dirty) buffers plus their cursor positions for the
     * crash/stop recovery store. The integration layer persists the result via
     * [RecoveryStore.save] when the app goes to the background (onStop).
     */
    fun snapshotForRecovery(): RecoverySnapshot = synchronized(this) {
        val entries = _tabs.value
            .filter { it.isDirty }
            .map { tab ->
                val cursor = cursors[tab.id]
                RecoveryTabEntry(
                    filePath = tab.file.path,
                    content = contents[tab.id] ?: "",
                    cursorLine = cursor?.first ?: 0,
                    cursorColumn = cursor?.second ?: 0,
                    displayName = tab.displayName,
                    languageId = tab.languageId
                )
            }
        RecoverySnapshot(
            tabs = entries,
            activeFilePath = _activeTab.value?.file?.path,
            savedAtMillis = System.currentTimeMillis()
        )
    }

    private fun tabIds(): Set<String> = _tabs.value.mapTo(mutableSetOf()) { it.id }

    private inline fun updateTabLocked(id: String, transform: (EditorTab) -> EditorTab) {
        val tabs = _tabs.value
        val index = tabs.indexOfFirst { it.id == id }
        if (index < 0) return
        val updated = transform(tabs[index])
        _tabs.value = tabs.toMutableList().also { it[index] = updated }
        if (_activeTab.value?.id == id) {
            _activeTab.value = updated
        }
    }
}
