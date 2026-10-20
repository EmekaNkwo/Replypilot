package com.replypilot.domain

import com.replypilot.domain.common.MINUTE_MS
import com.replypilot.domain.model.AutoReplyRule
import com.replypilot.domain.model.ChannelId
import com.replypilot.domain.model.ChannelIdentity
import com.replypilot.domain.model.Contact
import com.replypilot.domain.model.EnrollmentStatus
import com.replypilot.domain.model.ExecutionSource
import com.replypilot.domain.model.IncomingMessage
import com.replypilot.domain.model.MatchType
import com.replypilot.domain.model.MessageSequence
import com.replypilot.domain.model.MessageStatus
import com.replypilot.domain.model.Recipient
import com.replypilot.domain.model.ReplyMode
import com.replypilot.domain.model.RuleCondition
import com.replypilot.domain.model.SequenceStep
import com.replypilot.domain.scheduling.ActionResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IncomingMessageHandlerTest {
    private val pricing = AutoReplyRule(
        id = "pricing", name = "Pricing",
        conditions = listOf(RuleCondition.MessageMatches(MatchType.ANY_KEYWORD, listOf("price", "cost"))),
        response = "Hi {{firstName}}, our prices start at 10.", cooldownMinutes = 30,
    )

    private fun incoming(text: String, id: String = "n1", sender: String = "Ada Okafor") = IncomingMessage(
        id = id, channel = ChannelId.DEMO, conversationKey = sender, senderName = sender, text = text,
        receivedAt = MONDAY_10AM_UTC, replyTarget = "notification-1",
    )

    @Test
    fun `matching message is replied to through the queue and logged`() = runTest {
        val h = TestHarness()
        h.rules.upsert(pricing)
        h.handler.handle(incoming("What's the price?"))
        h.processor().process()
        val sent = h.adapter.sent.single()
        assertEquals("Hi Ada, our prices start at 10.", sent.body)
        assertEquals("notification-1", sent.replyTarget)
        val types = h.activityRepo.events.map { it.type.name }
        assertTrue(types.containsAll(listOf("RULE_MATCHED", "REPLY_SENT")))
    }

    @Test
    fun `the same notification posted twice produces one reply`() = runTest {
        val h = TestHarness()
        h.rules.upsert(pricing)
        h.handler.handle(incoming("price?", id = "n1"))
        h.handler.handle(incoming("price?", id = "n2"))
        h.processor().process()
        assertEquals(1, h.adapter.sent.size)
    }

    @Test
    fun `cooldown holds across separate messages`() = runTest {
        val h = TestHarness()
        h.rules.upsert(pricing)
        h.handler.handle(incoming("price?", id = "n1"))
        h.processor().process()
        h.clock.advance(10 * MINUTE_MS)
        h.handler.handle(incoming("what does it cost", id = "n2"))
        h.processor().process()
        assertEquals(1, h.adapter.sent.size)
        h.clock.advance(30 * MINUTE_MS)
        h.handler.handle(incoming("cost again?", id = "n3"))
        h.processor().process()
        assertEquals(2, h.adapter.sent.size)
    }

    @Test
    fun `two auto-responders talking to each other are cut off`() = runTest {
        val h = TestHarness()
        h.rules.upsert(pricing.copy(conditions = listOf(RuleCondition.MessageMatches(MatchType.ANY_MESSAGE, emptyList())), cooldownMinutes = 0))
        repeat(6) { i ->
            h.handler.handle(incoming("Automatic message $i from the other bot", id = "n$i"))
            h.processor().process()
            h.clock.advance(MINUTE_MS)
        }
        assertEquals(h.settings.current().loopThreshold, h.adapter.sent.size)
    }

    @Test
    fun `opt-out marks the contact and stops their sequences`() = runTest {
        val h = TestHarness()
        h.contacts.upsert(Contact("c1", "Ada", "Okafor", identities = listOf(ChannelIdentity(ChannelId.DEMO, "Ada Okafor"))))
        h.sequencesRepo.upsertSequence(
            MessageSequence("s1", "Follow-up", ChannelId.DEMO, listOf(SequenceStep(0, "12:00", "One"), SequenceStep(2, "12:00", "Two")), timeZone = "UTC"),
        )
        h.sequenceService.enroll("s1", listOf(Recipient("Ada Okafor", "Ada Okafor", "c1")))
        h.handler.handle(incoming("Stop"))
        assertTrue(h.contacts.get("c1")?.optedOut == true)
        assertEquals(EnrollmentStatus.STOPPED, h.sequencesRepo.enrollments().single().status)
        assertTrue(h.executions.items.filter { it.source == ExecutionSource.SEQUENCE }.all { it.status == MessageStatus.CANCELLED })
    }

    @Test
    fun `unknown sender who opts out is remembered`() = runTest {
        val h = TestHarness()
        h.handler.handle(incoming("unsubscribe", sender = "Stranger"))
        val contact = h.contacts.all().single()
        assertTrue(contact.optedOut)
        h.rules.upsert(pricing)
        h.handler.handle(incoming("price?", id = "n9", sender = "Stranger"))
        h.processor().process()
        assertTrue(h.adapter.sent.isEmpty())
    }

    @Test
    fun `manual approval waits for the user`() = runTest {
        val h = TestHarness()
        h.rules.upsert(pricing.copy(replyMode = ReplyMode.MANUAL_APPROVAL))
        h.handler.handle(incoming("price?"))
        h.processor().process()
        assertTrue(h.adapter.sent.isEmpty())
        val pending = h.executions.items.single()
        assertEquals(MessageStatus.AWAITING_REVIEW, pending.status)
        assertEquals(ActionResult.Done, h.actions.approve(pending.id, "Edited reply"))
        h.processor().process()
        assertEquals("Edited reply", h.adapter.sent.single().body)
    }

    @Test
    fun `mock AI drafts always wait for review even with auto-send enabled`() = runTest {
        val h = TestHarness(com.replypilot.domain.model.AutomationSettings(quietHoursEnabled = false, aiAutoSend = true, aiMinConfidence = 0.1))
        h.rules.upsert(pricing.copy(replyMode = ReplyMode.AI_ASSISTED))
        h.handler.handle(incoming("price?"))
        h.processor().process()
        assertTrue(h.adapter.sent.isEmpty())
        assertEquals(MessageStatus.AWAITING_REVIEW, h.executions.items.single().status)
    }

    @Test
    fun `reply from a sequence recipient stops the sequence`() = runTest {
        val h = TestHarness()
        h.sequencesRepo.upsertSequence(
            MessageSequence("s1", "Follow-up", ChannelId.DEMO, listOf(SequenceStep(1, "12:00", "Checking in")), timeZone = "UTC"),
        )
        h.sequenceService.enroll("s1", listOf(Recipient("Ada Okafor", "Ada Okafor")))
        h.handler.handle(incoming("Thanks, got it"))
        assertEquals(EnrollmentStatus.STOPPED, h.sequencesRepo.enrollments().single().status)
    }

    @Test
    fun `replying yourself pauses auto-replies in that conversation`() = runTest {
        val h = TestHarness()
        h.rules.upsert(pricing)
        h.handler.handle(incoming("I'll call you in a minute", id = "self").copy(isFromSelf = true))
        h.handler.handle(incoming("ok, and the price?", id = "n2"))
        h.processor().process()
        assertTrue(h.adapter.sent.isEmpty())
        assertTrue(h.activityRepo.events.any { it.type.name == "HUMAN_TAKEOVER_STARTED" })
    }

    @Test
    fun `an echo of our own reply does not count as a takeover`() = runTest {
        val h = TestHarness()
        h.rules.upsert(pricing.copy(cooldownMinutes = 0))
        h.handler.handle(incoming("price?", id = "n1"))
        h.processor().process()
        h.handler.handle(incoming("Hi Ada, our prices start at 10.", id = "echo").copy(isFromSelf = true))
        h.handler.handle(incoming("great, what about delivery cost", id = "n3"))
        h.processor().process()
        assertEquals(2, h.adapter.sent.size)
    }
}
