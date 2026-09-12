package com.maragung.arrowide.ui.nav

import android.content.Context
import android.os.Build
import com.maragung.arrowide.ai.OpenCodeService
import com.maragung.arrowide.buildsystem.BuildSystemDetector
import com.maragung.arrowide.buildsystem.ProjectEnvironment
import com.maragung.arrowide.data.SettingsStore
import com.maragung.arrowide.editor.EditorTabManager
import com.maragung.arrowide.editor.RecoveryStore
import com.maragung.arrowide.git.AndroidGitProcess
import com.maragung.arrowide.git.GitIdentity
import com.maragung.arrowide.git.GitService
import com.maragung.arrowide.github.AndroidKeystoreTokenStore
import com.maragung.arrowide.github.GitHubService
import com.maragung.arrowide.github.HttpUrlConnectionTransport
import com.maragung.arrowide.terminal.ShellPtyFactory
import com.maragung.arrowide.terminal.TerminalEnvironment
import com.maragung.arrowide.terminal.TerminalSessionManager
import com.maragung.arrowide.packages.ProjectPackageManager
import com.maragung.arrowide.process.PortScanner
import com.maragung.arrowide.process.ProcessManager
import com.maragung.arrowide.secrets.AndroidKeystoreSecretStore
import com.maragung.arrowide.secrets.SecretStore
import com.maragung.arrowide.toolchain.HttpPackageDownloader
import com.maragung.arrowide.toolchain.TermuxRepoClient
import com.maragung.arrowide.toolchain.ToolchainEnvironment
import com.maragung.arrowide.toolchain.ToolchainManager
import com.maragung.arrowide.workspace.WorkspaceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
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

    /** Keeps a synchronous snapshot of settings for the identity provider. */
    private var latestSettings = SettingsStore.AppSettings()

    private val containerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        containerScope.launch {
            settingsStore.settings.collect { latestSettings = it }
        }
    }

    init {
        val environment = TerminalEnvironment(
            homeDir = File(context.filesDir, "home"),
            prefixDir = File(context.filesDir, "usr"),
            tmpDir = File(context.cacheDir, "tmp")
        )
        // History policy (plan #37) is read per session so Settings changes
        // apply to every NEW terminal without restarting the app.
        terminalSessionManager = TerminalSessionManager(
            ShellPtyFactory(environment, historyModeProvider = { latestSettings.historyMode })
        )
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

    /**
     * GitHub integration (plan #12-#16): REST API over an injectable
     * transport; the PAT lives only in the Android Keystore-encrypted
     * token store and is handed to git per operation via [gitCredentials]
     * (plan #38) — never stored in remote URLs or git config.
     */
    val githubService: GitHubService = GitHubService(
        tokenStore = AndroidKeystoreTokenStore(context),
        transport = HttpUrlConnectionTransport()
    )

    /**
     * Local secrets manager (plan #21): Keystore-encrypted key/value store
     * for GITHUB_TOKEN, NPM_TOKEN, API_KEY etc. — masked in the UI, never
     * logged, never committed.
     */
    val secretStore: SecretStore = AndroidKeystoreSecretStore(context)

    /** Build-system detection (plan #24): marker files → real commands. */
    val buildSystemDetector: BuildSystemDetector = BuildSystemDetector()

    /**
     * AI coding agent (plan #51+): the real OpenCode binary (static musl
     * build for the device ABI) installed into the shared toolchain prefix
     * so it is on every shell's PATH, run as a local `opencode serve`
     * process and driven over its HTTP API. HOME matches the terminal's
     * home; the server is restarted in the open workspace so the agent
     * works on the current project.
     */
    val openCodeService: OpenCodeService = OpenCodeService(
        transport = HttpUrlConnectionTransport(),
        binaryDir = File(context.filesDir, "usr/bin"),
        homeDir = File(context.filesDir, "home"),
        cacheDir = File(context.cacheDir, "opencode"),
        archAssetSuffix = if (Build.SUPPORTED_ABIS.firstOrNull() == "x86_64") {
            "x64-musl"
        } else {
            "arm64-musl"
        }
    )

    /** Project environment (plan #22): .env parsing + secret heuristics. */
    val projectEnvironment: ProjectEnvironment = ProjectEnvironment()

    /**
     * Background Process Manager (plan #25): read-only live view over the
     * terminal sessions; kill/restart stay with the session manager.
     */
    val processManager: ProcessManager = ProcessManager(terminalSessionManager.sessions)

    /**
     * Port scanner (plan #27/#28): detects this app's listening TCP
     * sockets from /proc and maps them to terminal sessions.
     */
    val portScanner: PortScanner = PortScanner(uid = android.os.Process.myUid())

    /**
     * Package manager UI core (plan #29): dependency listing + real
     * install/remove/update commands against the installed toolchain.
     */
    val projectPackageManager: ProjectPackageManager = ProjectPackageManager(
        toolAvailable = { toolId -> toolchainManager.isAvailable(toolId) ?: false },
    )

    /**
     * Git plumbing (plan #10-#11): runs the toolchain's real git binary
     * against the current project. Commit identity comes from settings
     * (Settings → Git) and falls back to ~/.gitconfig when unset.
     * Credentials come from the stored GitHub PAT (late-bound — reads the
     * token store per operation, so connect/disconnect take effect
     * immediately).
     */
    val gitService: GitService

    /** Keeps a synchronous snapshot of settings for the identity provider. */
    private var latestSettings = SettingsStore.AppSettings()

    private val containerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        containerScope.launch {
            settingsStore.settings.collect { latestSettings = it }
        }
        gitService = GitService(
            process = AndroidGitProcess(
                homeDir = File(context.filesDir, "home"),
                prefixDir = File(context.filesDir, "usr")
            ),
            homeDir = File(context.filesDir, "home"),
            askpassCacheDir = File(context.cacheDir, "git-askpass"),
            identityProvider = {
                val s = latestSettings
                val name = s.gitUserName
                val email = s.gitUserEmail
                if (!name.isNullOrBlank() && !email.isNullOrBlank()) {
                    GitIdentity(name, email)
                } else {
                    null
                }
            },
            credentialsProvider = githubService.gitCredentials()
        )
    }
}
