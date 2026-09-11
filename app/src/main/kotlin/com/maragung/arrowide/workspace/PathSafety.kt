package com.maragung.arrowide.workspace

import java.io.File
import java.io.IOException

/**
 * Validates that paths addressed against a workspace stay inside the workspace root.
 *
 * Blocks path traversal (`..`), absolute-path injection and symlink escapes by
 * canonicalizing both the root and the candidate before comparing. This is the
 * single choke point every file operation in the app must go through
 * (explorer, editor, terminal cwd, future AI agent tools).
 */
object PathSafety {

    /** Thrown when a requested path escapes the workspace root. */
    class PathTraversalException(message: String) : SecurityException(message)

    /**
     * Resolves [path] (relative to [root], or absolute) and returns the canonical
     * file, but only if it lies within [root].
     *
     * @throws PathTraversalException if the resolved path escapes the root
     * @throws IOException if the path cannot be canonicalized
     */
    @Throws(IOException::class)
    fun resolveWithin(root: File, path: String): File {
        val rootCanonical = root.canonicalFile
        val candidate = if (File(path).isAbsolute) File(path) else File(rootCanonical, path)
        val resolved = candidate.canonicalFile
        if (resolved != rootCanonical && !resolved.toPath().startsWith(rootCanonical.toPath())) {
            throw PathTraversalException("Path escapes workspace root: $path")
        }
        return resolved
    }

    /** Like [resolveWithin] but the target must exist on disk. */
    @Throws(IOException::class)
    fun resolveExisting(root: File, path: String): File =
        resolveWithin(root, path).also {
            if (!it.exists()) throw IOException("No such file or directory: $path")
        }
}
