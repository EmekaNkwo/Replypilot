package com.replypilot.domain

import com.replypilot.domain.common.HOUR_MS
import com.replypilot.domain.common.MINUTE_MS
import com.replypilot.domain.messaging.DeliveryState
import com.replypilot.domain.messaging.SendOutcome
import com.replypilot.domain.model.AutomationSettings
import com.replypilot.domain.model.ChannelId
import com.replypilot.domain.model.ChannelIdentity
import com.replypilot.domain.model.Contact
import com.replypilot.domain.model.ExecutionItem
import com.replypilot.domain.model.FailureCode
import com.replypilot.domain.model.MessageStatus
import com.replypilot.domain.model.QuietHoursBehavior
import com.replypilot.domain.model.Recipient
import com.replypilot.domain.model.ScheduleSpec
import com.replypilot.domain.model.SendPolicy
import com.replypilot.domain.model.TimeWindow
import com.replypilot.domain.scheduling.ActionResult
import com.replypilot.domain.scheduling.SaveResult
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueProcessorTest {
    private suspend fun TestHarness.scheduleAt(time: String, policy: SendPolicy = SendPolicy()): ExecutionItem {
        val result = scheduleService.save(draft(spec = ScheduleSpec("2026-01-05", time, "UTC")).copy(policy = policy))
        assertTrue(result is SaveResult.Saved)
        return executions.items.last()
    }

    private suspend fun TestHarness.row(id: String) = requireNotNull(executions.get(id))

    @Test
    fun `nothing is sent before it is due`() = runTest {
        val h = TestHarness()
        h.scheduleAt("12:00")
        h.processor().process()
        assertTrue(h.adapter.sent.isEmpty())
        assertNotNull(h.wake.requests.last())
    }

    @Test
    fun `due row is submitted, not reported as delivered`() = runTest {
        val h = TestHarness()
        val item = h.scheduleAt("12:00")
        h.clock.advance(2 * HOUR_MS)
        val report = h.processor().process()
        assertEquals(1, report.submitted)
        assertEquals(MessageStatus.SUBMITTED, h.row(item.id).status)
    }

    @Test
    fun `delivered only after the channel confirms it`() = runTest {
        val h = TestHarness()
        val item = h.scheduleAt("12:00")
        h.clock.advance(2 * HOUR_MS)
        h.processor().process()
        h.processor().process()
        assertEquals(MessageStatus.SUBMITTED, h.row(item.id).status)
        h.adapter.delivery = DeliveryState.DELIVERED
        h.processor().process()
        assertEquals(MessageStatus.DELIVERED, h.row(item.id).status)
    }

    @Test
    fun `two workers running concurrently send exactly once`() = runTest {
        val h = TestHarness()
        h.scheduleAt("12:00")
        h.clock.advance(2 * HOUR_MS)
        listOf(async { h.processor().process() }, async { h.processor().process() }, async { h.processor().process() }).awaitAll()
        assertEquals(1, h.adapter.sent.size)
    }

    @Test
    fun `transient failures retry with backoff then succeed`() = runTest {
        val h = TestHarness()
        h.adapter.outcomes = mutableListOf(SendOutcome.Failed(FailureCode.NETWORK, "offline"))
        val item = h.scheduleAt("12:00")
        h.clock.advance(2 * HOUR_MS)
        h.processor().process()
        val retry = h.row(item.id)
        assertEquals(MessageStatus.RETRY_SCHEDULED, retry.status)
        assertEquals(h.clock.now() + MINUTE_MS, retry.nextAttemptAt)

        h.processor().process()
        assertEquals("Retry must wait for its backoff", 1, h.adapter.sent.size)
        h.clock.advance(MINUTE_MS)
        h.processor().process()
        assertEquals(MessageStatus.SUBMITTED, h.row(item.id).status)
        assertEquals(2, h.adapter.sent.size)
    }

    @Test
    fun `retries are bounded`() = runTest {
        val h = TestHarness()
        h.adapter.outcomes = MutableList(10) { SendOutcome.Failed(FailureCode.PROVIDER_ERROR, "500") }
        val item = h.scheduleAt("12:00", SendPolicy(maxRetries = 2))
        h.clock.advance(2 * HOUR_MS)
        repeat(10) {
            h.processor().process()
            h.clock.advance(HOUR_MS)
        }
        assertEquals(3, h.adapter.sent.size)
        assertEquals(MessageStatus.FAILED, h.row(item.id).status)
    }

    @Test
    fun `permanent failures are never retried`() = runTest {
        val h = TestHarness()
        h.adapter.outcomes = mutableListOf(SendOutcome.Failed(FailureCode.PROVIDER_REJECTED, "invalid"))
        val item = h.scheduleAt("12:00")
        h.clock.advance(2 * HOUR_MS)
        h.processor().process()
        h.clock.advance(HOUR_MS)
        h.processor().process()
        assertEquals(1, h.adapter.sent.size)
        assertEquals(MessageStatus.FAILED, h.row(item.id).status)
    }

    @Test
    fun `missing permission hands the message to the user`() = runTest {
        val h = TestHarness()
        h.adapter.outcomes = MutableList(5) { SendOutcome.Failed(FailureCode.PERMISSION_MISSING, "Notification access off") }
        val item = h.scheduleAt("12:00", SendPolicy(maxRetries = 0))
        h.clock.advance(2 * HOUR_MS)
        h.processor().process()
        val row = h.row(item.id)
        assertEquals(MessageStatus.NEEDS_ATTENTION, row.status)
        assertNotNull(row.handoff)
        assertTrue(h.activityRepo.events.any { it.type == com.replypilot.domain.model.ActivityType.PERMISSION_UNAVAILABLE })
    }

    @Test
    fun `quiet hours defer by default and skip when configured`() = runTest {
        val h = TestHarness(AutomationSettings(quietHoursEnabled = true, quietHours = TimeWindow("22:00", "07:00")))
        val deferred = h.scheduleAt("23:00")
        h.scheduleService.save(h.draft(spec = ScheduleSpec("2026-01-05", "23:30", "UTC"), body = "Skip {{firstName}}")
            .copy(policy = SendPolicy(quietHours = QuietHoursBehavior.SKIP)))
        val skipped = h.executions.items.last()
        h.clock.advance(14 * HOUR_MS) // 00:00 Tuesday
        h.processor().process()
        assertTrue(h.adapter.sent.isEmpty())
        assertEquals(MessageStatus.SKIPPED, h.row(skipped.id).status)
        val waiting = h.row(deferred.id)
        assertEquals(MessageStatus.SCHEDULED, waiting.status)
        assertEquals(MONDAY_10AM_UTC + 21 * HOUR_MS, waiting.nextAttemptAt) // 07:00 Tuesday
        assertEquals(0, waiting.attempts)
    }

    @Test
    fun `opted-out recipients are skipped at send time`() = runTest {
        val h = TestHarness()
        val item = h.scheduleAt("12:00")
        h.contacts.upsert(Contact("c1", "Ada", identities = listOf(ChannelIdentity(ChannelId.DEMO, "ada")), optedOut = true))
        h.clock.advance(2 * HOUR_MS)
        h.processor().process()
        assertTrue(h.adapter.sent.isEmpty())
        assertEquals(MessageStatus.SKIPPED, h.row(item.id).status)
    }

    @Test
    fun `rows past their expiry are expired rather than sent late`() = runTest {
        val h = TestHarness()
        val item = h.scheduleAt("12:00", SendPolicy(expireAfterMinutes = 30))
        h.clock.advance(3 * HOUR_MS)
        h.processor().process()
        assertTrue(h.adapter.sent.isEmpty())
        assertEquals(MessageStatus.EXPIRED, h.row(item.id).status)
    }

    @Test
    fun `row stuck executing after a crash is flagged, never resent`() = runTest {
        val h = TestHarness()
        val item = h.scheduleAt("12:00")
        h.clock.advance(2 * HOUR_MS)
        h.executions.compareAndSetStatus(item.id, MessageStatus.SCHEDULED, MessageStatus.EXECUTING, h.clock.now())
        h.clock.advance(10 * MINUTE_MS)
        val report = h.processor().process()
        assertEquals(1, report.recovered)
        assertTrue(h.adapter.sent.isEmpty())
        assertEquals(FailureCode.INTERRUPTED, h.row(item.id).failureCode)
    }

    @Test
    fun `global pause stops all sending until resumed`() = runTest {
        val h = TestHarness()
        h.scheduleAt("12:00")
        h.actions.setAutomationPaused(true)
        h.clock.advance(2 * HOUR_MS)
        assertTrue(h.processor().process().paused)
        assertTrue(h.adapter.sent.isEmpty())
        h.actions.setAutomationPaused(false)
        h.processor().process()
        assertEquals(1, h.adapter.sent.size)
    }

    @Test
    fun `daily limit defers excess messages to tomorrow`() = runTest {
        val h = TestHarness(AutomationSettings(quietHoursEnabled = false, maxMessagesPerDay = 1))
        h.scheduleService.save(h.draft(recipients = listOf(Recipient("ada", "Ada"), Recipient("ben", "Ben"))))
        h.clock.advance(2 * HOUR_MS)
        h.processor().process()
        assertEquals(1, h.adapter.sent.size)
        assertEquals(1, h.executions.items.count { it.status == MessageStatus.SCHEDULED })
    }

    @Test
    fun `cancelled rows cannot be cancelled again and retry needs a failed row`() = runTest {
        val h = TestHarness()
        val item = h.scheduleAt("12:00")
        assertEquals(ActionResult.Done, h.actions.cancel(item.id))
        assertTrue(h.actions.cancel(item.id) is ActionResult.Rejected)
        assertTrue(h.actions.retry(item.id) is ActionResult.Rejected)
    }

    @Test
    fun `manual retry of a failed row sends once more`() = runTest {
        val h = TestHarness()
        h.adapter.outcomes = mutableListOf(SendOutcome.Failed(FailureCode.PROVIDER_REJECTED, "no"))
        val item = h.scheduleAt("12:00")
        h.clock.advance(2 * HOUR_MS)
        h.processor().process()
        assertEquals(ActionResult.Done, h.actions.retry(item.id))
        h.processor().process()
        assertEquals(MessageStatus.SUBMITTED, h.row(item.id).status)
    }
}
