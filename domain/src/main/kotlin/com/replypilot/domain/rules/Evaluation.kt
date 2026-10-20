package com.replypilot.domain.rules

import com.replypilot.domain.model.AutoReplyRule
import com.replypilot.domain.model.AutomationSettings
import com.replypilot.domain.model.Contact
import com.replypilot.domain.model.IncomingMessage
import com.replypilot.domain.model.MessageTemplate
import com.replypilot.domain.model.ReplyMode
import com.replypilot.domain.model.ReplyRecord
import java.time.ZoneId

enum class SuppressionReason(val label: String) {
    AUTOMATION_PAUSED("Automation is paused"),
    OWN_MESSAGE("Message was sent by you or by ReplyPilot"),
    DUPLICATE_EVENT("Same message was already processed"),
    GROUPS_DISABLED("Auto-replies in group chats are turned off"),
    BLOCKED_SENDER("Sender is on your blocked list"),
    OPT_OUT_REQUEST("Sender asked to opt out"),
    OPTED_OUT("Contact has opted out"),
    BLOCKED_PHRASE("Message contains a blocked phrase"),
    HUMAN_TAKEOVER("You have taken over this conversation"),
    LOOP_DETECTED("Possible auto-reply loop"),
    QUIET_HOURS("Quiet hours"),
    GLOBAL_DAILY_LIMIT("Daily message limit reached"),
    CONTACT_DAILY_LIMIT("Reply limit for this contact reached"),
    CHANNEL_DAILY_LIMIT("Channel daily limit reached"),
    COOLDOWN("Rule cooldown is active"),
    RULE_DAILY_LIMIT("Rule's daily limit reached"),
    UNRESOLVED_VARIABLES("Reply has unresolved placeholders"),
    CHANNEL_CANNOT_REPLY("Channel can't reply automatically"),
    AI_NOT_CONFIGURED("No AI provider is configured"),
}

data class Suppression(val reason: SuppressionReason, val detail: String)

data class CheckTrace(val label: String, val passed: Boolean, val detail: String = "")

data class RuleTrace(
    val rule: AutoReplyRule,
    val checks: List<CheckTrace>,
    val matched: Boolean,
    val suppression: Suppression? = null,
    val response: String? = null,
)

data class PlannedReply(
    val rule: AutoReplyRule,
    val text: String,
    val mode: ReplyMode,
    val delaySeconds: Int,
)

data class Evaluation(
    val message: IncomingMessage,
    val globalChecks: List<CheckTrace>,
    val globalSuppression: Suppression?,
    val traces: List<RuleTrace>,
    val replies: List<PlannedReply>,
    val optOutDetected: Boolean,
    val contact: Contact?,
) {
    val matchedTraces: List<RuleTrace> get() = traces.filter { it.matched }
    val firstMatch: RuleTrace? get() = matchedTraces.firstOrNull()
    val wouldSend: Boolean get() = replies.isNotEmpty()

    /** The main reason nothing is sent, if nothing is sent. */
    val blockingSuppression: Suppression?
        get() = globalSuppression ?: if (replies.isEmpty()) firstMatch?.suppression else null
}

/**
 * Everything the engine needs, captured as plain data so evaluation is a pure function.
 * Built from repositories at runtime and from fixtures in the simulator and tests.
 */
data class RuleContext(
    val now: Long,
    val zone: ZoneId,
    val settings: AutomationSettings,
    val contact: Contact?,
    /** Auto-replies sent in the last 24 hours (all conversations). */
    val recentReplies: List<ReplyRecord>,
    val isDuplicate: Boolean,
    val takeoverActive: Boolean,
    val channelCanAutoReply: Boolean,
    val channelReplyDetail: String = "",
    val sentToday: Int,
    val sentTodayOnChannel: Int,
    val templates: Map<String, MessageTemplate> = emptyMap(),
    val globalVariables: Map<String, String> = emptyMap(),
)
