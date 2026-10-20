package com.replypilot.domain.model

import kotlinx.serialization.Serializable

/** Daily window in local time. When [end] is before [start] the window crosses midnight. */
@Serializable
data class TimeWindow(
    val start: String,
    val end: String,
    val days: Set<Int> = (1..7).toSet(),
)

@Serializable
enum class QuietHoursAutoReply(val label: String) {
    SUPPRESS("Don't auto-reply during quiet hours"),
    ALLOW("Auto-replies may still be sent"),
}

/** Central automation policy; persisted in DataStore and shared by the scheduler and the rule engine. */
@Serializable
data class AutomationSettings(
    val automationPaused: Boolean = false,
    val workingHours: TimeWindow = TimeWindow("09:00", "17:00", setOf(1, 2, 3, 4, 5)),
    val quietHoursEnabled: Boolean = true,
    val quietHours: TimeWindow = TimeWindow("22:00", "07:00"),
    val quietHoursAutoReply: QuietHoursAutoReply = QuietHoursAutoReply.SUPPRESS,
    val maxRepliesPerContactPerDay: Int = 5,
    val maxMessagesPerDay: Int = 200,
    val perChannelDailyLimit: Map<ChannelId, Int> = emptyMap(),
    val loopThreshold: Int = 3,
    val loopWindowMinutes: Int = 10,
    val duplicateWindowMinutes: Int = 5,
    val humanTakeoverMinutes: Int = 120,
    val optOutKeywords: List<String> = listOf("stop", "unsubscribe", "opt out", "optout"),
    val blockedPhrases: List<String> = emptyList(),
    val blockedSenders: List<String> = emptyList(),
    val replyToGroups: Boolean = false,
    /** AI drafts are always reviewed unless the user explicitly enables auto-send with a real provider. */
    val aiAutoSend: Boolean = false,
    val aiMinConfidence: Double = 0.85,
    val businessName: String = "",
    val supportHours: String = "",
    val defaultTimeZone: String = "UTC",
)
