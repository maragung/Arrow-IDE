package com.maragung.arrowide.workspace

import java.io.File
import java.io.IOException
import java.nio.file.Files

/** One entry of a directory listing inside a workspace. */
data class FileEntry(
    val name: String,
    /** Path relative to the repository root, using `/` separators (empty for the root itself). */
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long
)

/**
 * Pure-Kotlin lazy file tree over a workspace root: lists one directory at a
 * time (never walks recursively), sorted directories-first then alphabetical.
 *
 * Every operation resolves through [PathSafety], so traversal attempts
 * (`..`, absolute paths outside the root) are rejected.
 */
class FileTreeRepository(private val root: File) {

    /**
     * Lists the immediate children of the directory at [relativePath]
     * (empty string = root), directories first, then alphabetical by name.
     *
     * @throws PathSafety.PathTraversalException if the path escapes the root
     * @throws IOException if the path does not exist or is not a directory
     */
    fun listDir(relativePath: String): List<FileEntry> {
        val dir = PathSafety.resolveExisting(root, relativePath)
        if (!dir.isDirectory) throw IOException("Not a directory: $relativePath")
        return dir.listFiles()
            ?.map { it.toFileEntry() }
            ?.sortedWith(
                compareByDescending<FileEntry> { it.isDirectory }
                    .thenBy { it.name.lowercase() }
            )
            .orEmpty()
    }

    /**
     * Creates an empty file named [name] inside the directory at [parentPath].
     *
     * @throws IllegalArgumentException for unsafe names
     * @throws IOException if it already exists or the parent is not a directory
     */
    fun createFile(parentPath: String, name: String): FileEntry {
        val safeName = NameValidation.validate(name)
        val parent = PathSafety.resolveExisting(root, parentPath)
        if (!parent.isDirectory) throw IOException("Not a directory: $parentPath")
        val target = PathSafety.resolveWithin(root, join(parentPath, safeName))
        if (target.exists()) throw IOException("Already exists: $safeName")
        if (!target.createNewFile()) throw IOException("Could not create file: $safeName")
        return target.toFileEntry()
    }

    /**
     * Creates a directory named [name] inside the directory at [parentPath].
     *
     * @throws IllegalArgumentException for unsafe names
     * @throws IOException if it already exists or creation fails
     */
    fun createDirectory(parentPath: String, name: String): FileEntry {
        val safeName = NameValidation.validate(name)
        val parent = PathSafety.resolveExisting(root, parentPath)
        if (!parent.isDirectory) throw IOException("Not a directory: $parentPath")
        val target = PathSafety.resolveWithin(root, join(parentPath, safeName))
        if (target.exists()) throw IOException("Already exists: $safeName")
        if (!target.mkdirs()) throw IOException("Could not create directory: $safeName")
        return target.toFileEntry()
    }

    /**
     * Renames the entry at [relativePath] to [newName] within its parent directory.
     *
     * @throws IllegalArgumentException for unsafe names
     * @throws IOException if a target with the new name already exists or the move fails
     */
    fun rename(relativePath: String, newName: String): File {
        val safeNewName = NameValidation.validate(newName)
        require(relativePath.isNotEmpty()) { "Cannot rename the workspace root" }
        val source = PathSafety.resolveExisting(root, relativePath)
        val target = PathSafety.resolveWithin(
            root,
            File(source.parentFile, safeNewName).absolutePath
        )
        if (target.exists()) throw IOException("Already exists: $safeNewName")
        return try {
            Files.move(source.toPath(), target.toPath())
            target
        } catch (e: IOException) {
            throw IOException("Could not rename ${source.name}: ${e.message}", e)
        }
    }

    /**
     * Deletes the file or (recursively) directory at [relativePath].
     * The workspace root itself cannot be deleted.
     *
     * @throws IOException if the entry does not exist or deletion fails
     */
    fun delete(relativePath: String) {
        if (relativePath.isBlank()) throw IOException("Cannot delete the workspace root")
        val target = PathSafety.resolveExisting(root, relativePath)
        if (target == root.canonicalFile) throw IOException("Cannot delete the workspace root")
        val deleted = if (target.isDirectory) target.deleteRecursively() else target.delete()
        if (!deleted) throw IOException("Could not delete: ${target.name}")
    }

    private fun File.toFileEntry(): FileEntry = FileEntry(
        name = name,
        path = relativePathWithinRoot(),
        isDirectory = isDirectory,
        size = if (isDirectory) 0L else length(),
        lastModified = lastModified()
    )

    private fun File.relativePathWithinRoot(): String =
        root.canonicalFile.toPath().relativize(canonicalFile.toPath()).toString()

    private fun join(parent: String, child: String): String =
        if (parent.isEmpty()) child else "$parent/$child"
}
