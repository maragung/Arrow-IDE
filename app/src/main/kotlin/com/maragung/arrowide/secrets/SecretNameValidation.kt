package com.maragung.arrowide.secrets

/**
 * Validates secret names before they reach the filesystem (plan #21).
 * Pure JVM — unit-testable without Android, mirroring the workspace
 * package's NameValidation.
 *
 * A secret name becomes the file name under the secret store's private
 * directory, so anything containing path separators or relative references
 * is rejected up front.
 */
object SecretNameValidation {

    /**
     * Returns the trimmed name if it is a safe single path segment.
     *
     * @throws IllegalArgumentException for blank names, path separators or
     * relative references (`.` / `..`).
     */
    fun validate(name: String): String {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "Secret name must not be empty" }
        require('/' !in trimmed && '\\' !in trimmed) {
            "Secret name must not contain path separators: $name"
        }
        require(trimmed != "." && trimmed != ".." && ".." !in trimmed) {
            "Secret name must not be a relative path reference: $name"
        }
        return trimmed
    }
}
