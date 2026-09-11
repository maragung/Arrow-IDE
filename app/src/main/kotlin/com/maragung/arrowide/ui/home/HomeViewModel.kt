package com.maragung.arrowide.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maragung.arrowide.workspace.ProjectInfo
import com.maragung.arrowide.workspace.WorkspaceManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Backs [HomeScreen]: project list plus the current workspace banner.
 * Navigation-triggering operations return success so the screen can invoke
 * its callbacks only when the operation actually succeeded.
 */
class HomeViewModel(private val workspaceManager: WorkspaceManager) : ViewModel() {

    data class UiState(
        val loading: Boolean = false,
        val projects: List<ProjectInfo> = emptyList(),
        val currentWorkspace: File? = null,
        val error: String? = null
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            workspaceManager.currentWorkspace.collect { dir ->
                _state.value = _state.value.copy(currentWorkspace = dir)
            }
        }
        refresh()
    }

    /** Reloads the project list. */
    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true)
            val projects = runCatching { workspaceManager.listProjects() }
                .onFailure { setError(it) }
                .getOrDefault(emptyList())
            _state.value = _state.value.copy(loading = false, projects = projects)
        }
    }

    /**
     * Creates a project. Returns `true` on success.
     * On failure the message is available in [UiState.error].
     */
    suspend fun createProject(name: String): Boolean {
        val created = runCatching { workspaceManager.createProject(name) }
            .onFailure { setError(it) }
            .isSuccess
        refresh()
        return created
    }

    /**
     * Opens a project (sets the current workspace). Returns `true` on success.
     * On failure the message is available in [UiState.error].
     */
    suspend fun openProject(name: String): Boolean {
        val dir = runCatching { workspaceManager.openProject(name) }
            .onFailure { setError(it) }
            .getOrNull()
        if (dir != null) {
            workspaceManager.setCurrentWorkspace(dir)
        }
        refresh()
        return dir != null
    }

    /** Deletes a project (UI must have confirmed). Reloads the list afterwards. */
    fun deleteProject(name: String) {
        viewModelScope.launch {
            runCatching { workspaceManager.deleteProject(name) }
                .onFailure { setError(it) }
            refresh()
        }
    }

    /** Closes the current workspace without deleting anything. */
    fun closeWorkspace() {
        workspaceManager.setCurrentWorkspace(null)
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }

    private fun setError(t: Throwable) {
        _state.value = _state.value.copy(error = t.message ?: t.toString())
    }
}
