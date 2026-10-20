package com.replypilot.domain.scheduling

import com.replypilot.domain.common.Clock
import com.replypilot.domain.common.DAY_MS
import com.replypilot.domain.common.IdGenerator
import com.replypilot.domain.common.MINUTE_MS
import com.replypilot.domain.model.ChannelId
import com.replypilot.domain.model.ExecutionItem
import com.replypilot.domain.model.ExecutionSource
import com.replypilot.domain.model.FailureCode
import com.replypilot.domain.model.MessageStatus
import com.replypilot.domain.model.ScheduleState
import com.replypilot.domain.model.ScheduledMessage
import com.replypilot.domain.repository.ContactRepository
import com.replypilot.domain.repository.ExecutionRepository
import com.replypilot.domain.repository.ScheduleRepository
import com.replypilot.domain.repository.SettingsRepository
import com.replypilot.domain.templates.TemplateRenderer

/**
 * Turns schedule definitions into concrete execution rows for a rolling horizon.
 * Rows are keyed by (schedule revision, recipient, occurrence index), so running this any number
 * of times — after a restart, from two workers — never produces a second row for the same send.
 */
class ScheduleMaterializer(
    private val schedules: ScheduleRepository,
    private val executions: ExecutionRepository,
    private val contacts: ContactRepository,
    private val settings: SettingsRepository,
    private val clock: Clock,
    private val ids: IdGenerator,
    private val horizonMs: Long = DEFAULT_HORIZON_MS,
) {
    suspend fun materializeAll(): Int =
        schedules.all().filter { it.state == ScheduleState.ACTIVE }.sumOf { materialize(it) }

    suspend fun materialize(schedule: ScheduledMessage): Int {
        if (schedule.state != ScheduleState.ACTIVE) return 0
        val now = clock.now()
        val zone = clock.zone()
        val until = now + horizonMs
        if (schedule.materializedUntil >= until) return 0

        val occurrences = RecurrenceCalculator.occurrencesBetween(schedule.spec, zone, schedule.materializedUntil, until)
        val currentSettings = settings.current()
        val items = occurrences.flatMap { occurrence ->
            schedule.recipients.map { recipient ->
                val contact = recipient.contactId?.let { contacts.get(it) }
                val rendered = TemplateRenderer.render(
                    schedule.body,
                    VariableResolver.forRecipient(recipient, contact, currentSettings),
                )
                ExecutionItem(
                    id = ids.next(),
                    idempotencyKey = ExecutionItem.key(
                        ExecutionSource.SCHEDULED, "${schedule.id}#r${schedule.revision}", recipient.address, occurrence.index,
                    ),
                    source = ExecutionSource.SCHEDULED,
                    sourceId = schedule.id,
                    occurrenceIndex = occurrence.index,
                    channel = schedule.channel,
                    recipient = recipient,
                    body = rendered.text,
                    attachments = schedule.attachments,
                    plannedAt = occurrence.at,
                    expiresAt = occurrence.at + schedule.policy.expireAfterMinutes * MINUTE_MS,
                    status = if (rendered.isComplete) MessageStatus.SCHEDULED else MessageStatus.NEEDS_ATTENTION,
                    failureCode = if (rendered.isComplete) null else FailureCode.UNRESOLVED_VARIABLES,
                    statusReason = if (rendered.isComplete) null else "No value for ${rendered.unresolved.joinToString { "{{$it}}" }}",
                    maxAttempts = 1 + schedule.policy.maxRetries.coerceIn(0, MAX_RETRIES),
                    nextAttemptAt = occurrence.at,
                    quietHours = schedule.policy.quietHours,
                    simulated = schedule.isDemo || schedule.channel == ChannelId.DEMO,
                    createdAt = now,
                    updatedAt = now,
                )
            }
        }
        val inserted = executions.insertIfAbsent(items)
        val hasMore = RecurrenceCalculator.next(schedule.spec, zone, until) != null
        // Re-read: a pause, cancel or edit may have landed while rows were built. Rows inserted for a
        // superseded schedule are harmless — the queue re-checks the schedule state before sending.
        val latest = schedules.get(schedule.id)
        if (latest == null || latest.state != ScheduleState.ACTIVE || latest.revision != schedule.revision) return inserted
        schedules.upsert(
            latest.copy(
                materializedUntil = until,
                state = if (hasMore) ScheduleState.ACTIVE else ScheduleState.COMPLETED,
                updatedAt = now,
            ),
        )
        return inserted
    }

    companion object {
        const val DEFAULT_HORIZON_MS: Long = 2 * DAY_MS
        const val MAX_RETRIES = 10
    }
}
