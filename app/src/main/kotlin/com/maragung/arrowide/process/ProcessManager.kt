package com.maragung.arrowide.process

import com.maragung.arrowide.terminal.TerminalSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * A terminal session surfaced in the Processes screen (plan #25).
 *
 * @param sessionId id of the backing [TerminalSession]
 * @param pid       OS process id of the session's PTY child
 * @param title     live session title (OSC 0/2 or default "sh (dir)")
 * @param cwdPath   working directory the session was started in
 * @param isAlive   false once the child has exited
 * @param exitCode  the child's exit status, or null while it runs
 */
data class TrackedProcess(
    val sessionId: Int,
    val pid: Long,
    val title: String,
    val cwdPath: String,
    val isAlive: Boolean,
    val exitCode: Int?,
)

/**
 * Read-only view over the live terminal sessions for the Processes screen
 * (plan #25 Background Process Manager).
 *
 * The manager observes [sessionSource] (the [TerminalSessionManager.sessions]
 * flow) plus each session's [TerminalSession.title] and
 * [TerminalSession.exitCode] flows, so rows update live without polling.
 *
 * Deliberately read-only: kill / close / restart actions are performed by
 * the UI through [com.maragung.arrowide.terminal.TerminalSessionManager]
 * directly (killSession / closeSession / createSession). Keeping process
 * control out of this class makes it trivially unit-testable and avoids a
 * second owner for session lifecycle.
 *
 * Only processes belonging to the app itself are ever visible — the source
 * list only ever contains the app's own PTY children (plan #25: never touch
 * other Android processes).
 */
class ProcessManager(
    private val sessionSource: StateFlow<List<TerminalSession>>,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _processes =
        MutableStateFlow<List<TrackedProcess>>(emptyList())

    /** The tracked processes, oldest session first (mirrors the source list). */
    val processes: StateFlow<List<TrackedProcess>> = _processes.asStateFlow()

    /** Session id -> per-session flow observer job; cleaned up on removal. */
    private val observers = mutableMapOf<Int, Job>()

    init {
        sessionSource
            .onEach { sessions ->
                snapshot(sessions)
                syncObservers(sessions)
            }
            .launchIn(scope)
    }

    /** Cancels observation. The manager must not be used afterwards. */
    fun close() {
        scope.cancel()
    }

    /**
     * Takes one consistent snapshot from [sessions] — called both from the
     * source flow and from every per-session flow change.
     */
    private fun snapshot(sessions: List<TerminalSession>) {
        _processes.value = sessions.map { session ->
            TrackedProcess(
                sessionId = session.id,
                pid = session.process.pid,
                title = session.title.value,
                cwdPath = session.cwd.absolutePath,
                isAlive = session.isAlive,
                exitCode = session.exitCode.value,
            )
        }
    }

    /** Starts observers for new sessions, drops observers for removed ones. */
    private fun syncObservers(sessions: List<TerminalSession>) {
        val liveIds = sessions.mapTo(mutableSetOf()) { it.id }
        observers.keys.retainAll { id -> id in liveIds }
        sessions.forEach { session ->
            if (session.id !in observers) {
                observers[session.id] = combine(
                    session.title,
                    session.exitCode,
                ) { _, _ -> Unit } // any change re-snapshots the whole list
                    .onEach { snapshot(sessionSource.value) }
                    .launchIn(scope)
            }
        }
    }
}
