package com.replypilot.core.scheduling

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.work.BackoffPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.replypilot.core.coroutines.ApplicationScope
import com.replypilot.core.datastore.AppPreferencesRepository
import com.replypilot.core.permissions.PlatformStatus
import com.replypilot.domain.repository.WakeScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Arms exactly one wake-up for the queue's next due time. There are never per-message timers:
 * the persistent queue is the source of truth, and any wake simply processes whatever is due.
 *
 * - Default: a WorkManager one-off with an initial delay (battery-friendly, may be late by minutes).
 * - Precise timing (opt-in, permission-gated): an exact alarm that enqueues expedited work.
 * - Always: a 15-minute periodic safety net, so a missed wake only delays, never drops, a send.
 */
@Singleton
class AndroidWakeScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val platform: PlatformStatus,
    preferences: AppPreferencesRepository,
    @ApplicationScope scope: CoroutineScope,
) : WakeScheduler {
    private val workManager get() = WorkManager.getInstance(context)

    @Volatile
    private var preciseTiming = false

    /** The wake currently armed; a later request never postpones an earlier one. */
    @Volatile
    private var armedAt: Long? = null

    init {
        scope.launch { preferences.preferences.collect { preciseTiming = it.preciseTiming } }
    }

    override fun scheduleWake(at: Long?) {
        if (at == null) {
            armedAt = null
            workManager.cancelUniqueWork(WORK_WAKE)
            cancelAlarm()
            return
        }
        val now = System.currentTimeMillis()
        val delay = (at - now).coerceAtLeast(0)
        if (delay == 0L) {
            runNow()
            return
        }
        val current = armedAt
        if (current != null && current > now && current <= at) return
        armedAt = at
        val precise = preciseTiming && platform.exactAlarmsAllowed()
        if (precise) {
            setAlarm(at)
        } else {
            cancelAlarm()
            workManager.enqueueUniqueWork(
                WORK_WAKE,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<QueueWorker>()
                    .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .build(),
            )
        }
    }

    /** Runs the queue as soon as possible (expedited when quota allows). */
    fun runNow() {
        workManager.enqueueUniqueWork(
            WORK_NOW,
            // Chain after a run in progress instead of dropping the request: new rows may have arrived mid-run.
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<QueueWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build(),
        )
    }

    fun ensurePeriodicSafetyNet() {
        workManager.enqueueUniquePeriodicWork(
            WORK_PERIODIC,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<QueueWorker>(15, TimeUnit.MINUTES).build(),
        )
    }

    private fun alarmIntent(): PendingIntent = PendingIntent.getBroadcast(
        context, 0, Intent(context, QueueAlarmReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun setAlarm(at: Long) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        try {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarmIntent())
        } catch (_: SecurityException) {
            // Permission revoked between the check and the call: fall back to inexact work.
            workManager.enqueueUniqueWork(
                WORK_WAKE, ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<QueueWorker>().setInitialDelay(at - System.currentTimeMillis(), TimeUnit.MILLISECONDS).build(),
            )
        }
    }

    private fun cancelAlarm() {
        context.getSystemService(AlarmManager::class.java)?.cancel(alarmIntent())
    }

    companion object {
        const val WORK_WAKE = "queue-wake"
        const val WORK_NOW = "queue-now"
        const val WORK_PERIODIC = "queue-periodic"
    }
}
