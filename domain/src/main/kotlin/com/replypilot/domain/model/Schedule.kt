package com.replypilot.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class Frequency(val label: String) {
    ONCE("Once"),
    DAILY("Daily"),
    WEEKLY("Weekly"),
    MONTHLY("Monthly"),
    YEARLY("Yearly"),
}

@Serializable
enum class MonthlyMode(val label: String) {
    DAY_OF_MONTH("Same day of month"),
    NTH_WEEKDAY("Same weekday of month"),
    LAST_DAY("Last day of month"),
}

/**
 * FIXED keeps the wall-clock time in the schedule's own zone (09:00 in Lagos stays 09:00 in Lagos).
 * FOLLOW_DEVICE re-interprets the wall-clock time in whatever zone the device is currently in.
 */
@Serializable
enum class ZoneMode(val label: String) {
    FIXED("Keep schedule time zone"),
    FOLLOW_DEVICE("Follow device time zone"),
}

@Serializable
enum class QuietHoursBehavior(val label: String) {
    DEFER("Wait until quiet hours end"),
    SKIP("Skip this occurrence"),
    SEND_ANYWAY("Send anyway"),
}

/** Day-of-week values follow ISO-8601: 1 = Monday .. 7 = Sunday. Dates are ISO yyyy-MM-dd, times HH:mm. */
@Serializable
data class ScheduleSpec(
    val startDate: String,
    val time: String,
    val timeZone: String,
    val zoneMode: ZoneMode = ZoneMode.FIXED,
    val frequency: Frequency = Frequency.ONCE,
    val interval: Int = 1,
    val weekdays: Set<Int> = emptySet(),
    val monthlyMode: MonthlyMode = MonthlyMode.DAY_OF_MONTH,
    val endDate: String? = null,
    val maxOccurrences: Int? = null,
    val skipDates: Set<String> = emptySet(),
)

@Serializable
data class SendPolicy(
    val expireAfterMinutes: Int = 24 * 60,
    val maxRetries: Int = 3,
    val quietHours: QuietHoursBehavior = QuietHoursBehavior.DEFER,
)

/**
 * INDIVIDUAL: addressed by phone/handle. GROUP: a group chat by its exact name.
 * CHAT: a chat the user picked inside the messaging app (a person or a group), addressed by its exact title.
 */
@Serializable
enum class RecipientKind { INDIVIDUAL, GROUP, CHAT }

/** One destination. For GROUP and CHAT the address is the chat's name exactly as the messaging app shows it. */
@Serializable
data class Recipient(
    val address: String,
    val displayName: String,
    val contactId: String? = null,
    val kind: RecipientKind = RecipientKind.INDIVIDUAL,
) {
    val isGroup: Boolean get() = kind == RecipientKind.GROUP

    /** Reached by searching for its name in the app rather than by a phone number. */
    val isNamedChat: Boolean get() = kind == RecipientKind.GROUP || kind == RecipientKind.CHAT
}

@Serializable
enum class ScheduleState(val label: String) {
    DRAFT("Draft"),
    ACTIVE("Active"),
    PAUSED("Paused"),
    COMPLETED("Completed"),
    CANCELLED("Cancelled"),
}

/**
 * A persistent schedule definition. It never sends anything itself: the materializer turns its
 * upcoming occurrences into individual [ExecutionItem] rows that the queue processes.
 */
@Serializable
data class ScheduledMessage(
    val id: String,
    val title: String,
    val channel: ChannelId,
    val recipients: List<Recipient>,
    val body: String,
    val templateId: String? = null,
    /** Files sent with every occurrence; the body becomes their caption. */
    val attachments: List<Attachment> = emptyList(),
    val spec: ScheduleSpec,
    val policy: SendPolicy = SendPolicy(),
    val state: ScheduleState = ScheduleState.ACTIVE,
    /** Occurrences at or before this instant have already been turned into execution items. */
    val materializedUntil: Long = 0,
    /** Bumped on every edit so executions created for an older version never collide with new ones. */
    val revision: Int = 1,
    val isDemo: Boolean = false,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
)
