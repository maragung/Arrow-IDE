package com.maragung.arrowide.ui.explorer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maragung.arrowide.workspace.FileEntry
import com.maragung.arrowide.workspace.FileTreeRepository
import com.maragung.arrowide.workspace.WorkspaceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Backs [ExplorerScreen]: a lazily loaded file tree for the current workspace.
 *
 * Directories are expanded/collapsed one level at a time; children are fetched
 * from [FileTreeRepository] on demand (never a recursive walk) and cached by
 * relative path. All mutations go through the repository (and thus PathSafety).
 */
class ExplorerViewModel(private val workspaceManager: WorkspaceManager) : ViewModel() {

    /** One visible row of the flattened tree. */
    data class TreeRow(
        val entry: FileEntry,
        val depth: Int,
        val expanded: Boolean
    )

    data class UiState(
        val workspace: File? = null,
        val rows: List<TreeRow> = emptyList(),
        val loading: Boolean = false,
        val error: String? = null
    )

    private var repository: FileTreeRepository? = null
    private val expandedPaths = LinkedHashSet<String>()
    private val childrenCache = HashMap<String, List<FileEntry>>()

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            workspaceManager.currentWorkspace.collect { dir ->
                setWorkspace(dir)
            }
        }
    }

    /** Switches the explored root (called when the current workspace changes). */
    fun setWorkspace(dir: File?) {
        repository = dir?.let { FileTreeRepository(it) }
        expandedPaths.clear()
        childrenCache.clear()
        _state.value = UiState(workspace = dir, loading = dir != null)
        viewModelScope.launch {
            if (repository != null) {
                loadDir("", silent = true)
            }
            rebuild()
            _state.value = _state.value.copy(loading = false)
        }
    }

    /** Expands or collapses the directory at [path], loading children lazily. */
    fun toggle(path: String) {
        if (path.isEmpty()) return
        viewModelScope.launch {
            if (!expandedPaths.remove(path)) {
                expandedPaths.add(path)
                loadDir(path, silent = false)
            }
            rebuild()
        }
    }

    /** Reloads every cached directory and rebuilds the visible rows. */
    fun refresh() {
        viewModelScope.launch {
            refreshNow()
        }
    }

    fun createFile(parentPath: String, name: String) {
        runOperation { createFile(parentPath, name) }
    }

    fun createFolder(parentPath: String, name: String) {
        runOperation { createDirectory(parentPath, name) }
    }

    fun rename(path: String, newName: String) {
        runOperation {
            rename(path, newName)
            // Cached children of the old path are now stale.
            prune(path)
        }
    }

    fun delete(path: String) {
        runOperation {
            delete(path)
            prune(path)
        }
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }

    private fun runOperation(operation: suspend FileTreeRepository.() -> Unit) {
        viewModelScope.launch {
            val repo = repository
            if (repo == null) {
                _state.value = _state.value.copy(error = "No workspace open")
                return@launch
            }
            runCatching { withContext(Dispatchers.IO) { repo.operation() } }
                .onSuccess { refreshNow() }
                .onFailure { t ->
                    _state.value = _state.value.copy(error = t.message ?: t.toString())
                }
        }
    }

    private suspend fun refreshNow() {
        for (path in childrenCache.keys.toList()) {
            loadDir(path, silent = true)
        }
        rebuild()
    }

    private suspend fun loadDir(path: String, silent: Boolean) {
        val repo = repository ?: return
        runCatching { withContext(Dispatchers.IO) { repo.listDir(path) } }
            .onSuccess { childrenCache[path] = it }
            .onFailure {
                // A directory may have vanished (deleted/renamed elsewhere).
                childrenCache.remove(path)
                expandedPaths.remove(path)
                if (!silent) {
                    _state.value = _state.value.copy(error = it.message ?: it.toString())
                }
            }
    }

    private fun prune(path: String) {
        expandedPaths.removeAll { it == path || it.startsWith("$path/") }
        childrenCache.keys.removeAll { it == path || it.startsWith("$path/") }
    }

    private fun rebuild() {
        val rows = ArrayList<TreeRow>()
        fun walk(path: String, depth: Int) {
            val children = childrenCache[path] ?: return
            for (entry in children) {
                val expanded = entry.isDirectory && entry.path in expandedPaths
                rows.add(TreeRow(entry = entry, depth = depth, expanded = expanded))
                if (expanded) {
                    walk(entry.path, depth + 1)
                }
            }
        }
        walk("", 0)
        _state.value = _state.value.copy(rows = rows)
    }
}
