package com.replypilot.domain.automation

import com.replypilot.domain.assistant.MessageAssistant
import com.replypilot.domain.common.Clock
import com.replypilot.domain.common.Fingerprints
import com.replypilot.domain.common.IdGenerator
import com.replypilot.domain.common.MINUTE_MS
import com.replypilot.domain.messaging.IncomingMessageSink
import com.replypilot.domain.model.ActivityResult
import com.replypilot.domain.model.ActivityType
import com.replypilot.domain.model.ChannelIdentity
import com.replypilot.domain.model.Contact
import com.replypilot.domain.model.ConversationState
import com.replypilot.domain.model.ExecutionItem
import com.replypilot.domain.model.ExecutionSource
import com.replypilot.domain.model.IncomingMessage
import com.replypilot.domain.model.MessageStatus
import com.replypilot.domain.model.QuietHoursBehavior
import com.replypilot.domain.model.Recipient
import com.replypilot.domain.model.ReplyMode
import com.replypilot.domain.model.ReplyRecord
import com.replypilot.domain.repository.ContactRepository
import com.replypilot.domain.repository.ConversationRepository
import com.replypilot.domain.repository.ExecutionRepository
import com.replypilot.domain.repository.RuleRepository
import com.replypilot.domain.repository.SettingsRepository
import com.replypilot.domain.repository.WakeScheduler
import com.replypilot.domain.rules.Evaluation
import com.replypilot.domain.rules.PlannedReply
import com.replypilot.domain.rules.RuleEngine
import com.replypilot.domain.rules.SuppressionReason

/**
 * The pipeline for a real incoming message: context → rule engine → safeguards → queued reply → history.
 * Replies are never sent from here directly; they go through the same queue as scheduled messages.
 */
class IncomingMessageHandler(
    private val rules: RuleRepository,
    private val contacts: ContactRepository,
    private val conversations: ConversationRepository,
    private val executions: ExecutionRepository,
    private val settings: SettingsRepository,
    private val contextBuilder: RuleContextBuilder,
    private val sequences: SequenceService,
    private val assistant: MessageAssistant,
    private val activity: ActivityLogger,
    private val clock: Clock,
    private val ids: IdGenerator,
    private val wake: WakeScheduler,
) : IncomingMessageSink {

    override suspend fun onIncoming(message: IncomingMessage) {
        handle(message)
    }

    suspend fun handle(message: IncomingMessage): Evaluation {
        val context = contextBuilder.build(message)
        val evaluation = RuleEngine.evaluate(message, rules.all(), context)
        val now = clock.now()
        conversations.recordFingerprint(RuleContextBuilder.fingerprint(message), now)

        if (message.isFromSelf) startTakeoverIfManual(message)
        val reactsToSender = !message.isFromSelf && evaluation.globalSuppression?.reason !in IGNORED_FOR_SEQUENCES
        if (evaluation.optOutDetected) recordOptOut(message, evaluation.contact)
        else if (reactsToSender) sequences.onInbound(message, evaluation.contact)

        logEvaluation(message, evaluation)
        val queuedAt = evaluation.replies.mapNotNull { queueReply(message, it, evaluation.contact?.id) }
        if (queuedAt.isNotEmpty()) wake.scheduleWake(queuedAt.min())
        return evaluation
    }

    /**
     * A message the device owner typed themselves means a human is handling the conversation, so automatic
     * replies pause there for the configured window. Echoes of ReplyPilot's own replies don't count.
     */
    private suspend fun startTakeoverIfManual(message: IncomingMessage) {
        val now = clock.now()
        val current = settings.current()
        val hash = Fingerprints.of(Fingerprints.normalizeText(message.text))
        val isOwnReply = conversations.repliesSince(now - current.loopWindowMinutes * MINUTE_MS).any {
            it.channel == message.channel && it.conversationKey == message.conversationKey && it.textHash == hash
        }
        if (isOwnReply) return
        conversations.setState(ConversationState(message.channel, message.conversationKey, now + current.humanTakeoverMinutes * MINUTE_MS))
        activity.log(
            ActivityType.HUMAN_TAKEOVER_STARTED, ActivityResult.INFO,
            "You replied yourself; auto-replies paused here for ${current.humanTakeoverMinutes} min", message.simulated,
        ) { it.copy(channel = message.channel, recipientRef = message.conversationKey) }
    }

    private suspend fun recordOptOut(message: IncomingMessage, contact: Contact?) {
        val now = clock.now()
        val updated = contact?.copy(optedOut = true, optedOutAt = now, updatedAt = now) ?: Contact(
            id = ids.next(),
            firstName = message.senderName,
            identities = listOf(ChannelIdentity(message.channel, message.senderAddress ?: message.conversationKey)),
            optedOut = true,
            optedOutAt = now,
            notes = "Created automatically when this sender opted out.",
            createdAt = now,
            updatedAt = now,
        )
        contacts.upsert(updated)
        sequences.onOptOut(message, updated)
        activity.log(ActivityType.OPT_OUT_RECORDED, ActivityResult.INFO, "Sender sent an opt-out keyword", message.simulated) {
            it.copy(channel = message.channel, recipientRef = message.senderName)
        }
    }

    private suspend fun logEvaluation(message: IncomingMessage, evaluation: Evaluation) {
        val first = evaluation.firstMatch
        val global = evaluation.globalSuppression
        when {
            first != null -> {
                activity.log(ActivityType.RULE_MATCHED, ActivityResult.INFO, first.checks.filter { c -> c.passed }.joinToString("; ") { c -> c.label }, message.simulated) {
                    it.copy(channel = message.channel, ruleId = first.rule.id, sourceId = first.rule.id, recipientRef = message.senderName)
                }
                val suppression = global ?: first.suppression
                if (suppression != null) {
                    activity.log(ActivityType.AUTO_REPLY_SUPPRESSED, ActivityResult.SUPPRESSED, "${suppression.reason.label}: ${suppression.detail}", message.simulated) {
                        it.copy(channel = message.channel, ruleId = first.rule.id, sourceId = first.rule.id, recipientRef = message.senderName)
                    }
                }
            }
            global != null -> activity.log(ActivityType.AUTO_REPLY_SUPPRESSED, ActivityResult.SUPPRESSED, "${global.reason.label}: ${global.detail}", message.simulated) {
                it.copy(channel = message.channel, recipientRef = message.senderName)
            }
            else -> activity.log(ActivityType.NO_RULE_MATCHED, ActivityResult.INFO, null, message.simulated) {
                it.copy(channel = message.channel, recipientRef = message.senderName)
            }
        }
    }

    /** Returns the time the reply should run, or null if it was not queued for sending. */
    private suspend fun queueReply(message: IncomingMessage, reply: PlannedReply, contactId: String?): Long? {
        val now = clock.now()
        val current = settings.current()
        var text = reply.text
        var status = if (reply.mode == ReplyMode.RULE_AUTOMATIC) MessageStatus.SCHEDULED else MessageStatus.AWAITING_REVIEW
        var reason: String? = if (reply.mode == ReplyMode.MANUAL_APPROVAL) "Waiting for your approval" else null

        if (reply.mode == ReplyMode.AI_ASSISTED) {
            if (!assistant.isAvailable()) {
                logSuppressed(message, reply, SuppressionReason.AI_NOT_CONFIGURED.label)
                return null
            }
            val draft = assistant.draftReply(message.text, reply.text).getOrElse {
                logSuppressed(message, reply, "AI draft failed: ${it.message}")
                return null
            }
            text = draft.text
            val autoSend = current.aiAutoSend && !draft.isMock && draft.confidence >= current.aiMinConfidence
            status = if (autoSend) MessageStatus.SCHEDULED else MessageStatus.AWAITING_REVIEW
            reason = "AI draft by ${draft.providerName}" + if (autoSend) " (auto-sent above confidence threshold)" else "; waiting for your approval"
        }

        val runAt = now + reply.delaySeconds * 1_000L
        val item = ExecutionItem(
            id = ids.next(),
            idempotencyKey = ExecutionItem.key(ExecutionSource.AUTO_REPLY, "${reply.rule.id}:${message.id}", message.conversationKey, 0),
            source = ExecutionSource.AUTO_REPLY,
            sourceId = reply.rule.id,
            occurrenceIndex = 0,
            channel = message.channel,
            recipient = Recipient(message.senderAddress ?: message.conversationKey, message.senderName, contactId),
            body = text,
            plannedAt = runAt,
            expiresAt = runAt + REPLY_EXPIRY_MS,
            status = status,
            maxAttempts = 2,
            nextAttemptAt = runAt,
            replyTarget = message.replyTarget,
            quietHours = QuietHoursBehavior.SEND_ANYWAY,
            statusReason = reason,
            simulated = message.simulated,
            createdAt = now,
            updatedAt = now,
        )
        if (executions.insertIfAbsent(listOf(item)) == 0) return null
        // Recorded at queue time so a second notification arriving before the send still sees the cooldown.
        conversations.recordReply(ReplyRecord(reply.rule.id, message.channel, message.conversationKey, Fingerprints.of(Fingerprints.normalizeText(text)), now))
        if (status == MessageStatus.AWAITING_REVIEW) {
            activity.forExecution(item, ActivityType.REPLY_AWAITING_APPROVAL, ActivityResult.PENDING, reason)
            return null
        }
        return runAt
    }

    private suspend fun logSuppressed(message: IncomingMessage, reply: PlannedReply, reason: String) {
        activity.log(ActivityType.AUTO_REPLY_SUPPRESSED, ActivityResult.SUPPRESSED, reason, message.simulated) {
            it.copy(channel = message.channel, ruleId = reply.rule.id, sourceId = reply.rule.id, recipientRef = message.senderName)
        }
    }

    companion object {
        const val REPLY_EXPIRY_MS = 60 * MINUTE_MS
        private val IGNORED_FOR_SEQUENCES = setOf(SuppressionReason.OWN_MESSAGE, SuppressionReason.DUPLICATE_EVENT)
    }
}
