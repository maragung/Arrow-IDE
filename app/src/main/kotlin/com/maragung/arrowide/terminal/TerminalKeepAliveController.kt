package com.maragung.arrowide.terminal

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * Bridges [TerminalSessionManager.sessions] to [TerminalKeepAliveService].
 *
 * While at least one live session exists (a dev server started per plan #26),
 * the foreground service is kept running so Android does not reclaim the
 * process while the app is backgrounded (plan #44: lifecycle). When the last
 * session dies, the service is stopped.
 *
 * The service is NOT restarted on every emission: it is only refreshed when
 * the session count changes or the SET of session titles changes (sorted
 * comparison), so per-keystroke title churn does not rebuild the
 * notification.
 *
 * @param context used to start/stop the service
 * @param scope   scope the collection runs in; cancelling it (or [stop])
 *               ends the observation
 */
class TerminalKeepAliveController(
    private val context: Context,
    private val scope: CoroutineScope,
) {

    private var job: Job? = null

    /** Last count passed to the service; 0 means "stopped", -1 "never synced". */
    private var lastCount = -1

    /** Sorted titles of the last start; only meaningful while lastCount > 0. */
    private var lastTitles: List<String> = emptyList()

    /**
     * Starts observing [sessions]. Replaces any previous observation.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe(sessions: StateFlow<List<TerminalSession>>) {
        job?.cancel()
        job = scope.launch {
            // Re-emits whenever the session list changes OR any session's
            // title (OSC 0/2) changes, so title updates are visible without
            // polling; the dedupe below keeps the service quiet.
            sessions
                .flatMapLatest { list ->
                    if (list.isEmpty()) {
                        flowOf(Unit)
                    } else {
                        combine(List(list.size) { index -> list[index].title }) { }
                    }
                }
                .collect {
                    sync(sessions.value.filter { it.isAlive })
                }
        }
    }

    /** Cancels the observation and stops the foreground service. */
    fun stop() {
        job?.cancel()
        job = null
        if (lastCount != 0) {
            TerminalKeepAliveService.stop(context)
        }
        lastCount = 0
        lastTitles = emptyList()
    }

    private fun sync(liveSessions: List<TerminalSession>) {
        if (liveSessions.isEmpty()) {
            if (lastCount != 0) {
                TerminalKeepAliveService.stop(context)
                lastCount = 0
                lastTitles = emptyList()
            }
            return
        }
        val titles = liveSessions.map { it.title.value }
        val sorted = titles.sorted()
        if (liveSessions.size == lastCount && sorted == lastTitles) {
            return
        }
        TerminalKeepAliveService.start(context, liveSessions.size, titles)
        lastCount = liveSessions.size
        lastTitles = sorted
    }
}
