package com.maragung.arrowide.terminal

import com.maragung.arrowide.workspace.PathSafety
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Creates [TerminalSession]s. Kept as an interface so the manager can be
 * unit-tested with fake sessions/processes; the real implementation is
 * [ShellPtyFactory].
 */
interface PtyFactory {

    /** Directory used for new sessions when the caller has none to offer. */
    val defaultCwd: File

    /**
     * Creates and starts a new session running in [cwd].
     *
     * @param rows initial terminal height in rows
     * @param cols initial terminal width in columns
     */
    fun createSession(cwd: File, rows: Int, cols: Int): TerminalSession
}

/**
 * Real [PtyFactory]: spawns the default shell on a PTY with the
 * [TerminalEnvironment] environment.
 *
 * @param environment    locations and env vars for child processes
 * @param workspaceRoot  when set, a [cwd] that lies inside this root is
 *                       validated with [PathSafety] (blocks path traversal
 *                       and symlink escapes); directories outside it (such
 *                       as the terminal home) are used as-is
 * @param shell          binary to execute
 */
class ShellPtyFactory(
    private val environment: TerminalEnvironment,
    private val workspaceRoot: File? = null,
    private val shell: String = TerminalEnvironment.DEFAULT_SHELL,
) : PtyFactory {

    private val idCounter = AtomicInteger(0)

    override val defaultCwd: File
        get() = environment.homeDir

    override fun createSession(cwd: File, rows: Int, cols: Int): TerminalSession {
        environment.ensureDirectories()
        val resolvedCwd = resolveCwd(cwd)
        val argv = arrayOf(shell)
        val env = environment.toEnvArray()
        val process = UnixPtyProcess(
            cmd = shell,
            argv = argv,
            env = env,
            cwd = resolvedCwd.absolutePath,
            rows = rows,
            cols = cols,
        )
        return TerminalSession(
            id = idCounter.incrementAndGet(),
            process = process,
            cwd = resolvedCwd,
        )
    }

    /**
     * Validates the requested working directory:
     *  - inside the workspace root -> canonicalized and existence-checked
     *    through [PathSafety] (throws [PathSafety.PathTraversalException]
     *    on escape attempts, falls back to the home dir when the directory
     *    disappeared),
     *  - otherwise -> used as-is.
     */
    private fun resolveCwd(cwd: File): File {
        val root = workspaceRoot ?: return cwd.canonicalFile
        val rootCanonical = root.canonicalFile
        val cwdCanonical = cwd.canonicalFile
        return if (cwdCanonical.toPath().startsWith(rootCanonical.toPath())) {
            try {
                PathSafety.resolveExisting(rootCanonical, cwdCanonical.absolutePath)
            } catch (e: PathSafety.PathTraversalException) {
                throw e
            } catch (e: java.io.IOException) {
                // The directory vanished between listing and spawning.
                environment.homeDir
            }
        } else {
            cwdCanonical
        }
    }
}

/**
 * Owns the list of live terminal sessions.
 *
 * The manager has no Android Context dependency; it only needs the
 * [PtyFactory] (and a [CoroutineScope] used to observe session exits and
 * drop finished sessions from [sessions]).
 */
class TerminalSessionManager(
    private val factory: PtyFactory,
    private val scope: CoroutineScope,
) {

    constructor(factory: PtyFactory) : this(
        factory,
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )

    constructor(environment: TerminalEnvironment) : this(ShellPtyFactory(environment))

    companion object {
        private const val INITIAL_ROWS = 24
        private const val INITIAL_COLS = 80
    }

    private val lock = Any()

    private val _sessions = MutableStateFlow<List<TerminalSession>>(emptyList())

    /** The currently tracked sessions, oldest first. */
    val sessions: StateFlow<List<TerminalSession>> = _sessions

    /** Directory used by callers that have no workspace to offer. */
    val defaultCwd: File
        get() = factory.defaultCwd

    /**
     * Starts a new session in [cwd].
     *
     * @param maxSessions hard limit on the number of concurrent sessions
     * @throws IllegalStateException when the limit is reached
     */
    fun createSession(cwd: File, maxSessions: Int): TerminalSession {
        require(maxSessions > 0) { "maxSessions must be positive" }
        val session = synchronized(lock) {
            val current = _sessions.value
            if (current.size >= maxSessions) {
                throw IllegalStateException("Terminal session limit reached ($maxSessions)")
            }
            val created = factory.createSession(cwd, INITIAL_ROWS, INITIAL_COLS)
            _sessions.value = current + created
            created
        }
        watchForExit(session)
        return session
    }

    /** Gracefully closes [session] and removes it from [sessions]. */
    fun closeSession(session: TerminalSession) {
        removeSession(session)
        session.close()
    }

    /** Kills [session] (SIGKILL), closes it and removes it from [sessions]. */
    fun killSession(session: TerminalSession) {
        removeSession(session)
        session.kill()
        session.close()
    }

    /** Kills and closes every session and empties [sessions]. */
    fun killAll() {
        val current = synchronized(lock) { _sessions.value }
        for (session in current) {
            session.kill()
            session.close()
        }
        synchronized(lock) { _sessions.value = emptyList() }
    }

    /** Removes sessions from the list as their processes exit on their own. */
    private fun watchForExit(session: TerminalSession) {
        scope.launch {
            session.onExit.await()
            removeSession(session)
        }
    }

    private fun removeSession(session: TerminalSession) {
        synchronized(lock) {
            _sessions.value = _sessions.value - session
        }
    }
}
