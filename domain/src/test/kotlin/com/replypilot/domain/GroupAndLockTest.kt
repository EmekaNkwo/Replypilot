package com.replypilot.domain

import com.replypilot.domain.common.HOUR_MS
import com.replypilot.domain.common.MINUTE_MS
import com.replypilot.domain.messaging.SendOutcome
import com.replypilot.domain.model.ActivityType
import com.replypilot.domain.model.ChannelCapability
import com.replypilot.domain.model.ChannelId
import com.replypilot.domain.model.FailureCode
import com.replypilot.domain.model.MessageStatus
import com.replypilot.domain.model.Recipient
import com.replypilot.domain.model.RecipientKind
import com.replypilot.domain.scheduling.SaveResult
import com.replypilot.domain.scheduling.ValidationField
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupAndLockTest {
    private val family = Recipient("Family Chat", "Family Chat", kind = RecipientKind.GROUP)

    @Test
    fun `a group recipient is accepted by name on a channel that supports groups`() = runTest {
        val h = TestHarness(adapter = FakeAdapter(capabilities = setOf(ChannelCapability.SEND_MESSAGE, ChannelCapability.GROUP_CONVERSATIONS)))
        val result = h.scheduleService.save(h.draft(recipients = listOf(family), body = "Hello {{firstName|everyone}}"))
        assertTrue("$result", result is SaveResult.Saved)
        assertEquals("Groups don't get a first name from their title", "Hello everyone", h.executions.items.single().body)
    }

    @Test
    fun `groups are rejected on channels without group support`() = runTest {
        val h = TestHarness(adapter = FakeAdapter(capabilities = setOf(ChannelCapability.SEND_MESSAGE)))
        val result = h.scheduleService.save(h.draft(recipients = listOf(family), body = "Hello"))
        assertTrue((result as SaveResult.Invalid).issues.any { it.field == ValidationField.RECIPIENTS && it.message.contains("group") })
    }

    @Test
    fun `a locked phone defers the send without using up retries`() = runTest {
        val h = TestHarness()
        h.adapter.outcomes = mutableListOf(
            SendOutcome.Failed(FailureCode.DEVICE_LOCKED, "Phone is locked"),
            SendOutcome.Failed(FailureCode.DEVICE_LOCKED, "Phone is locked"),
        )
        h.scheduleService.save(h.draft(channel = ChannelId.DEMO))
        h.clock.advance(2 * HOUR_MS)
        h.processor().process()
        val waiting = h.executions.items.single()
        assertEquals(MessageStatus.SCHEDULED, waiting.status)
        assertEquals(0, waiting.attempts)
        h.clock.advance(5 * MINUTE_MS); h.processor().process()
        h.clock.advance(5 * MINUTE_MS); h.processor().process()
        assertEquals(MessageStatus.SUBMITTED, h.executions.items.single().status)
        assertEquals(3, h.adapter.sent.size)
    }

    @Test
    fun `a chat picked by name is accepted and personalised like a person`() = runTest {
        val h = TestHarness()
        val chat = Recipient("Eze EZE", "Eze EZE", kind = RecipientKind.CHAT)
        val result = h.scheduleService.save(h.draft(recipients = listOf(chat), body = "Hi {{firstName}}"))
        assertTrue("$result", result is SaveResult.Saved)
        assertEquals("Hi Eze", h.executions.items.single().body)
        assertEquals(RecipientKind.CHAT, h.executions.items.single().recipient.kind)
    }

    @Test
    fun `cancelling a message is recorded in the activity history`() = runTest {
        val h = TestHarness()
        h.scheduleService.save(h.draft())
        val item = h.executions.items.single()
        h.actions.cancel(item.id)
        assertTrue(h.activityRepo.events.any { it.executionId == item.id && it.type == ActivityType.MESSAGE_CANCELLED })
    }

    @Test
    fun `several chats picked by name are different recipients`() = runTest {
        val h = TestHarness(adapter = FakeAdapter(channel = ChannelId.WHATSAPP, capabilities = setOf(ChannelCapability.SEND_MESSAGE)))
        val chats = listOf("Eze EZE", "Mum", "Ada Okafor").map { Recipient(it, it, kind = RecipientKind.CHAT) }
        val result = h.scheduleService.save(h.draft(channel = ChannelId.WHATSAPP, recipients = chats, body = "Hi"))
        assertTrue("$result", result is SaveResult.Saved)
        assertEquals(3, h.executions.items.size)
        val twice = chats + Recipient("mum", "mum", kind = RecipientKind.CHAT)
        assertTrue((h.scheduleService.save(h.draft(channel = ChannelId.WHATSAPP, recipients = twice, body = "Hey")) as SaveResult.Invalid)
            .issues.any { it.message.contains("more than once") })
    }

    @Test
    fun `clearing failed messages removes only failed rows`() = runTest {
        val h = TestHarness()
        h.adapter.outcomes = mutableListOf(SendOutcome.Failed(FailureCode.PROVIDER_REJECTED, "no"))
        h.scheduleService.save(h.draft(recipients = listOf(Recipient("ada", "Ada"), Recipient("ben", "Ben"))))
        h.clock.advance(2 * HOUR_MS)
        h.processor().process()
        assertEquals(1, h.actions.clearFailed())
        assertEquals(listOf(MessageStatus.SUBMITTED), h.executions.items.map { it.status })
    }
}
