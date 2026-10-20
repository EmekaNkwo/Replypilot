package com.replypilot.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class ActivityType(val label: String) {
    MESSAGE_SCHEDULED("Message scheduled"),
    SCHEDULE_UPDATED("Schedule updated"),
    SCHEDULE_PAUSED("Schedule paused"),
    SCHEDULE_CANCELLED("Schedule cancelled"),
    EXECUTION_STARTED("Execution started"),
    MESSAGE_SUBMITTED("Message submitted"),
    DELIVERY_CONFIRMED("Delivery confirmed"),
    MANUAL_ACTION_REQUIRED("Manual action required"),
    RULE_MATCHED("Rule matched"),
    NO_RULE_MATCHED("No rule matched"),
    AUTO_REPLY_SUPPRESSED("Auto-reply suppressed"),
    REPLY_SENT("Reply sent"),
    REPLY_AWAITING_APPROVAL("Reply awaiting approval"),
    EXECUTION_FAILED("Execution failed"),
    RETRY_SCHEDULED("Retry scheduled"),
    EXECUTION_SKIPPED("Execution skipped"),
    EXECUTION_EXPIRED("Execution expired"),
    PERMISSION_UNAVAILABLE("Permission unavailable"),
    OPT_OUT_RECORDED("Opt-out recorded"),
    HUMAN_TAKEOVER_STARTED("You took over a conversation"),
    SEQUENCE_STARTED("Sequence started"),
    SEQUENCE_STOPPED("Sequence stopped"),
    AUTOMATION_PAUSED("Automation paused"),
    AUTOMATION_RESUMED("Automation resumed"),
    QUEUE_RECOVERED("Queue recovered after restart"),
    MESSAGE_CANCELLED("Message cancelled"),
}

@Serializable
enum class ActivityResult(val label: String) {
    SUCCESS("Success"),
    INFO("Info"),
    SUPPRESSED("Suppressed"),
    PENDING("Pending"),
    FAILED("Failed"),
}

/**
 * One audit record. Holds identifiers and a diagnostic reason, never full message bodies.
 * [simulated] marks demo/simulator activity so it is never mistaken for a real send.
 */
@Serializable
data class ActivityEvent(
    val id: String,
    val at: Long,
    val type: ActivityType,
    val result: ActivityResult,
    val channel: ChannelId? = null,
    val source: ExecutionSource? = null,
    val sourceId: String? = null,
    val ruleId: String? = null,
    val executionId: String? = null,
    val recipientRef: String? = null,
    val attempt: Int? = null,
    val reason: String? = null,
    val simulated: Boolean = false,
)
