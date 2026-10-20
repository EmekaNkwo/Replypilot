package com.replypilot.domain.simulation

import com.replypilot.domain.automation.ActivityLogger
import com.replypilot.domain.common.HOUR_MS
import com.replypilot.domain.common.MINUTE_MS
import com.replypilot.domain.messaging.ChannelAdapter
import com.replypilot.domain.messaging.OutboundMessage
import com.replypilot.domain.messaging.SendOutcome
import com.replypilot.domain.messaging.StaticAdapterRegistry
import com.replypilot.domain.model.AutomationSettings
import com.replypilot.domain.model.AvailabilityState
import com.replypilot.domain.model.ChannelCapability
import com.replypilot.domain.model.ChannelDescriptor
import com.replypilot.domain.model.ChannelId
import com.replypilot.domain.model.ChannelIdentity
import com.replypilot.domain.model.ChannelStatus
import com.replypilot.domain.model.Contact
import com.replypilot.domain.model.ExecutionItem
import com.replypilot.domain.model.ExecutionSource
import com.replypilot.domain.model.FailureCode
import com.replypilot.domain.model.IntegrationMode
import com.replypilot.domain.model.ManualHandoff
import com.replypilot.domain.model.MessageStatus
import com.replypilot.domain.model.QuietHoursBehavior
import com.replypilot.domain.model.Recipient
import com.replypilot.domain.model.SequenceEnrollment
import com.replypilot.domain.model.TimeWindow
import com.replypilot.domain.scheduling.QueueProcessor
import com.replypilot.domain.scheduling.ScheduleMaterializer
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

enum class QueueScenario(val title: String, val description: String) {
    SUCCESS("Successful send", "The channel accepts the message on the first attempt."),
    TRANSIENT_FAILURE("Temporary failure, then success", "Two network errors, then the channel accepts it. Shows backoff."),
    RETRIES_EXHAUSTED("Retries exhausted", "The provider keeps failing until the retry limit is reached."),
    PERMANENT_FAILURE("Permanent failure", "The provider rejects the message. No retries are attempted."),
    PERMISSION_MISSING("Permission missing", "Notification access was revoked. The message is handed to you instead."),
    QUIET_HOURS("Quiet hours", "The message is due at 23:00 during quiet hours and waits until they end."),
    OPTED_OUT("Recipient opted out", "The recipient opted out after scheduling. Nothing is sent."),
    DOUBLE_WORKER("Worker runs twice", "Two queue workers run at the same moment. The message is sent once."),
    RESTART_DURING_SEND("Restart during send", "The app died mid-send. The message is flagged for review, not resent."),
}

data class TimelineEntry(val minute: Long, val status: MessageStatus, val detail: String)

data class QueueSimulationResult(
    val scenario: QueueScenario,
    val timeline: List<TimelineEntry>,
    val sendAttempts: Int,
    val finalStatus: MessageStatus,
)

/**
 * Runs the real [QueueProcessor] against in-memory storage and a scripted adapter on a virtual clock.
 * Nothing leaves the simulator: the adapter only counts calls.
 */
object QueueSimulator {
    private val START: Long = LocalDateTime.of(2026, 1, 5, 10, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    suspend fun run(scenario: QueueScenario): QueueSimulationResult {
        val clock = MutableClock(if (scenario == QueueScenario.QUIET_HOURS) START + 13 * HOUR_MS else START)
        val ids = SequentialIds("sim")
        val executions = InMemoryExecutionRepository()
        val contacts = InMemoryContactRepository()
        val settings = InMemorySettingsRepository(
            AutomationSettings(quietHoursEnabled = true, quietHours = TimeWindow("22:00", "07:00")),
        )
        val adapter = ScriptedAdapter(script(scenario))
        val activity = ActivityLogger(InMemoryActivityRepository(), clock, ids)
        val schedules = InMemoryScheduleRepository()
        val sequences = InMemorySequenceRepository()
        val materializer = ScheduleMaterializer(schedules, executions, contacts, settings, clock, ids)
        fun processor() = QueueProcessor(
            executions, schedules, sequences, contacts, InMemoryConversationRepository(), settings, StaticAdapterRegistry(listOf(adapter)),
            materializer, activity, clock, RecordingWakeScheduler(),
        )

        if (scenario == QueueScenario.OPTED_OUT) {
            contacts.upsert(Contact("c1", "Sam", identities = listOf(ChannelIdentity(ChannelId.DEMO, "sam")), optedOut = true))
        }
        // A sequence step is used because it passes through every preflight check, including quiet hours.
        sequences.upsertEnrollment(
            SequenceEnrollment("enrollment-1", "simulation", ChannelId.DEMO, Recipient("sam", "Sam"), startedAt = clock.now()),
        )
        executions.insertIfAbsent(
            listOf(
                ExecutionItem(
                    id = EXECUTION_ID,
                    idempotencyKey = "sim:1",
                    source = ExecutionSource.SEQUENCE,
                    sourceId = "enrollment-1",
                    occurrenceIndex = 0,
                    channel = ChannelId.DEMO,
                    recipient = Recipient("sam", "Sam", if (scenario == QueueScenario.OPTED_OUT) "c1" else null),
                    body = "Simulated message",
                    plannedAt = clock.now(),
                    expiresAt = clock.now() + 24 * HOUR_MS,
                    status = MessageStatus.SCHEDULED,
                    maxAttempts = 4,
                    nextAttemptAt = clock.now(),
                    quietHours = QuietHoursBehavior.DEFER,
                    simulated = true,
                ),
            ),
        )

        val timeline = mutableListOf<TimelineEntry>()
        fun record(state: ExecutionItem?) {
            state ?: return
            val minute = (clock.now() - START) / MINUTE_MS
            if (timeline.lastOrNull()?.let { it.status == state.status && it.detail == state.statusReason.orEmpty() } != true) {
                timeline += TimelineEntry(minute, state.status, state.statusReason.orEmpty())
            }
        }
        record(executions.get(EXECUTION_ID))

        when (scenario) {
            QueueScenario.DOUBLE_WORKER -> coroutineScope {
                listOf(async { processor().process() }, async { processor().process() }).awaitAll()
            }
            QueueScenario.RESTART_DURING_SEND -> {
                executions.compareAndSetStatus(EXECUTION_ID, MessageStatus.SCHEDULED, MessageStatus.EXECUTING, clock.now())
                record(executions.get(EXECUTION_ID)?.copy(statusReason = "Sending… then the process was killed"))
                clock.advance(QueueProcessor.STALE_EXECUTION_MS + MINUTE_MS)
                processor().process()
            }
            else -> processor().process()
        }
        record(executions.get(EXECUTION_ID))

        repeat(MAX_STEPS) {
            val current = executions.get(EXECUTION_ID) ?: return@repeat
            if (current.status !in MessageStatus.RUNNABLE) return@repeat
            clock.nowMs = maxOf(clock.now(), current.nextAttemptAt)
            processor().process()
            record(executions.get(EXECUTION_ID))
        }
        val final = executions.get(EXECUTION_ID)?.status ?: MessageStatus.FAILED
        return QueueSimulationResult(scenario, timeline, adapter.calls, final)
    }

    private fun script(scenario: QueueScenario): List<SendOutcome> = when (scenario) {
        QueueScenario.TRANSIENT_FAILURE -> listOf(
            SendOutcome.Failed(FailureCode.NETWORK, "Simulated network error"),
            SendOutcome.Failed(FailureCode.NETWORK, "Simulated network error"),
            SendOutcome.Submitted("sim-ref", simulated = true),
        )
        QueueScenario.RETRIES_EXHAUSTED -> List(6) { SendOutcome.Failed(FailureCode.PROVIDER_ERROR, "Simulated provider error") }
        QueueScenario.PERMANENT_FAILURE -> listOf(SendOutcome.Failed(FailureCode.PROVIDER_REJECTED, "Simulated rejection"))
        QueueScenario.PERMISSION_MISSING -> listOf(SendOutcome.Failed(FailureCode.PERMISSION_MISSING, "Notification access is off"))
        else -> listOf(SendOutcome.Submitted("sim-ref", simulated = true))
    }

    private const val MAX_STEPS = 10
    private const val EXECUTION_ID = "exec-1"

    private class ScriptedAdapter(private val outcomes: List<SendOutcome>) : ChannelAdapter {
        var calls = 0
            private set

        override val descriptor = ChannelDescriptor(
            ChannelId.DEMO, IntegrationMode.SIMULATED,
            setOf(ChannelCapability.SEND_MESSAGE, ChannelCapability.AUTO_REPLY, ChannelCapability.MANUAL_HANDOFF),
        )

        override suspend fun status() = ChannelStatus(descriptor, AvailabilityState.READY, "Simulator")

        override suspend fun send(message: OutboundMessage): SendOutcome {
            val outcome = outcomes.getOrElse(calls) { outcomes.last() }
            calls++
            return outcome
        }

        override fun handoffFor(message: OutboundMessage) = ManualHandoff(ManualHandoff.Kind.COPY_TEXT, message.body)
    }
}
