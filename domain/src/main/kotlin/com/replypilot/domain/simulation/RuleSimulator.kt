package com.replypilot.domain.simulation

import com.replypilot.domain.common.Fingerprints
import com.replypilot.domain.common.MINUTE_MS
import com.replypilot.domain.model.AutoReplyRule
import com.replypilot.domain.model.AutomationSettings
import com.replypilot.domain.model.ChannelId
import com.replypilot.domain.model.Contact
import com.replypilot.domain.model.IncomingMessage
import com.replypilot.domain.model.MessageTemplate
import com.replypilot.domain.model.ReplyRecord
import com.replypilot.domain.rules.Evaluation
import com.replypilot.domain.rules.RuleContext
import com.replypilot.domain.rules.RuleEngine
import com.replypilot.domain.scheduling.VariableResolver
import java.time.ZoneId

/**
 * A hypothetical situation to test rules against. Every input that affects the outcome is part of the
 * scenario, so the same scenario always produces the same evaluation.
 */
data class SimulationScenario(
    val text: String,
    val channel: ChannelId = ChannelId.DEMO,
    val senderName: String = "Test sender",
    val contactId: String? = null,
    val isGroup: Boolean = false,
    val at: Long,
    val zone: ZoneId,
    /** When set, pretend ReplyPilot already replied in this chat this many minutes ago (with every rule, unless [lastReplyRuleId] is set). */
    val minutesSinceLastReply: Int? = null,
    val lastReplyRuleId: String? = null,
    /** Auto-replies already sent in this chat inside the loop window. */
    val recentAutoReplies: Int = 0,
    val takeoverActive: Boolean = false,
    val contactOptedOut: Boolean = false,
    val duplicate: Boolean = false,
    val fromSelf: Boolean = false,
    val channelCanAutoReply: Boolean = true,
    val sentToday: Int = 0,
)

/** Runs the production rule engine on a scenario. It has no access to adapters, so it can never send. */
object RuleSimulator {
    fun simulate(
        scenario: SimulationScenario,
        rules: List<AutoReplyRule>,
        contacts: List<Contact>,
        settings: AutomationSettings,
        templates: List<MessageTemplate> = emptyList(),
    ): Evaluation {
        val conversationKey = scenario.senderName
        val message = IncomingMessage(
            id = "simulated",
            channel = scenario.channel,
            conversationKey = conversationKey,
            senderName = scenario.senderName,
            text = scenario.text,
            isGroup = scenario.isGroup,
            receivedAt = scenario.at,
            isFromSelf = scenario.fromSelf,
            simulated = true,
        )
        val contact = contacts.firstOrNull { it.id == scenario.contactId }
            ?.let { if (scenario.contactOptedOut) it.copy(optedOut = true) else it }
        val context = RuleContext(
            now = scenario.at,
            zone = scenario.zone,
            settings = settings,
            contact = contact,
            recentReplies = history(scenario, rules, conversationKey),
            isDuplicate = scenario.duplicate,
            takeoverActive = scenario.takeoverActive,
            channelCanAutoReply = scenario.channelCanAutoReply,
            channelReplyDetail = if (scenario.channelCanAutoReply) "" else "Scenario: channel can't reply automatically",
            sentToday = scenario.sentToday,
            sentTodayOnChannel = scenario.sentToday,
            templates = templates.associateBy { it.id },
            globalVariables = VariableResolver.global(settings),
        )
        return RuleEngine.evaluate(message, rules, context)
    }

    private fun history(scenario: SimulationScenario, rules: List<AutoReplyRule>, conversationKey: String): List<ReplyRecord> = buildList {
        scenario.minutesSinceLastReply?.let { minutes ->
            val at = scenario.at - minutes * MINUTE_MS
            val ruleIds = scenario.lastReplyRuleId?.let(::listOf) ?: rules.map { it.id }
            ruleIds.forEach { add(ReplyRecord(it, scenario.channel, conversationKey, Fingerprints.of("previous reply"), at)) }
        }
        repeat(scenario.recentAutoReplies) { i ->
            add(ReplyRecord("loop-$i", scenario.channel, conversationKey, Fingerprints.of("loop reply $i"), scenario.at - (i + 1) * MINUTE_MS))
        }
    }
}
