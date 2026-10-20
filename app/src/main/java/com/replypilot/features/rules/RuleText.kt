package com.replypilot.features.rules

import com.replypilot.domain.model.AutoReplyRule
import com.replypilot.domain.model.ConditionCombinator
import com.replypilot.domain.model.ConversationKind
import com.replypilot.domain.model.MatchType
import com.replypilot.domain.model.MessageTemplate
import com.replypilot.domain.model.ReplyMode
import com.replypilot.domain.model.RuleCondition
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/** Plain-language descriptions of rules, so a user can read what a rule does without opening it. */
object RuleText {
    fun dayShort(day: Int): String =
        runCatching { DayOfWeek.of(day).getDisplayName(TextStyle.SHORT, Locale.getDefault()) }.getOrDefault("?")

    fun matchVerb(type: MatchType): String = when (type) {
        MatchType.ANY_MESSAGE -> "is any message"
        MatchType.EXACT -> "is exactly"
        MatchType.CONTAINS -> "contains"
        MatchType.STARTS_WITH -> "starts with"
        MatchType.ANY_KEYWORD -> "mentions"
        MatchType.REGEX -> "matches the pattern"
    }

    fun condition(
        condition: RuleCondition,
        contactNames: Map<String, String> = emptyMap(),
        ruleNames: Map<String, String> = emptyMap(),
    ): String = when (condition) {
        is RuleCondition.MessageMatches -> if (condition.matchType == MatchType.ANY_MESSAGE) {
            "any message"
        } else {
            val patterns = condition.patterns.filter { it.isNotBlank() }
            val list = if (patterns.isEmpty()) "…" else patterns.joinToString(" or ") { "\"$it\"" }
            "the message ${matchVerb(condition.matchType)} $list"
        }
        is RuleCondition.SenderIsContact -> {
            val names = condition.contactIds.mapNotNull { contactNames[it] }
            when {
                names.isEmpty() -> "the sender is a chosen contact"
                names.size <= 2 -> "the sender is ${names.joinToString(" or ")}"
                else -> "the sender is one of ${names.size} contacts"
            }
        }
        is RuleCondition.SenderInGroup -> "the sender is in ${condition.groups.joinToString(" or ").ifBlank { "a chosen group" }}"
        RuleCondition.UnknownSender -> "the sender isn't a saved contact"
        is RuleCondition.ConversationIs -> if (condition.kind == ConversationKind.GROUP) "it's a group chat" else "it's an individual chat"
        RuleCondition.WithinWorkingHours -> "it's during working hours"
        RuleCondition.OutsideWorkingHours -> "it's outside working hours"
        is RuleCondition.OnWeekdays -> "it's ${condition.days.sorted().joinToString(", ") { dayShort(it) }.ifBlank { "a chosen day" }}"
        is RuleCondition.InDateRange -> "the date is ${condition.startDate} – ${condition.endDate}"
        is RuleCondition.AfterRuleExecuted ->
            "“${ruleNames[condition.ruleId] ?: "another rule"}” replied in the last ${condition.withinMinutes} min"
        RuleCondition.NoHumanTakeover -> "you haven't taken over the chat"
    }

    fun action(mode: ReplyMode): String = when (mode) {
        ReplyMode.RULE_AUTOMATIC -> "send"
        ReplyMode.MANUAL_APPROVAL -> "prepare for your approval"
        ReplyMode.AI_ASSISTED -> "have AI draft a reply guided by"
    }

    fun response(rule: AutoReplyRule, templates: Map<String, MessageTemplate>): String =
        rule.templateId?.let { id -> templates[id]?.let { "template “${it.name}”" } ?: "a missing template" }
            ?: "‘${rule.response.take(70)}${if (rule.response.length > 70) "…" else ""}’"

    fun summary(
        rule: AutoReplyRule,
        templates: Map<String, MessageTemplate>,
        contactNames: Map<String, String> = emptyMap(),
        ruleNames: Map<String, String> = emptyMap(),
    ): String {
        val channels = if (rule.channels.isEmpty()) "any channel" else rule.channels.joinToString(" or ") { it.displayName }
        val joiner = if (rule.combinator == ConditionCombinator.ALL) " and " else " or "
        val conditions = rule.conditions.map { condition(it, contactNames, ruleNames) }
        val ifPart = if (conditions.isEmpty()) "" else " and ${conditions.joinToString(joiner)}"
        return "When a message arrives on $channels$ifPart → ${action(rule.replyMode)} ${response(rule, templates)}"
    }
}

/** Every condition the editor can add. */
enum class ConditionKind(val label: String) {
    MESSAGE("Message text"),
    CONTACT("Specific contacts"),
    GROUP("Contact group"),
    UNKNOWN("Unknown sender"),
    CONVERSATION("Individual or group chat"),
    WITHIN_HOURS("During working hours"),
    OUTSIDE_HOURS("Outside working hours"),
    WEEKDAYS("On certain weekdays"),
    DATE_RANGE("Within a date range"),
    AFTER_RULE("After another rule replied"),
    NO_TAKEOVER("Only when you haven't taken over"),
    ;

    fun create(today: LocalDate = LocalDate.now()): RuleCondition = when (this) {
        MESSAGE -> RuleCondition.MessageMatches(MatchType.CONTAINS, emptyList())
        CONTACT -> RuleCondition.SenderIsContact(emptySet())
        GROUP -> RuleCondition.SenderInGroup(emptySet())
        UNKNOWN -> RuleCondition.UnknownSender
        CONVERSATION -> RuleCondition.ConversationIs(ConversationKind.INDIVIDUAL)
        WITHIN_HOURS -> RuleCondition.WithinWorkingHours
        OUTSIDE_HOURS -> RuleCondition.OutsideWorkingHours
        WEEKDAYS -> RuleCondition.OnWeekdays(setOf(1, 2, 3, 4, 5))
        DATE_RANGE -> RuleCondition.InDateRange(today.toString(), today.plusDays(7).toString())
        AFTER_RULE -> RuleCondition.AfterRuleExecuted("", 60)
        NO_TAKEOVER -> RuleCondition.NoHumanTakeover
    }
}
