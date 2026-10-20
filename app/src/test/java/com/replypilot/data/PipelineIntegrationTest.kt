package com.replypilot.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.replypilot.core.database.ReplyPilotDatabase
import com.replypilot.data.repositories.RoomActivityRepository
import com.replypilot.data.repositories.RoomContactRepository
import com.replypilot.data.repositories.RoomConversationRepository
import com.replypilot.data.repositories.RoomExecutionRepository
import com.replypilot.data.repositories.RoomRuleRepository
import com.replypilot.data.repositories.RoomScheduleRepository
import com.replypilot.data.repositories.RoomSequenceRepository
import com.replypilot.data.repositories.RoomTemplateRepository
import com.replypilot.domain.assistant.MockMessageAssistant
import com.replypilot.domain.automation.ActivityLogger
import com.replypilot.domain.automation.IncomingMessageHandler
import com.replypilot.domain.automation.RuleContextBuilder
import com.replypilot.domain.automation.SequenceService
import com.replypilot.domain.common.HOUR_MS
import com.replypilot.domain.messaging.StaticAdapterRegistry
import com.replypilot.domain.model.ActivityType
import com.replypilot.domain.model.AutoReplyRule
import com.replypilot.domain.model.AutomationSettings
import com.replypilot.domain.model.ChannelId
import com.replypilot.domain.model.IncomingMessage
import com.replypilot.domain.model.MatchType
import com.replypilot.domain.model.MessageStatus
import com.replypilot.domain.model.Recipient
import com.replypilot.domain.model.RuleCondition
import com.replypilot.domain.model.ScheduleSpec
import com.replypilot.domain.model.ScheduledMessage
import com.replypilot.domain.scheduling.QueueProcessor
import com.replypilot.domain.scheduling.SaveResult
import com.replypilot.domain.scheduling.ScheduleMaterializer
import com.replypilot.domain.scheduling.ScheduleService
import com.replypilot.domain.simulation.InMemorySettingsRepository
import com.replypilot.domain.simulation.MutableClock
import com.replypilot.domain.simulation.RecordingWakeScheduler
import com.replypilot.domain.simulation.SequentialIds
import com.replypilot.integrations.demo.DemoAdapter
import com.replypilot.integrations.demo.DemoControls
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The production domain services running over real Room storage and the demo adapter. */
@RunWith(AndroidJUnit4::class)
class PipelineIntegrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: ReplyPilotDatabase
    private val clock = MutableClock(LocalDateTime.of(2026, 1, 5, 10, 0).toInstant(ZoneOffset.UTC).toEpochMilli())
    private val ids = SequentialIds()
    private val settings = InMemorySettingsRepository(AutomationSettings(quietHoursEnabled = false))
    private val wake = RecordingWakeScheduler()

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, ReplyPilotDatabase::class.java).allowMainThreadQueries().build()
    }

    @After fun tearDown() = db.close()

    private val schedules by lazy { RoomScheduleRepository(db) }
    private val executions by lazy { RoomExecutionRepository(db) }
    private val contacts by lazy { RoomContactRepository(db) }
    private val activity by lazy { RoomActivityRepository(db) }
    private val conversations by lazy { RoomConversationRepository(db) }
    private val rules by lazy { RoomRuleRepository(db) }
    private val sequences by lazy { RoomSequenceRepository(db) }
    private val adapters = StaticAdapterRegistry(listOf(DemoAdapter(DemoControls())))
    private val logger by lazy { ActivityLogger(activity, clock, ids) }
    private val materializer by lazy { ScheduleMaterializer(schedules, executions, contacts, settings, clock, ids) }
    private fun processor() = QueueProcessor(executions, schedules, sequences, contacts, conversations, settings, adapters, materializer, logger, clock, wake)

    @Test
    fun `scheduled message is persisted, survives a restart and is submitted once`() = runTest {
        val service = ScheduleService(schedules, executions, contacts, settings, adapters, materializer, logger, clock, ids, wake)
        val result = service.save(
            ScheduledMessage("", "Reminder", ChannelId.DEMO, listOf(Recipient("Ada", "Ada")), "Hi {{firstName}}", spec = ScheduleSpec("2026-01-05", "11:00", "UTC")),
        )
        assertTrue(result is SaveResult.Saved)
        clock.advance(HOUR_MS)
        processor().process()
        processor().process()
        val rows = executions.withStatus(MessageStatus.SUCCESS)
        assertEquals(1, rows.size)
        assertTrue("demo sends are always marked simulated", rows.single().simulated)
    }

    @Test
    fun `incoming message runs through rules and is logged without its body`() = runTest {
        rules.upsert(
            AutoReplyRule("r1", "Pricing", conditions = listOf(RuleCondition.MessageMatches(MatchType.CONTAINS, listOf("price"))), response = "From 10"),
        )
        val sequenceService = SequenceService(sequences, executions, contacts, settings, logger, clock, ids, wake)
        val handler = IncomingMessageHandler(
            rules, contacts, conversations, executions, settings,
            RuleContextBuilder(contacts, conversations, executions, RoomTemplateRepository(db), settings, adapters, clock),
            sequenceService, MockMessageAssistant(), logger, clock, ids, wake,
        )
        val secret = "what is the price for the confidential project"
        handler.handle(IncomingMessage("n1", ChannelId.DEMO, "Ada", "Ada", text = secret, receivedAt = clock.now(), simulated = true))
        processor().process()

        assertEquals("From 10", executions.withStatus(MessageStatus.SUCCESS).single().body)
        val events = activity.since(0)
        assertTrue(events.any { it.type == ActivityType.RULE_MATCHED })
        assertTrue(events.any { it.type == ActivityType.REPLY_SENT })
        assertTrue("activity must not contain the incoming text", events.none { it.reason?.contains("confidential") == true })
    }
}
