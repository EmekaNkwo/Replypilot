package com.replypilot.domain.model

import kotlinx.serialization.Serializable

/**
 * Lifecycle of a single execution (one message to one recipient).
 * SUBMITTED means the channel accepted the message; DELIVERED is only set when the channel confirms it.
 */
@Serializable
enum class MessageStatus(val label: String, val isTerminal: Boolean) {
    DRAFT("Draft", false),
    SCHEDULED("Scheduled", false),
    AWAITING_REVIEW("Awaiting review", false),
    DUE("Due", false),
    EXECUTING("Executing", false),
    RETRY_SCHEDULED("Retry scheduled", false),
    SUBMITTED("Submitted", true),
    DELIVERED("Delivered", true),
    PAUSED("Paused", false),
    CANCELLED("Cancelled", true),
    EXPIRED("Expired", true),
    SKIPPED("Skipped", true),
    FAILED("Failed", true),
    NEEDS_ATTENTION("Needs attention", false),
    ;

    companion object {
        /** States the queue may pick up for execution. */
        val RUNNABLE: Set<MessageStatus> = setOf(SCHEDULED, DUE, RETRY_SCHEDULED)
        /** States that count as "pending" for dashboards and outbox. */
        val PENDING: Set<MessageStatus> = setOf(SCHEDULED, DUE, EXECUTING, RETRY_SCHEDULED, PAUSED, AWAITING_REVIEW)
        val SUCCESS: Set<MessageStatus> = setOf(SUBMITTED, DELIVERED)
    }
}

object MessageStateMachine {
    private val transitions: Map<MessageStatus, Set<MessageStatus>> =
        mapOf(
            MessageStatus.DRAFT to setOf(MessageStatus.SCHEDULED, MessageStatus.CANCELLED),
            MessageStatus.SCHEDULED to setOf(
                MessageStatus.DUE, MessageStatus.EXECUTING, MessageStatus.PAUSED, MessageStatus.CANCELLED,
                MessageStatus.EXPIRED, MessageStatus.SKIPPED, MessageStatus.NEEDS_ATTENTION, MessageStatus.AWAITING_REVIEW,
            ),
            MessageStatus.AWAITING_REVIEW to setOf(MessageStatus.SCHEDULED, MessageStatus.CANCELLED, MessageStatus.EXPIRED),
            MessageStatus.DUE to setOf(
                MessageStatus.EXECUTING, MessageStatus.PAUSED, MessageStatus.CANCELLED, MessageStatus.EXPIRED,
                MessageStatus.SKIPPED,
            ),
            MessageStatus.EXECUTING to setOf(
                MessageStatus.SUBMITTED, MessageStatus.FAILED, MessageStatus.RETRY_SCHEDULED,
                MessageStatus.NEEDS_ATTENTION, MessageStatus.SKIPPED, MessageStatus.EXPIRED, MessageStatus.SCHEDULED,
                MessageStatus.CANCELLED, MessageStatus.PAUSED,
            ),
            MessageStatus.RETRY_SCHEDULED to setOf(
                MessageStatus.EXECUTING, MessageStatus.PAUSED, MessageStatus.CANCELLED, MessageStatus.EXPIRED,
            ),
            MessageStatus.SUBMITTED to setOf(MessageStatus.DELIVERED, MessageStatus.FAILED),
            MessageStatus.PAUSED to setOf(MessageStatus.SCHEDULED, MessageStatus.CANCELLED, MessageStatus.EXPIRED),
            MessageStatus.NEEDS_ATTENTION to setOf(
                MessageStatus.SCHEDULED, MessageStatus.SUBMITTED, MessageStatus.CANCELLED, MessageStatus.EXPIRED,
            ),
            MessageStatus.FAILED to setOf(MessageStatus.SCHEDULED, MessageStatus.CANCELLED),
        )

    fun canTransition(from: MessageStatus, to: MessageStatus): Boolean = to in transitions[from].orEmpty()

    fun allowedSources(to: MessageStatus): Set<MessageStatus> =
        transitions.filterValues { to in it }.keys
}
