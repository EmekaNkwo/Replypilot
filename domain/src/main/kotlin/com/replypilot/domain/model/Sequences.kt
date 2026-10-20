package com.replypilot.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class SequenceStep(
    val dayOffset: Int,
    val time: String,
    val body: String,
    val templateId: String? = null,
    val requiresReview: Boolean = false,
)

@Serializable
data class MessageSequence(
    val id: String,
    val name: String,
    val channel: ChannelId,
    val steps: List<SequenceStep>,
    val stopOnReply: Boolean = true,
    val stopOnOptOut: Boolean = true,
    val quietHours: QuietHoursBehavior = QuietHoursBehavior.DEFER,
    val timeZone: String,
    val enabled: Boolean = true,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
) {
    companion object {
        const val MAX_STEPS = 10
    }
}

@Serializable
enum class EnrollmentStatus(val label: String) {
    ACTIVE("Active"),
    COMPLETED("Completed"),
    STOPPED("Stopped"),
}

@Serializable
data class SequenceEnrollment(
    val id: String,
    val sequenceId: String,
    val channel: ChannelId,
    val recipient: Recipient,
    val startedAt: Long,
    val status: EnrollmentStatus = EnrollmentStatus.ACTIVE,
    val stopReason: String? = null,
    val updatedAt: Long = 0,
)
