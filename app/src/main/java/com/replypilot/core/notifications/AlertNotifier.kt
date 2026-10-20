package com.replypilot.core.notifications

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.replypilot.MainActivity
import com.replypilot.R
import com.replypilot.core.permissions.PlatformStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** ReplyPilot's own notifications: only for things that need the user (approvals, manual sends). */
@Singleton
class AlertNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val platform: PlatformStatus,
) {
    fun ensureChannels() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ATTENTION, "Needs your attention", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Messages waiting for approval or a manual send"
            },
        )
    }

    @SuppressLint("MissingPermission")
    fun notifyAttention(needsAttention: Int, awaitingReview: Int, failedToday: Int = 0) {
        if (!platform.postNotificationsGranted()) return
        val total = needsAttention + awaitingReview + failedToday
        if (total == 0) {
            NotificationManagerCompat.from(context).cancel(ID_ATTENTION)
            return
        }
        val parts = buildList {
            if (awaitingReview > 0) add("$awaitingReview waiting for approval")
            if (needsAttention > 0) add("$needsAttention to send manually or check")
            if (failedToday > 0) add("$failedToday failed today")
        }
        val filter = if (failedToday > 0 && needsAttention + awaitingReview == 0) "failed" else "attention"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("replypilot://outbox?filter=$filter"), context, MainActivity::class.java)
        val pending = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(context, CHANNEL_ATTENTION)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(if (failedToday > 0 && needsAttention + awaitingReview == 0) "Some messages failed" else "ReplyPilot needs you")
            .setContentText(parts.joinToString(" · "))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        NotificationManagerCompat.from(context).notify(ID_ATTENTION, notification)
    }

    private companion object {
        const val CHANNEL_ATTENTION = "attention"
        const val ID_ATTENTION = 1001
    }
}
