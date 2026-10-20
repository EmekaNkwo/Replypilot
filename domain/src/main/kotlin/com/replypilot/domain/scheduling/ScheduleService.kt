package com.replypilot.domain.scheduling

import com.replypilot.domain.automation.ActivityLogger
import com.replypilot.domain.common.Clock
import com.replypilot.domain.common.IdGenerator
import com.replypilot.domain.common.MINUTE_MS
import com.replypilot.domain.messaging.AdapterRegistry
import com.replypilot.domain.model.ActivityResult
import com.replypilot.domain.model.ActivityType
import com.replypilot.domain.model.ExecutionSource
import com.replypilot.domain.model.MessageStatus
import com.replypilot.domain.model.ScheduleState
import com.replypilot.domain.model.ScheduledMessage
import com.replypilot.domain.model.ZoneMode
import com.replypilot.domain.repository.ContactRepository
import com.replypilot.domain.repository.ExecutionRepository
import com.replypilot.domain.repository.ScheduleRepository
import com.replypilot.domain.repository.SettingsRepository
import com.replypilot.domain.repository.WakeScheduler

sealed interface SaveResult {
    data class Saved(val schedule: ScheduledMessage, val warnings: List<ValidationIssue>, val queued: Int) : SaveResult
    data class Invalid(val issues: List<ValidationIssue>) : SaveResult
}

/** Use cases for creating and managing scheduled messages. */
class ScheduleService(
    private val schedules: ScheduleRepository,
    private val executions: ExecutionRepository,
    private val contacts: ContactRepository,
    private val settings: SettingsRepository,
    private val adapters: AdapterRegistry,
    private val materializer: ScheduleMaterializer,
    private val activity: ActivityLogger,
    private val clock: Clock,
    private val ids: IdGenerator,
    private val wake: WakeScheduler,
    private val validator: ScheduleValidator = ScheduleValidator(),
) {
    suspend fun validate(draft: ScheduledMessage, sendNow: Boolean = false): List<ValidationIssue> {
        val now = effectiveNow(sendNow)
        val currentSettings = settings.current()
        val contactsById = contacts.all().associateBy { it.id }
        val status = adapters.get(draft.channel)?.status()
        return validator.validate(
            draft = draft,
            channel = status,
            variablesFor = { VariableResolver.forRecipient(it, it.contactId?.let(contactsById::get), currentSettings) },
            existing = schedules.all(),
            now = now,
            deviceZone = clock.zone(),
        )
    }

    /**
     * Saves a new or edited schedule. Editing bumps the revision, cancels the old revision's pending
     * executions and materializes fresh ones, so an edit can never cause both versions to send.
     */
    suspend fun save(draft: ScheduledMessage, sendNow: Boolean = false): SaveResult {
        val issues = validate(draft, sendNow)
        if (issues.hasErrors) return SaveResult.Invalid(issues)

        val now = clock.now()
        val previous = schedules.get(draft.id)
        if (previous != null) cancelPending(previous.id, "Replaced by an edit")
        val saved = draft.copy(
            id = draft.id.ifBlank { ids.next() },
            state = ScheduleState.ACTIVE,
            revision = (previous?.revision ?: 0) + 1,
            materializedUntil = effectiveNow(sendNow),
            createdAt = previous?.createdAt ?: now,
            updatedAt = now,
        )
        schedules.upsert(saved)
        val queued = materializer.materialize(saved)
        wakeFor(saved.id)
        activity.log(
            if (previous == null) ActivityType.MESSAGE_SCHEDULED else ActivityType.SCHEDULE_UPDATED,
            ActivityResult.INFO,
            reason = RecurrenceCalculator.describe(saved.spec),
            simulated = saved.isDemo,
        ) { it.copy(channel = saved.channel, source = ExecutionSource.SCHEDULED, sourceId = saved.id) }
        return SaveResult.Saved(schedules.get(saved.id) ?: saved, issues, queued)
    }

    suspend fun pause(id: String) {
        val schedule = schedules.get(id) ?: return
        schedules.upsert(schedule.copy(state = ScheduleState.PAUSED, updatedAt = clock.now()))
        executions.bySource(ExecutionSource.SCHEDULED, id)
            .filter { it.status in setOf(MessageStatus.SCHEDULED, MessageStatus.RETRY_SCHEDULED, MessageStatus.DUE) }
            .forEach { executions.replaceIfStatus(it.copy(status = MessageStatus.PAUSED, updatedAt = clock.now()), it.status) }
        activity.log(ActivityType.SCHEDULE_PAUSED, ActivityResult.INFO, simulated = schedule.isDemo) {
            it.copy(channel = schedule.channel, source = ExecutionSource.SCHEDULED, sourceId = id)
        }
    }

    suspend fun resume(id: String) {
        val schedule = schedules.get(id) ?: return
        val now = clock.now()
        executions.bySource(ExecutionSource.SCHEDULED, id)
            .filter { it.status == MessageStatus.PAUSED }
            .forEach { executions.replaceIfStatus(it.copy(status = MessageStatus.SCHEDULED, updatedAt = now), MessageStatus.PAUSED) }
        // Paused rows that are now late still honour their expiry window; new rows start from now.
        val resumed = schedule.copy(
            state = ScheduleState.ACTIVE,
            materializedUntil = maxOf(schedule.materializedUntil, now),
            updatedAt = now,
        )
        schedules.upsert(resumed)
        materializer.materialize(resumed)
        wakeFor(id)
        activity.log(ActivityType.SCHEDULE_UPDATED, ActivityResult.INFO, reason = "Resumed", simulated = schedule.isDemo) {
            it.copy(channel = schedule.channel, source = ExecutionSource.SCHEDULED, sourceId = id)
        }
    }

    suspend fun cancel(id: String) {
        val schedule = schedules.get(id) ?: return
        cancelPending(id, "Schedule cancelled")
        schedules.upsert(schedule.copy(state = ScheduleState.CANCELLED, updatedAt = clock.now()))
        activity.log(ActivityType.SCHEDULE_CANCELLED, ActivityResult.INFO, simulated = schedule.isDemo) {
            it.copy(channel = schedule.channel, source = ExecutionSource.SCHEDULED, sourceId = id)
        }
    }

    suspend fun delete(id: String) {
        cancelPending(id, "Schedule deleted")
        schedules.delete(id)
    }

    /** Returns an unsaved copy for the composer to edit. */
    suspend fun duplicate(id: String): ScheduledMessage? {
        val original = schedules.get(id) ?: return null
        return original.copy(id = "", title = "${original.title} (copy)", revision = 0, materializedUntil = 0)
    }

    /**
     * Schedules that follow the device zone are re-planned when the zone changes: pending rows computed
     * for the old zone are cancelled and regenerated. Fixed-zone schedules keep their absolute instants.
     */
    suspend fun onTimeZoneChanged(): Int {
        val now = clock.now()
        var replanned = 0
        schedules.all()
            .filter { it.spec.zoneMode == ZoneMode.FOLLOW_DEVICE && it.state in setOf(ScheduleState.ACTIVE, ScheduleState.COMPLETED) }
            .forEach { schedule ->
                cancelPending(schedule.id, "Re-planned for new time zone")
                val updated = schedule.copy(
                    state = ScheduleState.ACTIVE,
                    revision = schedule.revision + 1,
                    materializedUntil = now,
                    updatedAt = now,
                )
                schedules.upsert(updated)
                materializer.materialize(updated)
                replanned++
            }
        return replanned
    }

    private suspend fun cancelPending(scheduleId: String, reason: String) {
        val now = clock.now()
        executions.bySource(ExecutionSource.SCHEDULED, scheduleId)
            .filter { it.status in CANCELLABLE }
            .forEach { item ->
                executions.replaceIfStatus(item.copy(status = MessageStatus.CANCELLED, statusReason = reason, updatedAt = now), item.status)
            }
    }

    /**
     * Arms the queue for this schedule's earliest runnable row. Without this a "send now" (or anything due
     * soon) would wait for the periodic safety-net run instead of going out on time.
     */
    private suspend fun wakeFor(scheduleId: String) {
        executions.bySource(ExecutionSource.SCHEDULED, scheduleId)
            .filter { it.status in MessageStatus.RUNNABLE }
            .minOfOrNull { it.nextAttemptAt }
            ?.let(wake::scheduleWake)
    }

    private fun effectiveNow(sendNow: Boolean): Long = clock.now() - if (sendNow) SEND_NOW_GRACE_MS else 0

    companion object {
        /** "Send now" schedules a one-off at the current minute; this grace keeps it in the future window. */
        const val SEND_NOW_GRACE_MS = 2 * MINUTE_MS
        val CANCELLABLE = setOf(
            MessageStatus.SCHEDULED, MessageStatus.DUE, MessageStatus.RETRY_SCHEDULED, MessageStatus.PAUSED,
            MessageStatus.AWAITING_REVIEW, MessageStatus.NEEDS_ATTENTION,
        )
    }
}
