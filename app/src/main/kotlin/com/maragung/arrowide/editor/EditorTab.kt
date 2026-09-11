package com.maragung.arrowide.editor

import java.io.File

/**
 * A single open file in the editor.
 *
 * Dirty state lives in this data class (immutable copies are pushed into
 * [EditorTabManager.tabs]); the in-memory text and cursor position of each
 * tab live in [EditorTabManager] (see [EditorTabManager.updateContent] and
 * [EditorTabManager.updateCursor]) so that recovery snapshots can be taken
 * without touching any Android view.
 *
 * @property id unique, stable identifier (UUID) assigned when the tab is opened.
 * @property file canonical file on disk backing this tab.
 * @property displayName label shown in the tab strip (usually the file name).
 * @property languageId TextMate scope name of the language (e.g. "source.python"),
 *   or the empty string when no grammar matches the file.
 * @property isDirty true when the in-memory text differs from what is on disk.
 */
data class EditorTab(
    val id: String,
    val file: File,
    val displayName: String,
    val languageId: String,
    val isDirty: Boolean = false
)
