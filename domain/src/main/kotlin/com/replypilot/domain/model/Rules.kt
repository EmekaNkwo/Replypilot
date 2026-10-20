package com.replypilot.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class MatchType(val label: String) {
    ANY_MESSAGE("Any message"),
    EXACT("Is exactly"),
    CONTAINS("Contains"),
    STARTS_WITH("Starts with"),
    ANY_KEYWORD("Contains any keyword"),
    REGEX("Matches pattern"),
}

@Serializable
enum class ConversationKind(val label: String) {
    INDIVIDUAL("Individual chat"),
    GROUP("Group chat"),
}

@Serializable
enum class ConditionCombinator(val label: String) {
    ALL("All conditions"),
    ANY("Any condition"),
}

@Serializable
sealed interface RuleCondition {
    @Serializable
    @SerialName("message")
    data class MessageMatches(
        val matchType: MatchType,
        val patterns: List<String>,
        val caseSensitive: Boolean = false,
    ) : RuleCondition

    @Serializable
    @SerialName("sender_contact")
    data class SenderIsContact(val contactIds: Set<String>) : RuleCondition

    @Serializable
    @SerialName("sender_group")
    data class SenderInGroup(val groups: Set<String>) : RuleCondition

    @Serializable
    @SerialName("unknown_sender")
    data object UnknownSender : RuleCondition

    @Serializable
    @SerialName("conversation")
    data class ConversationIs(val kind: ConversationKind) : RuleCondition

    @Serializable
    @SerialName("working_hours")
    data object WithinWorkingHours : RuleCondition

    @Serializable
    @SerialName("outside_working_hours")
    data object OutsideWorkingHours : RuleCondition

    @Serializable
    @SerialName("weekdays")
    data class OnWeekdays(val days: Set<Int>) : RuleCondition

    @Serializable
    @SerialName("date_range")
    data class InDateRange(val startDate: String, val endDate: String) : RuleCondition

    @Serializable
    @SerialName("after_rule")
    data class AfterRuleExecuted(val ruleId: String, val withinMinutes: Int) : RuleCondition

    @Serializable
    @SerialName("no_takeover")
    data object NoHumanTakeover : RuleCondition
}

@Serializable
enum class ReplyMode(val label: String, val description: String) {
    RULE_AUTOMATIC("Send automatically", "This rule sends its predefined response when it matches."),
    MANUAL_APPROVAL("Ask me first", "A reply is prepared and waits for your approval."),
    AI_ASSISTED("AI-assisted", "An enabled AI provider drafts a reply, which waits for your approval unless auto-send is allowed."),
}

@Serializable
data class AutoReplyRule(
    val id: String,
    val name: String,
    val enabled: Boolean = true,
    /** Empty means every channel that can receive messages. */
    val channels: Set<ChannelId> = emptySet(),
    val combinator: ConditionCombinator = ConditionCombinator.ALL,
    val conditions: List<RuleCondition> = emptyList(),
    val response: String,
    val templateId: String? = null,
    val replyMode: ReplyMode = ReplyMode.RULE_AUTOMATIC,
    /** Higher runs first. */
    val priority: Int = 0,
    val stopProcessing: Boolean = true,
    val cooldownMinutes: Int = 60,
    val maxRepliesPerDay: Int? = null,
    val delaySeconds: Int = 0,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
)
