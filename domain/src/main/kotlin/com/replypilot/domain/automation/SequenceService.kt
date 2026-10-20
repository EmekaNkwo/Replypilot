package com.replypilot.domain.automation

import com.replypilot.domain.common.Clock
import com.replypilot.domain.common.IdGenerator
import com.replypilot.domain.common.MINUTE_MS
import com.replypilot.domain.model.ActivityResult
import com.replypilot.domain.model.ActivityType
import com.replypilot.domain.model.ChannelId
import com.replypilot.domain.model.Contact
import com.replypilot.domain.model.EnrollmentStatus
import com.replypilot.domain.model.ExecutionItem
import com.replypilot.domain.model.ExecutionSource
import com.replypilot.domain.model.IncomingMessage
import com.replypilot.domain.model.MessageSequence
import com.replypilot.domain.model.MessageStatus
import com.replypilot.domain.model.Recipient
import com.replypilot.domain.model.SequenceEnrollment
import com.replypilot.domain.repository.ContactRepository
import com.replypilot.domain.repository.ExecutionRepository
import com.replypilot.domain.repository.SequenceRepository
import com.replypilot.domain.repository.SettingsRepository
import com.replypilot.domain.repository.WakeScheduler
import com.replypilot.domain.scheduling.AddressRules
import com.replypilot.domain.scheduling.RecurrenceCalculator
import com.replypilot.domain.scheduling.ScheduleService
import com.replypilot.domain.scheduling.TimeWindows
import com.replypilot.domain.scheduling.VariableResolver
import com.replypilot.domain.templates.TemplateRenderer
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

data class EnrollResult(val enrolled: List<SequenceEnrollment>, val skipped: List<Pair<Recipient, String>>)

/**
 * Multi-step follow-ups. Each step of each enrollment is its own execution row, so stopping an
 * enrollment simply cancels the rows that haven't run, and the queue re-checks the enrollment before sending.
 */
class SequenceService(
    private val sequences: SequenceRepository,
    private val executions: ExecutionRepository,
    private val contacts: ContactRepository,
    private val settings: SettingsRepository,
    private val activity: ActivityLogger,
    private val clock: Clock,
    private val ids: IdGenerator,
    private val wake: WakeScheduler,
) {
    fun validate(sequence: MessageSequence): List<String> = buildList {
        if (sequence.name.isBlank()) add("Give the sequence a name.")
        if (sequence.steps.isEmpty()) add("Add at least one step.")
        if (sequence.steps.size > MessageSequence.MAX_STEPS) add("A sequence can have at most ${MessageSequence.MAX_STEPS} steps.")
        if (sequence.steps.any { it.body.isBlank() }) add("Every step needs a message.")
        if (sequence.steps.any { it.dayOffset < 0 }) add("Step days can't be negative.")
        if (sequence.steps.zipWithNext().any { (a, b) -> b.dayOffset < a.dayOffset }) add("Steps must be in day order.")
        if (sequence.steps.any { TimeWindows.parseTime(it.time) == null }) add("Every step needs a valid time.")
        if (runCatching { ZoneId.of(sequence.timeZone) }.isFailure) add("Unknown time zone.")
    }

    /** Explicit, user-initiated enrollment with eligibility checks and a per-action cap. */
    suspend fun enroll(sequenceId: String, recipients: List<Recipient>): EnrollResult {
        val sequence = sequences.getSequence(sequenceId) ?: return EnrollResult(emptyList(), recipients.map { it to "Sequence not found" })
        if (!sequence.enabled) return EnrollResult(emptyList(), recipients.map { it to "Sequence is disabled" })
        val active = sequences.enrollments(EnrollmentStatus.ACTIVE).filter { it.sequenceId == sequenceId }
        val enrolled = mutableListOf<SequenceEnrollment>()
        val skipped = mutableListOf<Pair<Recipient, String>>()
        val currentSettings = settings.current()

        for (recipient in recipients) {
            val contact = recipient.contactId?.let { contacts.get(it) } ?: contacts.findByAddress(sequence.channel, recipient.address)
            val address = AddressRules.normalize(sequence.channel, recipient.address).lowercase()
            val reason = when {
                enrolled.size >= MAX_ENROLL_PER_ACTION -> "Limit of $MAX_ENROLL_PER_ACTION recipients per enrollment"
                contact?.optedOut == true -> "Opted out"
                !AddressRules.isValid(sequence.channel, recipient.address) -> "Invalid ${sequence.channel.displayName} address"
                (active + enrolled).any { AddressRules.normalize(sequence.channel, it.recipient.address).lowercase() == address } -> "Already in this sequence"
                else -> null
            }
            if (reason != null) {
                skipped += recipient to reason
                continue
            }
            val enrollment = SequenceEnrollment(
                id = ids.next(),
                sequenceId = sequence.id,
                channel = sequence.channel,
                recipient = recipient.copy(contactId = recipient.contactId ?: contact?.id),
                startedAt = clock.now(),
                updatedAt = clock.now(),
            )
            sequences.upsertEnrollment(enrollment)
            executions.insertIfAbsent(stepRows(sequence, enrollment, contact, VariableResolver.forRecipient(recipient, contact, currentSettings)))
            activity.log(ActivityType.SEQUENCE_STARTED, ActivityResult.INFO, reason = sequence.name, simulated = sequence.channel == ChannelId.DEMO) {
                it.copy(channel = sequence.channel, source = ExecutionSource.SEQUENCE, sourceId = enrollment.id, recipientRef = recipient.displayName)
            }
            enrolled += enrollment
        }
        wake.scheduleWake(clock.now())
        return EnrollResult(enrolled, skipped)
    }

    private fun stepRows(
        sequence: MessageSequence,
        enrollment: SequenceEnrollment,
        contact: Contact?,
        variables: Map<String, String>,
    ): List<ExecutionItem> {
        val zone = runCatching { ZoneId.of(contact?.timeZone ?: sequence.timeZone) }.getOrDefault(clock.zone())
        val startDate = Instant.ofEpochMilli(enrollment.startedAt).atZone(zone).toLocalDate()
        val now = clock.now()
        return sequence.steps.take(MessageSequence.MAX_STEPS).mapIndexed { index, step ->
            val time = TimeWindows.parseTime(step.time) ?: LocalTime.of(9, 0)
            val planned = RecurrenceCalculator.toEpoch(startDate.plusDays(step.dayOffset.toLong()), time, zone)
                .coerceAtLeast(now + MINUTE_MS)
            val rendered = TemplateRenderer.render(step.body, variables)
            ExecutionItem(
                id = ids.next(),
                idempotencyKey = ExecutionItem.key(ExecutionSource.SEQUENCE, enrollment.id, enrollment.recipient.address, index),
                source = ExecutionSource.SEQUENCE,
                sourceId = enrollment.id,
                occurrenceIndex = index,
                channel = sequence.channel,
                recipient = enrollment.recipient,
                body = rendered.text,
                plannedAt = planned,
                expiresAt = planned + STEP_EXPIRY_MS,
                status = when {
                    !rendered.isComplete -> MessageStatus.NEEDS_ATTENTION
                    step.requiresReview -> MessageStatus.AWAITING_REVIEW
                    else -> MessageStatus.SCHEDULED
                },
                statusReason = when {
                    !rendered.isComplete -> "No value for ${rendered.unresolved.joinToString { "{{$it}}" }}"
                    step.requiresReview -> "Step ${index + 1} needs your review before sending"
                    else -> null
                },
                maxAttempts = 3,
                nextAttemptAt = planned,
                quietHours = sequence.quietHours,
                simulated = sequence.channel == ChannelId.DEMO,
                createdAt = now,
                updatedAt = now,
            )
        }
    }

    suspend fun stop(enrollmentId: String, reason: String) {
        val enrollment = sequences.getEnrollment(enrollmentId) ?: return
        if (enrollment.status != EnrollmentStatus.ACTIVE) return
        val now = clock.now()
        sequences.upsertEnrollment(enrollment.copy(status = EnrollmentStatus.STOPPED, stopReason = reason, updatedAt = now))
        executions.bySource(ExecutionSource.SEQUENCE, enrollmentId)
            .filter { it.status in ScheduleService.CANCELLABLE }
            .forEach { item ->
                executions.replaceIfStatus(item.copy(status = MessageStatus.CANCELLED, statusReason = "Sequence stopped: $reason", updatedAt = now), item.status)
            }
        activity.log(ActivityType.SEQUENCE_STOPPED, ActivityResult.INFO, reason = reason, simulated = enrollment.channel == ChannelId.DEMO) {
            it.copy(channel = enrollment.channel, source = ExecutionSource.SEQUENCE, sourceId = enrollmentId, recipientRef = enrollment.recipient.displayName)
        }
    }

    /** A reply from the recipient stops sequences configured with "stop when they reply". */
    suspend fun onInbound(message: IncomingMessage, contact: Contact?) {
        matching(message.channel, message, contact).forEach { (enrollment, sequence) ->
            if (sequence?.stopOnReply != false) stop(enrollment.id, "Recipient replied")
        }
    }

    /** Opt-outs always stop every sequence for that person on that channel. */
    suspend fun onOptOut(message: IncomingMessage, contact: Contact?) {
        matching(message.channel, message, contact).forEach { (enrollment, _) -> stop(enrollment.id, "Recipient opted out") }
        contact?.let { stopAllFor(it) }
    }

    suspend fun stopAllFor(contact: Contact) {
        sequences.enrollments(EnrollmentStatus.ACTIVE)
            .filter { it.recipient.contactId == contact.id }
            .forEach { stop(it.id, "Contact opted out") }
    }

    private suspend fun matching(channel: ChannelId, message: IncomingMessage, contact: Contact?): List<Pair<SequenceEnrollment, MessageSequence?>> =
        sequences.enrollments(EnrollmentStatus.ACTIVE)
            .filter { it.channel == channel }
            .filter { enrollment ->
                val r = enrollment.recipient
                (contact != null && r.contactId == contact.id) ||
                    (message.senderAddress != null && AddressRules.normalize(channel, r.address) == AddressRules.normalize(channel, message.senderAddress)) ||
                    r.displayName.equals(message.conversationKey, ignoreCase = true)
            }
            .map { it to sequences.getSequence(it.sequenceId) }

    companion object {
        const val MAX_ENROLL_PER_ACTION = 25
        const val STEP_EXPIRY_MS = 12 * 60 * MINUTE_MS
    }
}
