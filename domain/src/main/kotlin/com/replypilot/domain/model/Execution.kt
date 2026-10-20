package com.replypilot.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class ExecutionSource(val label: String) {
    SCHEDULED("Scheduled message"),
    SEQUENCE("Sequence step"),
    AUTO_REPLY("Automatic reply"),
}

@Serializable
enum class FailureCode(val label: String, val permanent: Boolean) {
    PERMISSION_MISSING("Required permission is not granted", false),
    DEVICE_LOCKED("Phone is locked; it will be sent after you unlock it", false),
    NOT_CONFIGURED("Channel is not configured", true),
    UNSUPPORTED("Channel does not support this action", true),
    INVALID_RECIPIENT("Recipient address is not valid for this channel", true),
    NO_REPLY_TARGET("The conversation can no longer be replied to from the notification", true),
    RATE_LIMITED("Channel rate limit reached", false),
    NETWORK("Network unavailable", false),
    PROVIDER_ERROR("Provider returned an error", false),
    PROVIDER_REJECTED("Provider rejected the message", true),
    INTERRUPTED("Execution was interrupted; outcome unknown", true),
    UNRESOLVED_VARIABLES("Message contains unresolved placeholders", true),
    ATTACHMENT_MISSING("An attached file is missing", true),
    UNKNOWN("Unknown error", true),
}

/** What the user can do when a channel cannot send unattended. Executed by the app layer. */
@Serializable
data class ManualHandoff(
    val kind: Kind,
    val text: String,
    val uri: String? = null,
    val packageName: String? = null,
    /** Files to share with the message ([Kind.SHARE_FILES]). */
    val attachments: List<Attachment> = emptyList(),
    /** Who the share is for, when the target app can take it (email address, phone number for SMS/WhatsApp). */
    val recipientAddress: String? = null,
) {
    @Serializable
    enum class Kind(val label: String) {
        OPEN_APP_PREFILLED("Open app with message"),
        COMPOSE_SMS("Compose SMS"),
        COMPOSE_EMAIL("Compose email"),
        COPY_TEXT("Copy message"),
        SHARE_FILES("Share files"),
    }
}

/**
 * One row of the persistent execution queue: a single message to a single recipient.
 * [idempotencyKey] is unique so re-materialization or a twice-running worker cannot create a second send.
 */
@Serializable
data class ExecutionItem(
    val id: String,
    val idempotencyKey: String,
    val source: ExecutionSource,
    val sourceId: String,
    val occurrenceIndex: Int,
    val channel: ChannelId,
    val recipient: Recipient,
    val body: String,
    val attachments: List<Attachment> = emptyList(),
    val plannedAt: Long,
    val expiresAt: Long?,
    val status: MessageStatus,
    val attempts: Int = 0,
    val maxAttempts: Int = 1,
    val lastAttemptAt: Long? = null,
    val nextAttemptAt: Long,
    val failureCode: FailureCode? = null,
    val statusReason: String? = null,
    val providerRef: String? = null,
    val handoff: ManualHandoff? = null,
    /** Notification/conversation reference used to reply in place (auto-replies). */
    val replyTarget: String? = null,
    val quietHours: QuietHoursBehavior = QuietHoursBehavior.DEFER,
    val simulated: Boolean = false,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
) {
    companion object {
        fun key(source: ExecutionSource, sourceId: String, address: String, occurrence: Int): String =
            "${source.name}:$sourceId:${address.trim().lowercase()}:$occurrence"
    }
}
