package com.replypilot.domain.scheduling

import com.replypilot.domain.automation.ActivityLogger
import com.replypilot.domain.common.Clock
import com.replypilot.domain.common.DAY_MS
import com.replypilot.domain.common.MINUTE_MS
import com.replypilot.domain.messaging.AdapterRegistry
import com.replypilot.domain.messaging.DeliveryState
import com.replypilot.domain.messaging.OutboundMessage
import com.replypilot.domain.messaging.SendOutcome
import com.replypilot.domain.model.ActivityResult
import com.replypilot.domain.model.ActivityType
import com.replypilot.domain.model.AutomationSettings
import com.replypilot.domain.model.ChannelCapability
import com.replypilot.domain.model.EnrollmentStatus
import com.replypilot.domain.model.ExecutionItem
import com.replypilot.domain.model.ExecutionSource
import com.replypilot.domain.model.FailureCode
import com.replypilot.domain.model.ManualHandoff
import com.replypilot.domain.model.MessageStatus
import com.replypilot.domain.model.QuietHoursBehavior
import com.replypilot.domain.model.ScheduleState
import com.replypilot.domain.repository.ContactRepository
import com.replypilot.domain.repository.ConversationRepository
import com.replypilot.domain.repository.ExecutionRepository
import com.replypilot.domain.repository.ScheduleRepository
import com.replypilot.domain.repository.SequenceRepository
import com.replypilot.domain.repository.SettingsRepository
import com.replypilot.domain.repository.WakeScheduler
import com.replypilot.domain.templates.TemplateRenderer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ProcessReport(
    val paused: Boolean = false,
    val recovered: Int = 0,
    val materialized: Int = 0,
    val attempted: Int = 0,
    val submitted: Int = 0,
    val deferred: Int = 0,
    val failed: Int = 0,
    val needsAttention: Int = 0,
    val nextWakeAt: Long? = null,
)

/**
 * Executes due rows from the persistent queue. Safe to call at any time and from any trigger
 * (alarm, WorkManager, boot, app start): each row is claimed with a compare-and-set before it is sent,
 * and rows found stuck in EXECUTING after a crash are flagged for review instead of being resent.
 */
class QueueProcessor(
    private val executions: ExecutionRepository,
    private val schedules: ScheduleRepository,
    private val sequences: SequenceRepository,
    private val contacts: ContactRepository,
    private val conversations: ConversationRepository,
    private val settings: SettingsRepository,
    private val adapters: AdapterRegistry,
    private val materializer: ScheduleMaterializer,
    private val activity: ActivityLogger,
    private val clock: Clock,
    private val wake: WakeScheduler,
) {
    private val mutex = Mutex()

    suspend fun process(): ProcessReport = mutex.withLock {
        val recovered = recoverInterrupted()
        val materialized = materializer.materializeAll()
        val current = settings.current()
        if (current.automationPaused) {
            wake.scheduleWake(null)
            return@withLock ProcessReport(paused = true, recovered = recovered, materialized = materialized)
        }
        var report = ProcessReport(recovered = recovered, materialized = materialized)
        // Drain in batches so a backlog after downtime doesn't wait for the next wake-up.
        repeat(MAX_BATCHES) {
            val due = executions.due(clock.now(), MessageStatus.RUNNABLE, BATCH_SIZE)
            for (item in due) {
                report = when (execute(item, current)) {
                    Result.SUBMITTED -> report.copy(attempted = report.attempted + 1, submitted = report.submitted + 1)
                    Result.DEFERRED -> report.copy(deferred = report.deferred + 1)
                    Result.FAILED -> report.copy(attempted = report.attempted + 1, failed = report.failed + 1)
                    Result.NEEDS_ATTENTION -> report.copy(attempted = report.attempted + 1, needsAttention = report.needsAttention + 1)
                    Result.SKIPPED -> report
                }
            }
            if (due.size < BATCH_SIZE) return@repeat
        }
        refreshDeliveries()
        val next = nextWake()
        wake.scheduleWake(next)
        report.copy(nextWakeAt = next)
    }

    /** Earliest moment the queue needs attention: a due row or the next materialization pass. */
    suspend fun nextWake(): Long? {
        val nextRow = executions.nextWakeAt(MessageStatus.RUNNABLE)
        val hasActiveSchedules = schedules.all().any { it.state == ScheduleState.ACTIVE }
        val rematerialize = if (hasActiveSchedules) clock.now() + REMATERIALIZE_EVERY_MS else null
        return listOfNotNull(nextRow, rematerialize).minOrNull()
    }

    private enum class Result { SUBMITTED, DEFERRED, FAILED, NEEDS_ATTENTION, SKIPPED }

    private suspend fun recoverInterrupted(): Int {
        val cutoff = clock.now() - STALE_EXECUTION_MS
        val stale = executions.withStatus(setOf(MessageStatus.EXECUTING)).filter { (it.lastAttemptAt ?: 0) < cutoff }
        stale.forEach { item ->
            val updated = item.copy(
                status = MessageStatus.NEEDS_ATTENTION,
                failureCode = FailureCode.INTERRUPTED,
                statusReason = "Interrupted while sending. It may or may not have been sent; check the conversation before resending.",
                updatedAt = clock.now(),
            )
            executions.update(updated)
            activity.forExecution(updated, ActivityType.QUEUE_RECOVERED, ActivityResult.FAILED, updated.statusReason)
        }
        return stale.size
    }

    private suspend fun execute(original: ExecutionItem, current: AutomationSettings): Result {
        val now = clock.now()
        val item = original.copy(status = MessageStatus.EXECUTING, attempts = original.attempts + 1, lastAttemptAt = now, updatedAt = now)
        // The claim and the attempt counter are one atomic write; losing the race means another worker has it.
        if (!executions.replaceIfStatus(item, original.status)) return Result.SKIPPED

        preflight(item, current, now)?.let { return it }

        val adapter = adapters.get(item.channel)
            ?: return finish(item, MessageStatus.FAILED, FailureCode.NOT_CONFIGURED, "No integration is installed for ${item.channel.displayName}")
        val outbound = OutboundMessage(item.id, item.idempotencyKey, item.recipient, item.body, item.replyTarget, item.attachments)
        activity.forExecution(item, ActivityType.EXECUTION_STARTED, ActivityResult.PENDING)

        val outcome = try {
            adapter.send(outbound)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SendOutcome.Failed(FailureCode.UNKNOWN, e.message ?: e::class.simpleName.orEmpty())
        }
        return when (outcome) {
            is SendOutcome.Submitted -> {
                val done = item.copy(
                    status = MessageStatus.SUBMITTED,
                    providerRef = outcome.providerRef,
                    simulated = item.simulated || outcome.simulated,
                    failureCode = null,
                    statusReason = when {
                        item.simulated || outcome.simulated -> "Simulated — no real message was sent"
                        else -> outcome.detail ?: "Submitted to ${item.channel.displayName}"
                    },
                    updatedAt = clock.now(),
                )
                executions.update(done)
                val type = if (item.source == ExecutionSource.AUTO_REPLY) ActivityType.REPLY_SENT else ActivityType.MESSAGE_SUBMITTED
                activity.forExecution(done, type, ActivityResult.SUCCESS, done.statusReason)
                completeEnrollmentIfDone(done)
                Result.SUBMITTED
            }
            is SendOutcome.NeedsManualAction -> {
                val waiting = item.copy(status = MessageStatus.NEEDS_ATTENTION, handoff = outcome.handoff, statusReason = outcome.reason, updatedAt = clock.now())
                executions.update(waiting)
                activity.forExecution(waiting, ActivityType.MANUAL_ACTION_REQUIRED, ActivityResult.PENDING, outcome.reason)
                Result.NEEDS_ATTENTION
            }
            is SendOutcome.Failed -> handleFailure(item, outcome, adapter.handoffFor(outbound))
        }
    }

    /** Checks that must pass immediately before sending. Returns a result when the row should not be sent. */
    private suspend fun preflight(item: ExecutionItem, current: AutomationSettings, now: Long): Result? {
        if (item.expiresAt != null && now > item.expiresAt) {
            return finish(item, MessageStatus.EXPIRED, null, "Not sent before its expiry time", ActivityType.EXECUTION_EXPIRED)
        }
        sourceBlock(item)?.let { (status, reason) -> return finish(item, status, null, reason, ActivityType.EXECUTION_SKIPPED) }

        val contact = item.recipient.contactId?.let { contacts.get(it) } ?: contacts.findByAddress(item.channel, item.recipient.address)
        if (contact?.optedOut == true) {
            return finish(item, MessageStatus.SKIPPED, null, "${contact.displayName} has opted out", ActivityType.EXECUTION_SKIPPED)
        }
        val unresolved = TemplateRenderer.render(item.body, emptyMap()).unresolved
        if (unresolved.isNotEmpty()) {
            return finish(item, MessageStatus.FAILED, FailureCode.UNRESOLVED_VARIABLES, "Unresolved ${unresolved.joinToString { "{{$it}}" }}")
        }
        if (item.source == ExecutionSource.AUTO_REPLY && takeoverActive(item, now)) {
            return finish(item, MessageStatus.SKIPPED, null, "You took over this conversation before the reply was sent", ActivityType.EXECUTION_SKIPPED)
        }
        val zone = clock.zone()
        if (item.source != ExecutionSource.AUTO_REPLY && current.quietHoursEnabled && TimeWindows.contains(current.quietHours, now, zone)) {
            when (item.quietHours) {
                QuietHoursBehavior.DEFER -> return defer(item, TimeWindows.endOf(current.quietHours, now, zone), "Waiting for quiet hours to end")
                QuietHoursBehavior.SKIP -> return finish(item, MessageStatus.SKIPPED, null, "Skipped during quiet hours", ActivityType.EXECUTION_SKIPPED)
                QuietHoursBehavior.SEND_ANYWAY -> Unit
            }
        }
        val dayStart = TimeWindows.startOfDay(now, zone)
        if (executions.countSubmittedSince(dayStart) >= current.maxMessagesPerDay) {
            return defer(item, TimeWindows.startOfNextDay(now, zone), "Daily limit of ${current.maxMessagesPerDay} messages reached")
        }
        val channelLimit = current.perChannelDailyLimit[item.channel]
        if (channelLimit != null && executions.countSubmittedSince(dayStart, item.channel) >= channelLimit) {
            return defer(item, TimeWindows.startOfNextDay(now, zone), "${item.channel.displayName} daily limit of $channelLimit reached")
        }
        return null
    }

    /** Auto-reply rows address the conversation key when the channel exposes no sender address. */
    private suspend fun takeoverActive(item: ExecutionItem, now: Long): Boolean =
        (conversations.state(item.channel, item.recipient.address)?.humanTakeoverUntil ?: 0) > now

    private suspend fun sourceBlock(item: ExecutionItem): Pair<MessageStatus, String>? = when (item.source) {
        ExecutionSource.SCHEDULED -> {
            val schedule = schedules.get(item.sourceId)
            when (schedule?.state) {
                null, ScheduleState.CANCELLED -> MessageStatus.CANCELLED to "Schedule was cancelled or deleted"
                ScheduleState.PAUSED -> MessageStatus.PAUSED to "Schedule is paused"
                else -> null
            }
        }
        ExecutionSource.SEQUENCE -> {
            val enrollment = sequences.getEnrollment(item.sourceId)
            if (enrollment?.status != EnrollmentStatus.ACTIVE) {
                MessageStatus.CANCELLED to "Sequence stopped${enrollment?.stopReason?.let { ": $it" }.orEmpty()}"
            } else {
                null
            }
        }
        ExecutionSource.AUTO_REPLY -> null
    }

    private suspend fun handleFailure(item: ExecutionItem, failure: SendOutcome.Failed, handoff: ManualHandoff?): Result {
        if (failure.code == FailureCode.DEVICE_LOCKED) {
            // Not a real attempt: wait for the unlock (the platform also re-runs the queue on unlock).
            activity.forExecution(item, ActivityType.RETRY_SCHEDULED, ActivityResult.PENDING, "${failure.detail}; waiting for unlock")
            defer(item, clock.now() + LOCKED_RECHECK_MS, failure.code.label)
            return Result.DEFERRED
        }
        if (failure.code == FailureCode.PERMISSION_MISSING) {
            activity.forExecution(item, ActivityType.PERMISSION_UNAVAILABLE, ActivityResult.FAILED, failure.detail)
        }
        if (RetryPolicy.shouldRetry(failure.code, item.attempts, item.maxAttempts)) {
            val retryAt = clock.now() + RetryPolicy.delayFor(item.attempts)
            val retry = item.copy(
                status = MessageStatus.RETRY_SCHEDULED,
                nextAttemptAt = retryAt,
                failureCode = failure.code,
                statusReason = "${failure.code.label}: ${failure.detail}. Retry ${item.attempts} of ${item.maxAttempts - 1}.",
                updatedAt = clock.now(),
            )
            executions.update(retry)
            activity.forExecution(retry, ActivityType.RETRY_SCHEDULED, ActivityResult.PENDING, retry.statusReason)
            return Result.FAILED
        }
        if (handoff != null && failure.code in HANDOFF_CODES) {
            val waiting = item.copy(
                status = MessageStatus.NEEDS_ATTENTION,
                failureCode = failure.code,
                handoff = handoff,
                statusReason = "${failure.detail}. You can send it manually.",
                updatedAt = clock.now(),
            )
            executions.update(waiting)
            activity.forExecution(waiting, ActivityType.MANUAL_ACTION_REQUIRED, ActivityResult.FAILED, waiting.statusReason)
            return Result.NEEDS_ATTENTION
        }
        finish(item, MessageStatus.FAILED, failure.code, "${failure.code.label}: ${failure.detail}")
        return Result.FAILED
    }

    private suspend fun finish(
        item: ExecutionItem,
        status: MessageStatus,
        code: FailureCode?,
        reason: String,
        type: ActivityType = ActivityType.EXECUTION_FAILED,
    ): Result {
        val updated = item.copy(status = status, failureCode = code, statusReason = reason, updatedAt = clock.now())
        executions.update(updated)
        val result = if (status == MessageStatus.FAILED) ActivityResult.FAILED else ActivityResult.SUPPRESSED
        activity.forExecution(updated, type, result, reason)
        return if (status == MessageStatus.FAILED) Result.FAILED else Result.SKIPPED
    }

    private suspend fun defer(item: ExecutionItem, until: Long, reason: String): Result {
        // The attempt didn't happen, so it doesn't count against retries.
        executions.update(
            item.copy(
                status = MessageStatus.SCHEDULED,
                attempts = item.attempts - 1,
                nextAttemptAt = until,
                statusReason = reason,
                updatedAt = clock.now(),
            ),
        )
        return Result.DEFERRED
    }

    private suspend fun completeEnrollmentIfDone(item: ExecutionItem) {
        if (item.source != ExecutionSource.SEQUENCE) return
        val enrollment = sequences.getEnrollment(item.sourceId) ?: return
        val pending = executions.bySource(ExecutionSource.SEQUENCE, enrollment.id).any { !it.status.isTerminal }
        if (!pending && enrollment.status == EnrollmentStatus.ACTIVE) {
            sequences.upsertEnrollment(enrollment.copy(status = EnrollmentStatus.COMPLETED, updatedAt = clock.now()))
        }
    }

    /** Polls adapters that can confirm delivery. Only a confirmed report moves a row to DELIVERED. */
    private suspend fun refreshDeliveries() {
        val since = clock.now() - DELIVERY_LOOKBACK_MS
        executions.withStatus(setOf(MessageStatus.SUBMITTED))
            .filter { it.providerRef != null && it.updatedAt >= since }
            .forEach { item ->
                val adapter = adapters.get(item.channel) ?: return@forEach
                if (!adapter.descriptor.supports(ChannelCapability.DELIVERY_STATUS)) return@forEach
                val report = runCatching { adapter.deliveryStatus(item.providerRef.orEmpty()) }.getOrNull() ?: return@forEach
                val target = when (report.state) {
                    DeliveryState.DELIVERED -> MessageStatus.DELIVERED
                    DeliveryState.FAILED -> MessageStatus.FAILED
                    DeliveryState.PENDING, DeliveryState.UNKNOWN -> return@forEach
                }
                if (executions.compareAndSetStatus(item.id, MessageStatus.SUBMITTED, target, clock.now())) {
                    val updated = item.copy(status = target, statusReason = report.detail ?: target.label, updatedAt = clock.now())
                    executions.update(updated)
                    val type = if (target == MessageStatus.DELIVERED) ActivityType.DELIVERY_CONFIRMED else ActivityType.EXECUTION_FAILED
                    val result = if (target == MessageStatus.DELIVERED) ActivityResult.SUCCESS else ActivityResult.FAILED
                    activity.forExecution(updated, type, result, updated.statusReason)
                }
            }
    }

    companion object {
        const val BATCH_SIZE = 50
        const val MAX_BATCHES = 10
        const val LOCKED_RECHECK_MS = 5 * MINUTE_MS
        const val STALE_EXECUTION_MS = 5 * MINUTE_MS
        const val REMATERIALIZE_EVERY_MS = 12 * 60 * MINUTE_MS
        const val DELIVERY_LOOKBACK_MS = 3 * DAY_MS
        val HANDOFF_CODES = setOf(
            FailureCode.PERMISSION_MISSING, FailureCode.NOT_CONFIGURED, FailureCode.UNSUPPORTED, FailureCode.NO_REPLY_TARGET,
        )
    }
}
