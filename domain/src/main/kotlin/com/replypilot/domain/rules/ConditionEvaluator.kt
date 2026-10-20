package com.replypilot.domain.rules

import com.replypilot.domain.common.MINUTE_MS
import com.replypilot.domain.model.ConversationKind
import com.replypilot.domain.model.IncomingMessage
import com.replypilot.domain.model.MatchType
import com.replypilot.domain.model.RuleCondition
import com.replypilot.domain.scheduling.TimeWindows
import java.time.DayOfWeek
import java.time.Instant
import java.time.format.TextStyle
import java.util.Locale

/** Evaluates one condition and explains the outcome in words a user can read. */
object ConditionEvaluator {
    fun evaluate(condition: RuleCondition, message: IncomingMessage, context: RuleContext): CheckTrace = when (condition) {
        is RuleCondition.MessageMatches -> messageMatches(condition, message.text)
        is RuleCondition.SenderIsContact -> {
            val contact = context.contact
            CheckTrace(
                "Sender is a selected contact",
                contact != null && contact.id in condition.contactIds,
                contact?.let { "Sender identified as ${it.displayName}" } ?: "Sender is not a saved contact",
            )
        }
        is RuleCondition.SenderInGroup -> {
            val groups = context.contact?.groups.orEmpty()
            val wanted = condition.groups.map { it.lowercase() }.toSet()
            CheckTrace(
                "Sender is in group ${condition.groups.joinToString(", ")}",
                groups.any { it.lowercase() in wanted },
                if (groups.isEmpty()) "Sender belongs to no groups" else "Sender groups: ${groups.joinToString(", ")}",
            )
        }
        RuleCondition.UnknownSender -> CheckTrace(
            "Sender is unknown",
            context.contact == null,
            context.contact?.let { "Sender is saved as ${it.displayName}" } ?: "No saved contact matches the sender",
        )
        is RuleCondition.ConversationIs -> {
            val actual = if (message.isGroup) ConversationKind.GROUP else ConversationKind.INDIVIDUAL
            CheckTrace("Conversation is ${condition.kind.label.lowercase()}", actual == condition.kind, "This is a ${actual.label.lowercase()}")
        }
        RuleCondition.WithinWorkingHours -> {
            val inside = TimeWindows.contains(context.settings.workingHours, context.now, context.zone)
            CheckTrace("Within working hours", inside, workingHoursDetail(context))
        }
        RuleCondition.OutsideWorkingHours -> {
            val inside = TimeWindows.contains(context.settings.workingHours, context.now, context.zone)
            CheckTrace("Outside working hours", !inside, workingHoursDetail(context))
        }
        is RuleCondition.OnWeekdays -> {
            val today = Instant.ofEpochMilli(context.now).atZone(context.zone).dayOfWeek
            CheckTrace(
                "On ${condition.days.sorted().joinToString(", ") { dayName(it) }}",
                today.value in condition.days,
                "Today is ${today.getDisplayName(TextStyle.FULL, Locale.getDefault())}",
            )
        }
        is RuleCondition.InDateRange -> {
            val today = Instant.ofEpochMilli(context.now).atZone(context.zone).toLocalDate()
            val start = TimeWindows.parseDate(condition.startDate)
            val end = TimeWindows.parseDate(condition.endDate)
            val inside = start != null && end != null && !today.isBefore(start) && !today.isAfter(end)
            CheckTrace("Between ${condition.startDate} and ${condition.endDate}", inside, "Today is $today")
        }
        is RuleCondition.AfterRuleExecuted -> {
            val since = context.now - condition.withinMinutes * MINUTE_MS
            val hit = context.recentReplies.any {
                it.ruleId == condition.ruleId && it.channel == message.channel &&
                    it.conversationKey == message.conversationKey && it.at >= since
            }
            CheckTrace(
                "Another rule replied in the last ${condition.withinMinutes} min",
                hit,
                if (hit) "That rule replied recently in this conversation" else "That rule hasn't replied here recently",
            )
        }
        RuleCondition.NoHumanTakeover -> CheckTrace(
            "No human has taken over",
            !context.takeoverActive,
            if (context.takeoverActive) "You are handling this conversation" else "Conversation is automated",
        )
    }

    private fun workingHoursDetail(context: RuleContext): String {
        val wh = context.settings.workingHours
        val local = Instant.ofEpochMilli(context.now).atZone(context.zone)
        return "Working hours ${wh.start}–${wh.end}; it is ${local.toLocalTime().withNano(0).withSecond(0)} on ${local.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())}"
    }

    private fun dayName(day: Int): String =
        runCatching { DayOfWeek.of(day).getDisplayName(TextStyle.SHORT, Locale.getDefault()) }.getOrDefault("?")

    fun messageMatches(condition: RuleCondition.MessageMatches, rawText: String): CheckTrace {
        val patterns = condition.patterns.map { it.trim() }.filter { it.isNotEmpty() }
        val label = when (condition.matchType) {
            MatchType.ANY_MESSAGE -> "Any message"
            else -> "Message ${condition.matchType.label.lowercase()} ${patterns.joinToString(" / ") { "\"$it\"" }}"
        }
        if (condition.matchType == MatchType.ANY_MESSAGE) return CheckTrace(label, true, "Every message matches")
        if (patterns.isEmpty()) return CheckTrace(label, false, "No text to match was configured")

        val text = if (condition.caseSensitive) rawText.trim() else rawText.trim().lowercase()
        fun norm(p: String) = if (condition.caseSensitive) p else p.lowercase()

        val hit: String? = when (condition.matchType) {
            MatchType.EXACT -> patterns.firstOrNull { text == norm(it) }
            MatchType.CONTAINS -> patterns.firstOrNull { text.contains(norm(it)) }
            MatchType.STARTS_WITH -> patterns.firstOrNull { text.startsWith(norm(it)) }
            MatchType.ANY_KEYWORD -> patterns.firstOrNull { keyword ->
                Regex("(^|\\W)${Regex.escape(norm(keyword))}($|\\W)").containsMatchIn(text)
            }
            MatchType.REGEX -> {
                val pattern = patterns.first()
                when (RegexGuard.matches(pattern, rawText, condition.caseSensitive)) {
                    true -> pattern
                    false -> null
                    null -> return CheckTrace(label, false, "Pattern is invalid, unsafe or took too long, so it was skipped")
                }
            }
            MatchType.ANY_MESSAGE -> null
        }
        return CheckTrace(label, hit != null, hit?.let { "Matched \"$it\"" } ?: "No match")
    }
}
