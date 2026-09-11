package com.maragung.arrowide.ui.nav

import android.content.Context
import android.os.Build
import com.maragung.arrowide.data.SettingsStore
import com.maragung.arrowide.editor.EditorTabManager
import com.maragung.arrowide.editor.RecoveryStore
import com.maragung.arrowide.terminal.ShellPtyFactory
import com.maragung.arrowide.terminal.TerminalEnvironment
import com.maragung.arrowide.terminal.TerminalSessionManager
import com.maragung.arrowide.toolchain.HttpPackageDownloader
import com.maragung.arrowide.toolchain.TermuxRepoClient
import com.maragung.arrowide.toolchain.ToolchainEnvironment
import com.maragung.arrowide.toolchain.ToolchainManager
import com.maragung.arrowide.workspace.WorkspaceManager
import kotlinx.coroutines.Dispatchers
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

    /**
     * Toolchain Manager (plan #4-#7): installs real command-line tools
     * (Node.js, Python, Git, ...) from the Termux package repository into
     * the SAME prefix the terminal uses (`filesDir/usr`), so downloaded
     * binaries are immediately on every shell's PATH. Downloads are
     * SHA-256-verified before extraction; installs are atomic with
     * rollback. The constructor performs no IO — the Tools screen calls
     * [ToolchainManager.refresh] on entry.
     */
    val toolchainManager: ToolchainManager = ToolchainManager(
        environment = ToolchainEnvironment.fromAndroid(
            archAbi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a",
            prefixDir = File(context.filesDir, "usr"),
            cacheDir = File(context.cacheDir, "debs")
        ),
        repoClient = TermuxRepoClient(),
        downloader = HttpPackageDownloader(),
        ioDispatcher = Dispatchers.IO
    )
}
