package com.maragung.arrowide.editor

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** One unsaved buffer persisted by [RecoveryStore]. */
@Serializable
data class RecoveryTabEntry(
    /** Absolute path of the file the buffer belongs to. */
    val filePath: String,
    /** Full in-memory text at the time of the snapshot. */
    val content: String,
    /** 0-based cursor line at the time of the snapshot. */
    val cursorLine: Int,
    /** 0-based cursor column at the time of the snapshot. */
    val cursorColumn: Int,
    val displayName: String = "",
    val languageId: String = ""
)

/** Full recovery state: every dirty tab plus which tab was active. */
@Serializable
data class RecoverySnapshot(
    val tabs: List<RecoveryTabEntry>,
    val activeFilePath: String? = null,
    val savedAtMillis: Long
)

/**
 * Persists unsaved editor buffers as JSON under `<baseDir>/recovery/`.
 * Pure Kotlin (constructor takes the base directory), JVM-testable.
 *
 * Typical wiring (app shell, in `onStop`):
 * ```
 * lifecycleScope.launch {
 *     recoveryStore.save(tabManager.snapshotForRecovery())
 * }
 * ```
 * and after a restart, `recoveryStore.restore()` returns the snapshot (or
 * null when absent/corrupt) so the shell can offer to re-open the buffers.
 */
class RecoveryStore(baseDir: File) {

    private val dir = File(baseDir, "recovery")
    private val file = File(dir, "recovery.json")

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * Atomically persists [state]. Synchronous JSON encoding happens on the
     * caller's dispatcher; the file write happens on [Dispatchers.IO].
     */
    suspend fun save(state: RecoverySnapshot) {
        val encoded = json.encodeToString(state)
        withContext(Dispatchers.IO) {
            FileContentIO.writeAtomic(file, encoded.toByteArray(Charsets.UTF_8))
        }
    }

    /**
     * Returns the last persisted snapshot, or null when none exists or the
     * stored JSON is unreadable/corrupted (never throws for corrupt data).
     */
    suspend fun restore(): RecoverySnapshot? = withContext(Dispatchers.IO) {
        if (!file.isFile) return@withContext null
        return@withContext try {
            json.decodeFromString(RecoverySnapshot.serializer(), file.readText())
        } catch (e: Exception) {
            null
        }
    }

    /** Removes any persisted recovery state (call after buffers are saved or discarded). */
    fun clear() {
        if (file.exists()) {
            file.delete()
        }
    }
}
