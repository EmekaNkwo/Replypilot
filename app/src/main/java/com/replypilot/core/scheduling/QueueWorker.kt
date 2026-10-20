package com.replypilot.core.scheduling

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.replypilot.core.notifications.AlertNotifier
import com.replypilot.domain.common.DAY_MS
import com.replypilot.domain.model.MessageStatus
import com.replypilot.domain.repository.ActivityRepository
import com.replypilot.domain.repository.ConversationRepository
import com.replypilot.domain.repository.ExecutionRepository
import com.replypilot.domain.scheduling.QueueProcessor
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException

/** Processes due queue rows, prunes short-lived automation memory, and surfaces items needing the user. */
@HiltWorker
class QueueWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val processor: QueueProcessor,
    private val executions: ExecutionRepository,
    private val activity: ActivityRepository,
    private val conversations: ConversationRepository,
    private val notifier: AlertNotifier,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = try {
        val report = processor.process()
        val now = System.currentTimeMillis()
        conversations.prune(now - 2 * DAY_MS)
        activity.prune(now - ACTIVITY_RETENTION_DAYS * DAY_MS)
        notifier.notifyAttention(
            needsAttention = executions.withStatus(setOf(MessageStatus.NEEDS_ATTENTION)).size,
            awaitingReview = executions.withStatus(setOf(MessageStatus.AWAITING_REVIEW)).size,
            failedToday = executions.withStatus(setOf(MessageStatus.FAILED)).count { it.updatedAt >= now - DAY_MS && !it.simulated },
        )
        Log.i(TAG, "Queue run: $report")
        Result.success()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e(TAG, "Queue run failed", e)
        if (runAttemptCount < 3) Result.retry() else Result.failure()
    }

    private companion object {
        const val TAG = "ReplyPilotQueue"
        const val ACTIVITY_RETENTION_DAYS = 90
    }
}
