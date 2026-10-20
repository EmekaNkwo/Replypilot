package com.replypilot.domain.rules

import com.replypilot.domain.common.DAY_MS
import com.replypilot.domain.common.Fingerprints
import com.replypilot.domain.common.MINUTE_MS
import com.replypilot.domain.model.AutoReplyRule
import com.replypilot.domain.model.ConditionCombinator
import com.replypilot.domain.model.IncomingMessage
import com.replypilot.domain.model.QuietHoursAutoReply
import com.replypilot.domain.model.ReplyMode
import com.replypilot.domain.scheduling.TimeWindows
import com.replypilot.domain.templates.TemplateRenderer

/**
 * The single place where incoming messages are matched against rules.
 *
 * Evaluation order is fixed and visible in the result:
 *  1. Global safeguards (pause, own message, duplicates, groups, blocks, opt-out, takeover, loops,
 *     quiet hours, daily limits). Rules are still traced so the user can see what *would* have matched.
 *  2. Enabled rules for the channel, highest priority first (ties broken by name).
 *  3. For each matching rule: cooldown, rule limit, rendering, channel capability.
 *  4. A matching rule with "stop processing" ends evaluation, whether or not it was suppressed.
 */
object RuleEngine {
    const val MAX_REPLIES_PER_MESSAGE = 3

    fun order(rules: List<AutoReplyRule>): List<AutoReplyRule> =
        rules.sortedWith(compareByDescending<AutoReplyRule> { it.priority }.thenBy { it.name.lowercase() }.thenBy { it.id })

    fun evaluate(message: IncomingMessage, rules: List<AutoReplyRule>, context: RuleContext): Evaluation {
        val globalChecks = globalChecks(message, context)
        val globalSuppression = globalChecks.firstOrNull { !it.second.passed }?.let { (reason, trace) ->
            Suppression(reason, trace.detail)
        }
        val traces = mutableListOf<RuleTrace>()
        val replies = mutableListOf<PlannedReply>()
        for (rule in order(rules.filter { it.enabled })) {
            val trace = traceRule(rule, message, context)
            traces += trace
            if (trace.matched && trace.suppression == null && trace.response != null && globalSuppression == null &&
                replies.size < MAX_REPLIES_PER_MESSAGE
            ) {
                replies += PlannedReply(rule, trace.response, rule.replyMode, rule.delaySeconds)
            }
            if (trace.matched && rule.stopProcessing) break
        }
        return Evaluation(
            message = message,
            globalChecks = globalChecks.map { it.second },
            globalSuppression = globalSuppression,
            traces = traces,
            replies = replies,
            optOutDetected = isOptOut(message.text, context.settings.optOutKeywords) && !message.isFromSelf,
            contact = context.contact,
        )
    }

    fun isOptOut(text: String, keywords: List<String>): Boolean {
        val normalized = Fingerprints.normalizeText(text).trim('.', '!', ' ')
        return keywords.any { Fingerprints.normalizeText(it) == normalized }
    }

    private fun globalChecks(message: IncomingMessage, ctx: RuleContext): List<Pair<SuppressionReason, CheckTrace>> {
        val s = ctx.settings
        val conversationReplies = ctx.recentReplies.filter {
            it.channel == message.channel && it.conversationKey == message.conversationKey
        }
        val textHash = Fingerprints.of(Fingerprints.normalizeText(message.text))
        val echoesOwnReply = conversationReplies.any { it.textHash == textHash && it.at >= ctx.now - s.loopWindowMinutes * MINUTE_MS }
        val loopCount = conversationReplies.count { it.at >= ctx.now - s.loopWindowMinutes * MINUTE_MS }
        val startOfDay = TimeWindows.startOfDay(ctx.now, ctx.zone)
        val repliesToContactToday = conversationReplies.count { it.at >= startOfDay }
        val senderKeys = listOfNotNull(message.senderName, message.senderAddress, message.conversationKey).map { it.trim().lowercase() }
        val blockedSender = s.blockedSenders.firstOrNull { it.trim().lowercase() in senderKeys }
        val blockedPhrase = s.blockedPhrases.firstOrNull { it.isNotBlank() && message.text.contains(it, ignoreCase = true) }
        val inQuietHours = s.quietHoursEnabled && TimeWindows.contains(s.quietHours, ctx.now, ctx.zone)
        val channelLimit = s.perChannelDailyLimit[message.channel]

        fun check(reason: SuppressionReason, label: String, passed: Boolean, detail: String) =
            reason to CheckTrace(label, passed, detail)

        return listOf(
            check(SuppressionReason.AUTOMATION_PAUSED, "Automation is running", !s.automationPaused,
                if (s.automationPaused) "Global pause is on" else "Automation is on"),
            check(SuppressionReason.OWN_MESSAGE, "Message is from someone else", !message.isFromSelf && !echoesOwnReply,
                when {
                    message.isFromSelf -> "The channel marked this message as yours"
                    echoesOwnReply -> "Text matches a reply ReplyPilot just sent here"
                    else -> "Sent by ${message.senderName}"
                }),
            check(SuppressionReason.DUPLICATE_EVENT, "Message not seen before", !ctx.isDuplicate,
                if (ctx.isDuplicate) "Identical message in this chat within ${s.duplicateWindowMinutes} min" else "First time seen"),
            check(SuppressionReason.GROUPS_DISABLED, "Group replies allowed", !message.isGroup || s.replyToGroups,
                if (message.isGroup) (if (s.replyToGroups) "Group replies are on" else "Group replies are off in Settings") else "Individual chat"),
            check(SuppressionReason.BLOCKED_SENDER, "Sender not blocked", blockedSender == null,
                blockedSender?.let { "\"$it\" is blocked" } ?: "Not on the blocked list"),
            check(SuppressionReason.OPT_OUT_REQUEST, "Not an opt-out request", !isOptOut(message.text, s.optOutKeywords),
                if (isOptOut(message.text, s.optOutKeywords)) "Message is an opt-out keyword; the sender will be opted out" else "No opt-out keyword"),
            check(SuppressionReason.OPTED_OUT, "Contact has not opted out", ctx.contact?.optedOut != true,
                if (ctx.contact?.optedOut == true) "${ctx.contact.displayName} opted out" else "No opt-out on record"),
            check(SuppressionReason.BLOCKED_PHRASE, "No blocked phrases", blockedPhrase == null,
                blockedPhrase?.let { "Contains \"$it\"" } ?: "None found"),
            check(SuppressionReason.HUMAN_TAKEOVER, "No human takeover", !ctx.takeoverActive,
                if (ctx.takeoverActive) "You took over this conversation" else "Automated"),
            check(SuppressionReason.LOOP_DETECTED, "No reply loop", loopCount < s.loopThreshold,
                "$loopCount auto-replies here in the last ${s.loopWindowMinutes} min (limit ${s.loopThreshold})"),
            check(SuppressionReason.QUIET_HOURS, "Outside quiet hours",
                !(inQuietHours && s.quietHoursAutoReply == QuietHoursAutoReply.SUPPRESS),
                if (inQuietHours) "Quiet hours ${s.quietHours.start}–${s.quietHours.end} (${s.quietHoursAutoReply.label.lowercase()})" else "Not quiet hours"),
            check(SuppressionReason.GLOBAL_DAILY_LIMIT, "Under daily message limit", ctx.sentToday < s.maxMessagesPerDay,
                "${ctx.sentToday} of ${s.maxMessagesPerDay} sent today"),
            check(SuppressionReason.CHANNEL_DAILY_LIMIT, "Under channel limit", channelLimit == null || ctx.sentTodayOnChannel < channelLimit,
                channelLimit?.let { "${ctx.sentTodayOnChannel} of $it on ${message.channel.displayName} today" } ?: "No channel limit"),
            check(SuppressionReason.CONTACT_DAILY_LIMIT, "Under per-contact reply limit", repliesToContactToday < s.maxRepliesPerContactPerDay,
                "$repliesToContactToday of ${s.maxRepliesPerContactPerDay} replies to this chat today"),
        )
    }

    private fun traceRule(rule: AutoReplyRule, message: IncomingMessage, ctx: RuleContext): RuleTrace {
        val channelOk = rule.channels.isEmpty() || message.channel in rule.channels
        val channelCheck = CheckTrace(
            if (rule.channels.isEmpty()) "Any channel" else "Channel is ${rule.channels.joinToString(" or ") { it.displayName }}",
            channelOk,
            "Message arrived on ${message.channel.displayName}",
        )
        if (!channelOk) return RuleTrace(rule, listOf(channelCheck), matched = false)

        val conditionChecks = rule.conditions.map { ConditionEvaluator.evaluate(it, message, ctx) }
        val conditionsMatch = when {
            conditionChecks.isEmpty() -> true
            rule.combinator == ConditionCombinator.ALL -> conditionChecks.all { it.passed }
            else -> conditionChecks.any { it.passed }
        }
        val checks = listOf(channelCheck) + conditionChecks
        if (!conditionsMatch) return RuleTrace(rule, checks, matched = false)

        val rendered = TemplateRenderer.render(responseBody(rule, ctx), variables(message, ctx))
        val suppression = ruleSuppression(rule, message, ctx, rendered)
        return RuleTrace(rule, checks, matched = true, suppression = suppression, response = rendered.text)
    }

    private fun ruleSuppression(
        rule: AutoReplyRule,
        message: IncomingMessage,
        ctx: RuleContext,
        rendered: TemplateRenderer.Result,
    ): Suppression? {
        val byRuleHere = ctx.recentReplies.filter {
            it.ruleId == rule.id && it.channel == message.channel && it.conversationKey == message.conversationKey
        }
        val last = byRuleHere.maxOfOrNull { it.at }
        val cooldownMs = rule.cooldownMinutes * MINUTE_MS
        val ruleToday = ctx.recentReplies.count { it.ruleId == rule.id && it.at >= ctx.now - DAY_MS }
        return when {
            last != null && cooldownMs > 0 && ctx.now - last < cooldownMs -> {
                val minutesLeft = ((cooldownMs - (ctx.now - last)) / MINUTE_MS).coerceAtLeast(1)
                Suppression(SuppressionReason.COOLDOWN, "Replied here ${(ctx.now - last) / MINUTE_MS} min ago; $minutesLeft min of cooldown left")
            }
            rule.maxRepliesPerDay != null && ruleToday >= rule.maxRepliesPerDay ->
                Suppression(SuppressionReason.RULE_DAILY_LIMIT, "$ruleToday of ${rule.maxRepliesPerDay} replies in the last 24 hours")
            !rendered.isComplete ->
                Suppression(SuppressionReason.UNRESOLVED_VARIABLES, "No value for ${rendered.unresolved.joinToString { "{{$it}}" }}")
            rule.replyMode == ReplyMode.RULE_AUTOMATIC && !ctx.channelCanAutoReply ->
                Suppression(SuppressionReason.CHANNEL_CANNOT_REPLY, ctx.channelReplyDetail.ifBlank { "${message.channel.displayName} can't send automatic replies" })
            else -> null
        }
    }

    private fun responseBody(rule: AutoReplyRule, ctx: RuleContext): String =
        rule.templateId?.let { ctx.templates[it]?.body } ?: rule.response

    fun variables(message: IncomingMessage, ctx: RuleContext): Map<String, String> = buildMap {
        putAll(ctx.globalVariables)
        put("senderName", message.senderName)
        if (ctx.contact == null) {
            message.senderName.split(" ").firstOrNull()?.takeIf { it.isNotBlank() }?.let { put("firstName", it) }
        }
        ctx.contact?.let { putAll(it.templateVariables()) }
    }
}
