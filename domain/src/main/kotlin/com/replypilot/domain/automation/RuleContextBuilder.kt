package com.replypilot.domain.automation

import com.replypilot.domain.common.Clock
import com.replypilot.domain.common.DAY_MS
import com.replypilot.domain.common.Fingerprints
import com.replypilot.domain.common.MINUTE_MS
import com.replypilot.domain.messaging.AdapterRegistry
import com.replypilot.domain.model.AvailabilityState
import com.replypilot.domain.model.ChannelCapability
import com.replypilot.domain.model.Contact
import com.replypilot.domain.model.IncomingMessage
import com.replypilot.domain.repository.ContactRepository
import com.replypilot.domain.repository.ConversationRepository
import com.replypilot.domain.repository.ExecutionRepository
import com.replypilot.domain.repository.SettingsRepository
import com.replypilot.domain.repository.TemplateRepository
import com.replypilot.domain.rules.RuleContext
import com.replypilot.domain.scheduling.TimeWindows
import com.replypilot.domain.scheduling.VariableResolver

/** Gathers live state from repositories into a [RuleContext] snapshot for the pure rule engine. */
class RuleContextBuilder(
    private val contacts: ContactRepository,
    private val conversations: ConversationRepository,
    private val executions: ExecutionRepository,
    private val templates: TemplateRepository,
    private val settings: SettingsRepository,
    private val adapters: AdapterRegistry,
    private val clock: Clock,
) {
    suspend fun build(message: IncomingMessage): RuleContext {
        val now = clock.now()
        val zone = clock.zone()
        val current = settings.current()
        val contact = resolveContact(message)
        val adapter = adapters.get(message.channel)
        val status = adapter?.status()
        val canReply = adapter != null && adapter.descriptor.supports(ChannelCapability.AUTO_REPLY) &&
            status?.availability?.isUsable == true && status.availability != AvailabilityState.MANUAL_ONLY
        val takeover = conversations.state(message.channel, message.conversationKey)?.humanTakeoverUntil
        val dayStart = TimeWindows.startOfDay(now, zone)
        return RuleContext(
            now = now,
            zone = zone,
            settings = current,
            contact = contact,
            recentReplies = conversations.repliesSince(now - DAY_MS),
            isDuplicate = conversations.hasFingerprint(fingerprint(message), now - current.duplicateWindowMinutes * MINUTE_MS),
            takeoverActive = takeover != null && takeover > now,
            channelCanAutoReply = canReply,
            channelReplyDetail = when {
                adapter == null -> "${message.channel.displayName} has no integration"
                !adapter.descriptor.supports(ChannelCapability.AUTO_REPLY) -> "${message.channel.displayName} can't send automatic replies"
                else -> status?.detail.orEmpty()
            },
            sentToday = executions.countSubmittedSince(dayStart),
            sentTodayOnChannel = executions.countSubmittedSince(dayStart, message.channel),
            templates = templates.all().associateBy { it.id },
            globalVariables = VariableResolver.global(current),
        )
    }

    /** Matches by channel address first, then by exact display name (notifications often only carry a name). */
    suspend fun resolveContact(message: IncomingMessage): Contact? =
        message.senderAddress?.let { contacts.findByAddress(message.channel, it) }
            ?: contacts.findByAddress(message.channel, message.conversationKey)
            ?: if (!message.isGroup) contacts.findByName(message.senderName) else null

    companion object {
        fun fingerprint(message: IncomingMessage): String =
            Fingerprints.of(message.channel.name, message.conversationKey, message.senderName, Fingerprints.normalizeText(message.text))
    }
}
