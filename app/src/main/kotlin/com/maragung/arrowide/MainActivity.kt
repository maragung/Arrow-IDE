package com.maragung.arrowide

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.maragung.arrowide.data.SettingsStore
import com.maragung.arrowide.ui.nav.ArrowAppContainer
import com.maragung.arrowide.ui.nav.ArrowIDEApp
import com.maragung.arrowide.ui.theme.ArrowTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Owned by the activity and cached across recreations so the DataStore
        // and the current-workspace StateFlow survive configuration changes
        // (the manifest declares no custom Application class).
        val container = obtainContainer(applicationContext)

        setContent {
            val settings by container.settingsStore.settings.collectAsStateWithLifecycle(
                initialValue = SettingsStore.AppSettings()
            )
            ArrowTheme(themeMode = settings.themeMode) {
                ArrowIDEApp(container = container)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Persist unsaved editor buffers so nothing is lost when the app is
        // backgrounded, the screen is locked, or Android reclaims the process
        // (plan #44). Cheap no-op when no tab is dirty.
        val container = cachedContainer ?: return
        val snapshot = container.editorTabManager.snapshotForRecovery()
        if (snapshot.tabs.isNotEmpty()) {
            lifecycleScope.launch(Dispatchers.IO) {
                container.recoveryStore.save(snapshot)
            }
        }
    }

    override fun onDestroy() {
        // Last real exit: clear the recovery file only if there is nothing
        // dirty left to restore next launch.
        val container = cachedContainer
        if (container != null && isFinishing &&
            container.editorTabManager.snapshotForRecovery().tabs.isEmpty()
        ) {
            container.recoveryStore.clear()
        }
        super.onDestroy()
    }

    companion object {
        @Volatile
        private var cachedContainer: ArrowAppContainer? = null

        private fun obtainContainer(appContext: Context): ArrowAppContainer {
            val existing = cachedContainer
            if (existing != null) return existing
            val created = ArrowAppContainer(appContext)
            restoreEditorBuffers(created)
            // Long-running dev servers keep running while the app is
            // backgrounded via the keep-alive service (plan #26/#44).
            created.terminalKeepAlive.observe(created.terminalSessionManager.sessions)
            cachedContainer = created
            return created
        }

        /**
         * Restores dirty editor tabs from the previous session (plan #44):
         * re-opens each file, replays the unsaved buffer content and cursor.
         * Runs on first container creation only (i.e. once per process).
         */
        private fun restoreEditorBuffers(container: ArrowAppContainer) {
            val snapshot = runCatching {
                kotlinx.coroutines.runBlocking {
                    withContext(Dispatchers.IO) { container.recoveryStore.restore() }
                }
            }.getOrNull() ?: return
            snapshot.tabs.forEach { entry ->
                val file = File(entry.filePath)
                if (!file.exists()) return@forEach
                runCatching {
                    val tab = container.editorTabManager.openFile(file)
                    container.editorTabManager.updateContent(tab.id, entry.content)
                    container.editorTabManager.updateCursor(
                        tab.id, entry.cursorLine, entry.cursorColumn
                    )
                    container.editorTabManager.markDirty(tab.id)
                }
            }
            snapshot.activeFilePath?.let { activePath ->
                container.editorTabManager.tabFor(File(activePath))?.let { tab ->
                    container.editorTabManager.setActive(tab.id)
                }
            }
        }
    }
}
