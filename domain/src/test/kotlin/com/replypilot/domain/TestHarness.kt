package com.replypilot.domain

import com.replypilot.domain.assistant.MessageAssistant
import com.replypilot.domain.assistant.MockMessageAssistant
import com.replypilot.domain.automation.ActivityLogger
import com.replypilot.domain.automation.IncomingMessageHandler
import com.replypilot.domain.automation.RuleContextBuilder
import com.replypilot.domain.automation.SequenceService
import com.replypilot.domain.messaging.ChannelAdapter
import com.replypilot.domain.messaging.DeliveryReport
import com.replypilot.domain.messaging.DeliveryState
import com.replypilot.domain.messaging.OutboundMessage
import com.replypilot.domain.messaging.SendOutcome
import com.replypilot.domain.messaging.StaticAdapterRegistry
import com.replypilot.domain.model.AutomationSettings
import com.replypilot.domain.model.AvailabilityState
import com.replypilot.domain.model.ChannelCapability
import com.replypilot.domain.model.ChannelDescriptor
import com.replypilot.domain.model.ChannelId
import com.replypilot.domain.model.ChannelStatus
import com.replypilot.domain.model.IntegrationMode
import com.replypilot.domain.model.ManualHandoff
import com.replypilot.domain.model.Recipient
import com.replypilot.domain.model.ScheduleSpec
import com.replypilot.domain.model.ScheduledMessage
import com.replypilot.domain.scheduling.QueueActions
import com.replypilot.domain.scheduling.QueueProcessor
import com.replypilot.domain.scheduling.ScheduleMaterializer
import com.replypilot.domain.scheduling.ScheduleService
import com.replypilot.domain.simulation.InMemoryActivityRepository
import com.replypilot.domain.simulation.InMemoryContactRepository
import com.replypilot.domain.simulation.InMemoryConversationRepository
import com.replypilot.domain.simulation.InMemoryExecutionRepository
import com.replypilot.domain.simulation.InMemoryRuleRepository
import com.replypilot.domain.simulation.InMemoryScheduleRepository
import com.replypilot.domain.simulation.InMemorySequenceRepository
import com.replypilot.domain.simulation.InMemorySettingsRepository
import com.replypilot.domain.simulation.InMemoryTemplateRepository
import com.replypilot.domain.simulation.MutableClock
import com.replypilot.domain.simulation.RecordingWakeScheduler
import com.replypilot.domain.simulation.SequentialIds
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/** 2026-01-05 is a Monday. */
val MONDAY_10AM_UTC: Long = LocalDateTime.of(2026, 1, 5, 10, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

class FakeAdapter(
    channel: ChannelId = ChannelId.DEMO,
    capabilities: Set<ChannelCapability> = setOf(
        ChannelCapability.SEND_MESSAGE, ChannelCapability.AUTO_REPLY, ChannelCapability.MANUAL_HANDOFF,
        ChannelCapability.DELIVERY_STATUS,
    ),
    var availability: AvailabilityState = AvailabilityState.READY,
) : ChannelAdapter {
    override val descriptor = ChannelDescriptor(channel, IntegrationMode.SIMULATED, capabilities)
    val sent = mutableListOf<OutboundMessage>()
    var outcomes: MutableList<SendOutcome> = mutableListOf()
    var delivery: DeliveryState = DeliveryState.PENDING

    override suspend fun status() = ChannelStatus(descriptor, availability, availability.label)

    override suspend fun send(message: OutboundMessage): SendOutcome {
        sent += message
        return if (outcomes.isEmpty()) SendOutcome.Submitted("ref-${sent.size}") else outcomes.removeAt(0)
    }

    override fun handoffFor(message: OutboundMessage) = ManualHandoff(ManualHandoff.Kind.COPY_TEXT, message.body)

    override suspend fun deliveryStatus(providerRef: String) = DeliveryReport(providerRef, delivery)
}

class TestHarness(
    settings: AutomationSettings = AutomationSettings(quietHoursEnabled = false),
    zone: ZoneId = ZoneOffset.UTC,
    now: Long = MONDAY_10AM_UTC,
    val adapter: FakeAdapter = FakeAdapter(),
    val assistant: MessageAssistant = MockMessageAssistant(),
) {
    val clock = MutableClock(now, zone)
    val ids = SequentialIds()
    val schedules = InMemoryScheduleRepository()
    val executions = InMemoryExecutionRepository()
    val contacts = InMemoryContactRepository()
    val rules = InMemoryRuleRepository()
    val templates = InMemoryTemplateRepository()
    val sequencesRepo = InMemorySequenceRepository()
    val activityRepo = InMemoryActivityRepository()
    val conversations = InMemoryConversationRepository()
    val settings = InMemorySettingsRepository(settings)
    val wake = RecordingWakeScheduler()
    val registry = StaticAdapterRegistry(listOf(adapter))
    val activity = ActivityLogger(activityRepo, clock, ids)
    val materializer = ScheduleMaterializer(schedules, executions, contacts, this.settings, clock, ids)
    val scheduleService = ScheduleService(schedules, executions, contacts, this.settings, registry, materializer, activity, clock, ids, wake)
    val sequenceService = SequenceService(sequencesRepo, executions, contacts, this.settings, activity, clock, ids, wake)
    val actions = QueueActions(executions, conversations, this.settings, activity, clock, wake)
    val contextBuilder = RuleContextBuilder(contacts, conversations, executions, templates, this.settings, registry, clock)
    val handler = IncomingMessageHandler(
        rules, contacts, conversations, executions, this.settings, contextBuilder, sequenceService, assistant, activity, clock, ids, wake,
    )

    fun processor() = QueueProcessor(executions, schedules, sequencesRepo, contacts, conversations, settings, registry, materializer, activity, clock, wake)

    fun draft(
        spec: ScheduleSpec = ScheduleSpec(startDate = "2026-01-05", time = "12:00", timeZone = "UTC"),
        body: String = "Hello {{firstName}}",
        recipients: List<Recipient> = listOf(Recipient("ada", "Ada Okafor")),
        channel: ChannelId = ChannelId.DEMO,
    ) = ScheduledMessage(id = "", title = "Reminder", channel = channel, recipients = recipients, body = body, spec = spec)
}
