package com.replypilot

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import android.util.Log
import com.replypilot.core.accessibility.AppForeground
import com.replypilot.core.attachments.AttachmentStore
import com.replypilot.core.coroutines.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import com.replypilot.core.notifications.AlertNotifier
import com.replypilot.core.scheduling.AndroidWakeScheduler
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class ReplyPilotApp : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var wake: AndroidWakeScheduler
    @Inject lateinit var notifier: AlertNotifier
    @Inject lateinit var foreground: AppForeground
    @Inject lateinit var attachments: AttachmentStore
    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        foreground.register(this)
        notifier.ensureChannels()
        // Recovery path: any process start re-reads the persistent queue.
        wake.ensurePeriodicSafetyNet()
        wake.runNow()
        // Free the copies of files that no schedule or pending message uses any more.
        scope.launch {
            runCatching { attachments.deleteUnused() }
                .onSuccess { if (it > 0) Log.i("ReplyPilot", "Removed $it unused attachment folder(s)") }
                .onFailure { Log.w("ReplyPilot", "Attachment clean-up failed", it) }
        }
    }
}
