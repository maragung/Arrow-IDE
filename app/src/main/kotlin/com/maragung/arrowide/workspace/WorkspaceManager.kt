package com.maragung.arrowide.workspace

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** A project (workspace) living under `$HOME/projects/`. */
data class ProjectInfo(
    val name: String,
    val dir: File,
    val lastModified: Long
)

/**
 * Owns the HOME layout of the IDE: `$HOME/projects/` holds one directory per
 * project/workspace. Tracks the currently open workspace via [currentWorkspace].
 *
 * All path resolution goes through [PathSafety] with `projectsDir` as the root,
 * and all I/O is dispatched to [Dispatchers.IO].
 */
class WorkspaceManager(homeDir: File) {

    /** Directory holding all projects. Created eagerly on construction. */
    val projectsDir: File = File(homeDir, "projects").apply { mkdirs() }

    private val _currentWorkspace = MutableStateFlow<File?>(null)

    /** The open workspace directory, or `null` when none is open. */
    val currentWorkspace: StateFlow<File?> = _currentWorkspace.asStateFlow()

    /** Sets (or clears, with `null`) the current workspace. */
    fun setCurrentWorkspace(dir: File?) {
        _currentWorkspace.value = dir
    }

    /** Lists all projects, newest modification first. */
    suspend fun listProjects(): List<ProjectInfo> = withContext(Dispatchers.IO) {
        ensureProjectsDir()
        projectsDir.listFiles()
            ?.filter { it.isDirectory }
            ?.map { ProjectInfo(name = it.name, dir = it, lastModified = it.lastModified()) }
            ?.sortedByDescending { it.lastModified }
            .orEmpty()
    }

    /**
     * Creates a new project directory and seeds it with a `README.md`.
     *
     * @throws IllegalArgumentException for unsafe names (blank, separators, `.`/`..`)
     * @throws IOException if the directory already exists or cannot be created
     */
    suspend fun createProject(name: String): File = withContext(Dispatchers.IO) {
        val safeName = NameValidation.validate(name)
        val dir = PathSafety.resolveWithin(projectsDir, safeName)
        if (dir.exists()) throw IOException("Project already exists: $safeName")
        if (!dir.mkdirs()) throw IOException("Could not create project directory: $safeName")
        seedReadme(dir, safeName)
        dir
    }

    /**
     * Resolves an existing project directory, validated against `projectsDir`.
     *
     * @throws IllegalArgumentException for unsafe names
     * @throws PathSafety.PathTraversalException if the name escapes the projects root
     * @throws IOException if the project does not exist
     */
    suspend fun openProject(name: String): File = withContext(Dispatchers.IO) {
        val safeName = NameValidation.validate(name)
        PathSafety.resolveExisting(projectsDir, safeName)
    }

    /**
     * Recursively deletes a project. Callers must confirm this destructive
     * action in the UI before invoking.
     */
    suspend fun deleteProject(name: String) {
        withContext(Dispatchers.IO) {
            val safeName = NameValidation.validate(name)
            val dir = PathSafety.resolveExisting(projectsDir, safeName)
            if (!dir.deleteRecursively()) {
                throw IOException("Could not delete project: $safeName")
            }
        }
    }

    /** Returns a [FileTreeRepository] rooted at [dir] for file exploration. */
    fun repositoryFor(dir: File): FileTreeRepository = FileTreeRepository(dir)

    private fun ensureProjectsDir(): File = projectsDir.apply { if (!exists()) mkdirs() }

    private fun seedReadme(projectDir: File, name: String) {
        runCatching {
            File(projectDir, "README.md").writeText(
                "# $name\n\nCreated with Arrow IDE.\n"
            )
        }
    }
}
