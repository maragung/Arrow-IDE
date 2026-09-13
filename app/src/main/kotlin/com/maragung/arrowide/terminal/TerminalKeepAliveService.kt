package com.maragung.arrowide.terminal

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder

/**
 * Foreground service that keeps terminal sessions (long-running dev
 * servers such as `npm run dev` / `python3 server.py`, plan #26) alive
 * while the app is backgrounded (plan #44: lifecycle).
 *
 * The sessions themselves live in the app process ([TerminalSessionManager]);
 * this service owns no sessions. It only holds a foreground notification so
 * Android raises the process priority instead of reclaiming it, and shows
 * how many sessions are running. [TerminalKeepAliveController] starts and
 * stops it based on the live session list.
 */
class TerminalKeepAliveService : Service() {

    companion object {

        /** Starts (or refreshes) the keep-alive foreground service. */
        const val ACTION_START = "com.maragung.arrowide.terminal.KEEPALIVE_START"

        /** Stops the keep-alive foreground service ("Stop all" action). */
        const val ACTION_STOP = "com.maragung.arrowide.terminal.KEEPALIVE_STOP"

        /** Int extra: number of live sessions shown in the notification. */
        const val EXTRA_SESSION_COUNT = "session_count"

        /** StringArrayList extra: titles of the live sessions. */
        const val EXTRA_TITLES = "session_titles"

        private const val CHANNEL_ID = "terminal_keepalive"
        private const val CHANNEL_NAME = "Terminal sessions"
        private const val NOTIFICATION_ID = 26
        private const val REQUEST_CONTENT = 1
        private const val REQUEST_STOP = 2

        /**
         * Starts the service in the foreground with the current session
         * summary. The app targets SDK 28, so either start path is legal;
         * [Context.startForegroundService] is preferred on API 26+ with a
         * fallback for OEM builds that throw on it.
         */
        fun start(context: Context, sessionCount: Int, titles: List<String>) {
            val intent = Intent(context, TerminalKeepAliveService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_SESSION_COUNT, sessionCount)
                putStringArrayListExtra(EXTRA_TITLES, ArrayList(titles))
            }
            try {
                context.startForegroundService(intent)
            } catch (e: Exception) {
                try {
                    context.startService(intent)
                } catch (e2: Exception) {
                    // Backgrounded and not running: nothing to keep alive.
                }
            }
        }

        /** Stops the service and removes its notification. */
        fun stop(context: Context) {
            val intent = Intent(context, TerminalKeepAliveService::class.java).apply {
                action = ACTION_STOP
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                // Not running (or the app is backgrounded): nothing to stop.
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val count = intent.getIntExtra(EXTRA_SESSION_COUNT, 0)
                val titles = intent.getStringArrayListExtra(EXTRA_TITLES) ?: emptyList()
                startInForeground(buildNotification(count, titles))
            }
            ACTION_STOP -> stopKeepAlive()
            else -> {
                // START_STICKY restart delivered a null intent: the session
                // summary is gone. Surface a placeholder notification (the
                // service must reach the foreground state) and shut down;
                // the controller restarts us if sessions are still alive.
                startInForeground(buildNotification(0, emptyList()))
                stopKeepAlive()
            }
        }
        return START_STICKY
    }

    private fun startInForeground(notification: Notification) {
        try {
            startForeground(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            // Rare framework failures (bad notification on odd devices);
            // stopping is the only safe way out.
            stopKeepAlive()
        }
    }

    private fun stopKeepAlive() {
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
            // Not in the foreground: nothing to remove.
        }
        stopSelf()
    }

    private fun buildNotification(count: Int, titles: List<String>): Notification {
        ensureChannel()
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOnlyAlertOnce(true)

        if (count > 0) {
            builder.setContentTitle("Arrow IDE — $count session${if (count == 1) "" else "s"} running")
            builder.setContentText(summarizeTitles(titles))
        } else {
            builder.setContentTitle("Arrow IDE")
            builder.setContentText("Terminal sessions are running")
        }

        try {
            packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
                builder.setContentIntent(
                    PendingIntent.getActivity(
                        this,
                        REQUEST_CONTENT,
                        launch,
                        PendingIntent.FLAG_IMMUTABLE,
                    )
                )
            }
        } catch (e: Exception) {
            // No launch intent: the notification simply is not tappable.
        }

        val stopPi = try {
            PendingIntent.getService(
                this,
                REQUEST_STOP,
                Intent(this, TerminalKeepAliveService::class.java).apply { action = ACTION_STOP },
                PendingIntent.FLAG_IMMUTABLE,
            )
        } catch (e: Exception) {
            null
        }
        if (stopPi != null) {
            builder.addAction(0, "Stop all", stopPi)
            builder.setDeleteIntent(stopPi)
        }
        return builder.build()
    }

    /** "npm run dev, python3 server.py +1 more" style summary. */
    private fun summarizeTitles(titles: List<String>): String {
        val shown = titles.take(2)
        val rest = titles.size - shown.size
        return when {
            titles.isEmpty() -> "Terminal sessions are running"
            rest > 0 -> shown.joinToString(", ") + " +$rest more"
            else -> shown.joinToString(", ")
        }
    }

    private fun ensureChannel() {
        try {
            val manager = getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW)
                )
            }
        } catch (e: Exception) {
            // Channel creation failed: the notification may not show, but the
            // foreground service itself still protects the sessions.
        }
    }
}
