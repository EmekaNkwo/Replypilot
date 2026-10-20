package com.replypilot.core.scheduling

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.replypilot.core.coroutines.ApplicationScope
import com.replypilot.domain.scheduling.ScheduleService
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Fired by an exact alarm; hands off to WorkManager so the actual work survives the receiver's short life. */
@AndroidEntryPoint
class QueueAlarmReceiver : BroadcastReceiver() {
    @Inject lateinit var wake: AndroidWakeScheduler

    override fun onReceive(context: Context, intent: Intent) {
        wake.runNow()
    }
}

/** Boot, app update, clock and time-zone changes all re-arm the queue from persisted state. */
@AndroidEntryPoint
class SystemEventReceiver : BroadcastReceiver() {
    @Inject lateinit var wake: AndroidWakeScheduler
    @Inject lateinit var schedules: ScheduleService
    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_TIMEZONE_CHANGED -> {
                val pending = goAsync()
                scope.launch {
                    try {
                        val replanned = schedules.onTimeZoneChanged()
                        Log.i("ReplyPilot", "Time zone changed; re-planned $replanned schedule(s)")
                    } finally {
                        wake.runNow()
                        pending.finish()
                    }
                }
            }
            else -> {
                wake.ensurePeriodicSafetyNet()
                wake.runNow()
            }
        }
    }
}
