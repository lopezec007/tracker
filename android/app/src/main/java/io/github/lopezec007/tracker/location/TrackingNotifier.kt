// The persistent notification of the tracking service: its channel and the
// text for each state (tracking / paused / last fix / queue length).
package io.github.lopezec007.tracker.location

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.lopezec007.tracker.MainActivity
import io.github.lopezec007.tracker.R
import io.github.lopezec007.tracker.data.sync.SyncPhase
import io.github.lopezec007.tracker.util.Ages

class TrackingNotifier(private val context: Context) {

    /** The notification channel; creating an existing channel again is a no-op. */
    fun ensureChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_tracking),
            NotificationManager.IMPORTANCE_LOW,
        )
        channel.description = context.getString(R.string.notification_channel_tracking_desc)
        channel.setShowBadge(false)
        NotificationManagerCompat.from(context).createNotificationChannel(channel)
    }

    /** Builds the notification for [status]; the service posts it through startForeground. */
    fun build(status: ServiceStatus, paused: Boolean, pending: Int, syncPhase: SyncPhase, nowMs: Long = System.currentTimeMillis()): Notification {
        val title = when {
            status.error != null -> "Not tracking"
            paused -> "Paused"
            else -> "Tracking"
        }
        val text = status.error ?: buildString {
            append("Last fix: ")
            append(status.lastFixAt?.let { Ages.formatAgo(nowMs - it) } ?: "none yet")
            if (pending > 0) append(" · $pending queued")
            when (syncPhase) {
                SyncPhase.OFFLINE -> append(" · offline")
                // A foreign machine answers at the server's address (README "Server identity").
                SyncPhase.WRONG_SERVER -> append(" · a different server answered; nothing sent, fixes kept")
                else -> {}
            }
        }
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val toggle = PendingIntent.getService(
            context, 1,
            Intent(context, TrackerService::class.java).setAction(if (paused) TrackerService.ACTION_RESUME else TrackerService.ACTION_PAUSE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, if (paused) "Resume" else "Pause", toggle)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "tracking"
        const val NOTIFICATION_ID = 1
    }
}
