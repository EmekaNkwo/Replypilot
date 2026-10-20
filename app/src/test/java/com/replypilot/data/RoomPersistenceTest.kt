package com.replypilot.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.replypilot.core.database.ReplyPilotDatabase
import com.replypilot.data.repositories.RoomContactRepository
import com.replypilot.data.repositories.RoomExecutionRepository
import com.replypilot.data.repositories.RoomRuleRepository
import com.replypilot.data.repositories.RoomScheduleRepository
import com.replypilot.domain.model.AutoReplyRule
import com.replypilot.domain.model.ChannelId
import com.replypilot.domain.model.ChannelIdentity
import com.replypilot.domain.model.Contact
import com.replypilot.domain.model.ExecutionItem
import com.replypilot.domain.model.ExecutionSource
import com.replypilot.domain.model.Frequency
import com.replypilot.domain.model.ManualHandoff
import com.replypilot.domain.model.MatchType
import com.replypilot.domain.model.MessageStatus
import com.replypilot.domain.model.Recipient
import com.replypilot.domain.model.RuleCondition
import com.replypilot.domain.model.ScheduleSpec
import com.replypilot.domain.model.ScheduledMessage
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomPersistenceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: ReplyPilotDatabase

    private fun open() = Room.databaseBuilder(context, ReplyPilotDatabase::class.java, "persistence-test.db")
        .allowMainThreadQueries()
        .build()

    @Before fun setUp() {
        context.deleteDatabase("persistence-test.db")
        db = open()
    }

    @After fun tearDown() {
        db.close()
        context.deleteDatabase("persistence-test.db")
    }

    private fun row(id: String, key: String = "key-$id", status: MessageStatus = MessageStatus.SCHEDULED, next: Long = 1_000) = ExecutionItem(
        id = id, idempotencyKey = key, source = ExecutionSource.SCHEDULED, sourceId = "s1", occurrenceIndex = 0,
        channel = ChannelId.DEMO, recipient = Recipient("ada", "Ada"), body = "Hi", plannedAt = next, expiresAt = null,
        status = status, nextAttemptAt = next, handoff = ManualHandoff(ManualHandoff.Kind.COPY_TEXT, "Hi"),
    )

    @Test
    fun `schedules and rules survive closing and reopening the database`() = runTest {
        val schedule = ScheduledMessage(
            id = "s1", title = "Weekly", channel = ChannelId.DEMO, recipients = listOf(Recipient("ada", "Ada")), body = "Hi {{firstName}}",
            spec = ScheduleSpec("2026-01-05", "09:00", "Africa/Lagos", frequency = Frequency.WEEKLY, weekdays = setOf(1, 3)),
        )
        val rule = AutoReplyRule(
            id = "r1", name = "Pricing", response = "From 10",
            conditions = listOf(RuleCondition.MessageMatches(MatchType.ANY_KEYWORD, listOf("price")), RuleCondition.OutsideWorkingHours, RuleCondition.UnknownSender),
        )
        RoomScheduleRepository(db).upsert(schedule)
        RoomRuleRepository(db).upsert(rule)
        db.close()

        db = open()
        assertEquals(schedule, RoomScheduleRepository(db).get("s1"))
        assertEquals(rule, RoomRuleRepository(db).get("r1"))
    }

    @Test
    fun `idempotency key rejects duplicate executions`() = runTest {
        val repo = RoomExecutionRepository(db)
        assertEquals(1, repo.insertIfAbsent(listOf(row("a", key = "same"))))
        assertEquals(0, repo.insertIfAbsent(listOf(row("b", key = "same"))))
        assertEquals(1, repo.withStatus(setOf(MessageStatus.SCHEDULED)).size)
        assertNotNull(repo.get("a")?.handoff)
    }

    @Test
    fun `compare-and-set claims a row only once`() = runTest {
        val repo = RoomExecutionRepository(db)
        repo.insertIfAbsent(listOf(row("a")))
        assertTrue(repo.compareAndSetStatus("a", MessageStatus.SCHEDULED, MessageStatus.EXECUTING, 5))
        assertFalse(repo.compareAndSetStatus("a", MessageStatus.SCHEDULED, MessageStatus.EXECUTING, 6))
        assertEquals(5L, repo.get("a")?.lastAttemptAt)
    }

    @Test
    fun `due rows are ordered and filtered by time and status`() = runTest {
        val repo = RoomExecutionRepository(db)
        repo.insertIfAbsent(listOf(row("late", next = 3_000), row("early", next = 1_000), row("future", next = 9_000), row("done", status = MessageStatus.SUBMITTED)))
        assertEquals(listOf("early", "late"), repo.due(5_000, MessageStatus.RUNNABLE, 10).map { it.id })
        assertEquals(1_000L, repo.nextWakeAt(MessageStatus.RUNNABLE))
    }

    @Test
    fun `contacts are found by normalised address per channel only`() = runTest {
        val repo = RoomContactRepository(db)
        repo.upsert(Contact("c1", "Ada", identities = listOf(ChannelIdentity(ChannelId.WHATSAPP, "+234 800 000 0001"))))
        assertEquals("c1", repo.findByAddress(ChannelId.WHATSAPP, "+2348000000001")?.id)
        assertEquals(null, repo.findByAddress(ChannelId.SMS, "+2348000000001"))
        repo.upsert(Contact("c1", "Ada", identities = listOf(ChannelIdentity(ChannelId.SMS, "+2348000000001"))))
        assertEquals(null, repo.findByAddress(ChannelId.WHATSAPP, "+2348000000001"))
    }
}
