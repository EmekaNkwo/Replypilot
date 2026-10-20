package com.replypilot.features.rules

import com.replypilot.domain.model.AutoReplyRule
import com.replypilot.domain.model.MatchType
import com.replypilot.domain.model.MessageTemplate
import com.replypilot.domain.model.RuleCondition
import com.replypilot.domain.rules.RegexGuard
import com.replypilot.domain.scheduling.TimeWindows

/** Editor-level checks before a rule may be saved. Matching itself is the domain's job. */
object RuleValidation {
    fun errors(rule: AutoReplyRule, templates: Map<String, MessageTemplate>): List<String> = buildList {
        if (rule.name.isBlank()) add("Give the rule a name.")
        when {
            rule.templateId != null && rule.templateId !in templates -> add("The selected template no longer exists.")
            rule.templateId == null && rule.response.isBlank() -> add("Write a reply or pick a template.")
        }
        if (rule.cooldownMinutes < 0) add("Cooldown can't be negative.")
        if (rule.delaySeconds < 0) add("Delay can't be negative.")
        if ((rule.maxRepliesPerDay ?: 1) < 1) add("Daily limit must be at least 1.")
        rule.conditions.forEachIndexed { index, condition -> conditionError(condition)?.let { add("Condition ${index + 1}: $it") } }
    }

    fun conditionError(condition: RuleCondition): String? = when (condition) {
        is RuleCondition.MessageMatches -> when {
            condition.matchType == MatchType.ANY_MESSAGE -> null
            condition.patterns.none { it.isNotBlank() } -> "add at least one word or phrase to match."
            condition.matchType == MatchType.REGEX -> regexError(condition.patterns.first())
            else -> null
        }
        is RuleCondition.SenderIsContact -> if (condition.contactIds.isEmpty()) "choose at least one contact." else null
        is RuleCondition.SenderInGroup -> if (condition.groups.isEmpty()) "choose at least one group." else null
        is RuleCondition.OnWeekdays -> if (condition.days.isEmpty()) "choose at least one day." else null
        is RuleCondition.InDateRange -> {
            val start = TimeWindows.parseDate(condition.startDate)
            val end = TimeWindows.parseDate(condition.endDate)
            when {
                start == null || end == null -> "dates must look like 2026-10-15."
                end.isBefore(start) -> "the end date is before the start date."
                else -> null
            }
        }
        is RuleCondition.AfterRuleExecuted -> when {
            condition.ruleId.isBlank() -> "choose the other rule."
            condition.withinMinutes < 1 -> "minutes must be at least 1."
            else -> null
        }
        RuleCondition.UnknownSender, is RuleCondition.ConversationIs, RuleCondition.WithinWorkingHours,
        RuleCondition.OutsideWorkingHours, RuleCondition.NoHumanTakeover -> null
    }

    fun regexError(pattern: String): String? = when (val check = RegexGuard.check(pattern)) {
        RegexGuard.Check.Valid -> null
        is RegexGuard.Check.Invalid -> check.reason
    }
}
