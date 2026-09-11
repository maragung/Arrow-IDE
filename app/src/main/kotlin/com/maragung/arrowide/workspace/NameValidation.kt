package com.maragung.arrowide.workspace

/**
 * Validates user-supplied single-segment names (projects, files, folders).
 * Anything containing path separators or relative references is rejected up
 * front, before the name ever reaches [PathSafety] or the filesystem.
 */
object NameValidation {

    /**
     * Returns the trimmed name if it is a safe single path segment.
     *
     * @throws IllegalArgumentException for blank names, path separators or
     * relative references (`.` / `..`).
     */
    fun validate(name: String): String {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "Name must not be empty" }
        require('/' !in trimmed && '\\' !in trimmed) {
            "Name must not contain path separators: $name"
        }
        require(trimmed != "." && trimmed != "..") {
            "Name must not be a relative path reference: $name"
        }
        return trimmed
    }
}
