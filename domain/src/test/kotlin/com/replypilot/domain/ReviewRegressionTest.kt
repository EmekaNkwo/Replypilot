package com.replypilot.domain

import com.replypilot.domain.common.HOUR_MS
import com.replypilot.domain.common.MINUTE_MS
import com.replypilot.domain.model.AutoReplyRule
import com.replypilot.domain.model.ChannelId
import com.replypilot.domain.model.IncomingMessage
import com.replypilot.domain.model.MatchType
import com.replypilot.domain.model.MessageSequence
import com.replypilot.domain.model.MessageStatus
import com.replypilot.domain.model.Recipient
import com.replypilot.domain.model.ReplyMode
import com.replypilot.domain.model.RuleCondition
import com.replypilot.domain.model.ScheduleSpec
import com.replypilot.domain.model.SequenceStep
import com.replypilot.domain.scheduling.ActionResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards for defects found in code review. */
class ReviewRegressionTest {
    private val anyMessage = AutoReplyRule(
        id = "r", name = "Any", conditions = listOf(RuleCondition.MessageMatches(MatchType.ANY_MESSAGE, emptyList())),
        response = "Thanks!", cooldownMinutes = 0,
    )

    private fun incoming(text: String, id: String, self: Boolean = false) = IncomingMessage(
        id = id, channel = ChannelId.DEMO, conversationKey = "Ada", senderName = "Ada", text = text,
        receivedAt = MONDAY_10AM_UTC, isFromSelf = self,
    )

    @Test
    fun `a reply approved hours later is still sent`() = runTest {
        val h = TestHarness()
        h.rules.upsert(anyMessage.copy(replyMode = ReplyMode.MANUAL_APPROVAL))
        h.handler.handle(incoming("hello", "n1"))
        h.clock.advance(3 * HOUR_MS)
        val pending = h.executions.items.single()
        assertEquals(ActionResult.Done, h.actions.approve(pending.id))
        h.processor().process()
        assertEquals(1, h.adapter.sent.size)
    }

    @Test
    fun `taking over cancels a delayed reply that was already queued`() = runTest {
        val h = TestHarness()
        h.rules.upsert(anyMessage.copy(delaySeconds = 60))
        h.handler.handle(incoming("hello", "n1"))
        h.handler.handle(incoming("Hi Ada, I'm here", "self", self = true))
        h.clock.advance(2 * MINUTE_MS)
        h.processor().process()
        assertTrue(h.adapter.sent.isEmpty())
        assertEquals(MessageStatus.SKIPPED, h.executions.items.single().status)
    }

    @Test
    fun `the same address twice in one enrollment is enrolled once`() = runTest {
        val h = TestHarness()
        h.sequencesRepo.upsertSequence(MessageSequence("s", "S", ChannelId.DEMO, listOf(SequenceStep(0, "12:00", "Hi")), timeZone = "UTC"))
        val result = h.sequenceService.enroll("s", listOf(Recipient("ada", "Ada", "c1"), Recipient(" ADA ", "Ada (typed)")))
        assertEquals(1, result.enrolled.size)
        assertEquals(1, h.executions.items.size)
    }

    @Test
    fun `retrying a message with empty placeholders is refused with guidance`() = runTest {
        val h = TestHarness()
        h.scheduleService.save(h.draft(spec = ScheduleSpec("2026-01-05", "12:00", "UTC"), body = "Hi {{firstName|there}}"))
        val row = h.executions.items.single()
        h.executions.update(row.copy(status = MessageStatus.NEEDS_ATTENTION, failureCode = com.replypilot.domain.model.FailureCode.UNRESOLVED_VARIABLES))
        assertTrue(h.actions.retry(row.id) is ActionResult.Rejected)
    }

    @Test
    fun `a backlog larger than one batch is drained in a single run`() = runTest {
        val h = TestHarness(com.replypilot.domain.model.AutomationSettings(quietHoursEnabled = false, maxMessagesPerDay = 1_000))
        val recipients = (1..45).map { Recipient("p$it", "Person $it") }
        h.scheduleService.save(h.draft(recipients = recipients, body = "Hello"))
        h.scheduleService.save(h.draft(recipients = recipients, body = "Hello again"))
        h.clock.advance(2 * HOUR_MS)
        h.processor().process()
        assertEquals(90, h.adapter.sent.size)
    }
}
