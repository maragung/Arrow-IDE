package com.maragung.arrowide.ui.nav

import android.content.Context
import com.maragung.arrowide.data.SettingsStore
import com.maragung.arrowide.editor.EditorTabManager
import com.maragung.arrowide.editor.RecoveryStore
import com.maragung.arrowide.terminal.ShellPtyFactory
import com.maragung.arrowide.terminal.TerminalEnvironment
import com.maragung.arrowide.terminal.TerminalSessionManager
import com.maragung.arrowide.workspace.WorkspaceManager
import java.io.File

/**
 * DI-lite app container: owns the app-scoped singletons
 * ([WorkspaceManager], [SettingsStore], [EditorTabManager], [RecoveryStore],
 * [TerminalSessionManager]).
 *
 * The manifest has no custom `Application` class, so [com.maragung.arrowide.MainActivity]
 * creates this once (cached across activity recreations so the DataStore and
 * the current-workspace StateFlow survive configuration changes) and passes it
 * down into the composition.
 */
class ArrowAppContainer(context: Context) {

    val workspaceManager: WorkspaceManager =
        WorkspaceManager(File(context.filesDir, "home"))

    val settingsStore: SettingsStore = SettingsStore(context)

    /** Must outlive [com.maragung.arrowide.ui.editor.EditorScreen] — the screen only reads it. */
    val editorTabManager: EditorTabManager = EditorTabManager()

    /** Persists unsaved editor buffers under `<filesDir>/recovery/` (plan #44). */
    val recoveryStore: RecoveryStore = RecoveryStore(context.filesDir)

    /**
     * Real local terminal (plan #2/#3). Sessions live in the app process;
     * `HOME` matches the workspace home so `cd ~/projects` works (plan #23).
     */
    val terminalSessionManager: TerminalSessionManager

    init {
        val environment = TerminalEnvironment(
            homeDir = File(context.filesDir, "home"),
            prefixDir = File(context.filesDir, "usr"),
            tmpDir = File(context.cacheDir, "tmp")
        )
        terminalSessionManager = TerminalSessionManager(ShellPtyFactory(environment))
    }
}
