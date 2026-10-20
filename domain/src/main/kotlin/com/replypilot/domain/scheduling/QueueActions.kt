package com.replypilot.domain.scheduling

import com.replypilot.domain.automation.ActivityLogger
import com.replypilot.domain.common.Clock
import com.replypilot.domain.common.MINUTE_MS
import com.replypilot.domain.model.ActivityResult
import com.replypilot.domain.model.ActivityType
import com.replypilot.domain.model.ChannelId
import com.replypilot.domain.model.ConversationState
import com.replypilot.domain.model.FailureCode
import com.replypilot.domain.model.MessageStateMachine
import com.replypilot.domain.model.MessageStatus
import com.replypilot.domain.repository.ConversationRepository
import com.replypilot.domain.repository.ExecutionRepository
import com.replypilot.domain.repository.SettingsRepository
import com.replypilot.domain.repository.WakeScheduler

sealed interface ActionResult {
    data object Done : ActionResult
    data class Rejected(val reason: String) : ActionResult
}

/** User commands on the queue and on automation as a whole. */
class QueueActions(
    private val executions: ExecutionRepository,
    private val conversations: ConversationRepository,
    private val settings: SettingsRepository,
    private val activity: ActivityLogger,
    private val clock: Clock,
    private val wake: WakeScheduler,
) {
    /** The global stop switch. Paused automation sends nothing: no schedules, sequences or replies. */
    suspend fun setAutomationPaused(paused: Boolean) {
        settings.update { it.copy(automationPaused = paused) }
        activity.log(
            if (paused) ActivityType.AUTOMATION_PAUSED else ActivityType.AUTOMATION_RESUMED,
            ActivityResult.INFO,
        )
        wake.scheduleWake(if (paused) null else clock.now())
    }

    suspend fun cancel(executionId: String): ActionResult {
        val result = transition(executionId, MessageStatus.CANCELLED, CANCELLED_REASON)
        if (result == ActionResult.Done) {
            executions.get(executionId)?.let { activity.forExecution(it, ActivityType.MESSAGE_CANCELLED, ActivityResult.INFO, CANCELLED_REASON) }
        }
        return result
    }

    /** Re-queues a failed or flagged row for one more attempt. Interrupted rows require explicit confirmation in the UI. */
    suspend fun retry(executionId: String): ActionResult {
        val item = executions.get(executionId) ?: return ActionResult.Rejected("Message not found")
        if (item.status !in setOf(MessageStatus.FAILED, MessageStatus.NEEDS_ATTENTION)) {
            return ActionResult.Rejected("Only failed messages or messages needing attention can be retried")
        }
        if (item.failureCode == FailureCode.UNRESOLVED_VARIABLES) {
            return ActionResult.Rejected("This message still has empty placeholders. Edit the schedule (or add the contact's value and save the schedule again) instead of retrying.")
        }
        val now = clock.now()
        val requeued = item.copy(
            status = MessageStatus.SCHEDULED,
            nextAttemptAt = now,
            maxAttempts = item.attempts + 1,
            expiresAt = item.expiresAt?.let { maxOf(it, now + RETRY_GRACE_MS) },
            statusReason = "Retry requested by you",
            updatedAt = now,
        )
        if (!executions.replaceIfStatus(requeued, item.status)) {
            return ActionResult.Rejected("The message changed; refresh and try again")
        }
        activity.forExecution(requeued, ActivityType.RETRY_SCHEDULED, ActivityResult.PENDING, "Retry requested by you")
        wake.scheduleWake(now)
        return ActionResult.Done
    }

    /** Approves a reply or sequence step that was waiting for review, optionally with edited text. */
    suspend fun approve(executionId: String, editedBody: String? = null): ActionResult {
        val item = executions.get(executionId) ?: return ActionResult.Rejected("Message not found")
        if (item.status != MessageStatus.AWAITING_REVIEW) return ActionResult.Rejected("This message isn't waiting for review")
        val body = editedBody?.trim()?.takeIf { it.isNotEmpty() } ?: item.body
        val now = clock.now()
        val runAt = maxOf(item.plannedAt, now)
        val approved = item.copy(
            status = MessageStatus.SCHEDULED,
            body = body,
            nextAttemptAt = runAt,
            // Approval is a fresh decision: give it a full window rather than the original draft's expiry.
            expiresAt = item.expiresAt?.let { maxOf(it, runAt + RETRY_GRACE_MS) },
            statusReason = "Approved by you",
            updatedAt = now,
        )
        if (!executions.replaceIfStatus(approved, MessageStatus.AWAITING_REVIEW)) {
            return ActionResult.Rejected("The message changed; refresh and try again")
        }
        wake.scheduleWake(runAt)
        return ActionResult.Done
    }

    /** Clears failed messages from the Outbox. What happened stays in the activity history. */
    suspend fun clearFailed(): Int = executions.deleteWithStatus(setOf(MessageStatus.FAILED))

    suspend fun reject(executionId: String): ActionResult = transition(executionId, MessageStatus.CANCELLED, "Rejected by you")

    /** Records that the user finished a manual handoff themselves. */
    suspend fun markSentManually(executionId: String, reason: String = "Sent manually by you"): ActionResult {
        val result = transition(executionId, MessageStatus.SUBMITTED, reason)
        if (result == ActionResult.Done) {
            executions.get(executionId)?.let {
                activity.forExecution(it, ActivityType.MESSAGE_SUBMITTED, ActivityResult.SUCCESS, reason)
            }
        }
        return result
    }

    suspend fun takeOver(channel: ChannelId, conversationKey: String, minutes: Int? = null) {
        val duration = (minutes ?: settings.current().humanTakeoverMinutes) * MINUTE_MS
        conversations.setState(ConversationState(channel, conversationKey, clock.now() + duration))
    }

    suspend fun releaseTakeover(channel: ChannelId, conversationKey: String) {
        conversations.setState(ConversationState(channel, conversationKey, null))
    }

    private suspend fun transition(executionId: String, to: MessageStatus, reason: String): ActionResult {
        val item = executions.get(executionId) ?: return ActionResult.Rejected("Message not found")
        if (!MessageStateMachine.canTransition(item.status, to)) {
            return ActionResult.Rejected("Can't change a ${item.status.label.lowercase()} message to ${to.label.lowercase()}")
        }
        val now = clock.now()
        if (!executions.replaceIfStatus(item.copy(status = to, statusReason = reason, updatedAt = now), item.status)) {
            return ActionResult.Rejected("The message changed; refresh and try again")
        }
        return ActionResult.Done
    }

    companion object {
        const val CANCELLED_REASON = "Cancelled by you"
        const val RETRY_GRACE_MS = 30 * MINUTE_MS
    }
}
