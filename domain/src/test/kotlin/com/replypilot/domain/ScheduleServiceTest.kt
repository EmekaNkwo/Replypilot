package com.replypilot.domain

import com.replypilot.domain.common.HOUR_MS
import com.replypilot.domain.common.MINUTE_MS
import com.replypilot.domain.model.ChannelIdentity
import com.replypilot.domain.model.ChannelId
import com.replypilot.domain.model.Contact
import com.replypilot.domain.model.Frequency
import com.replypilot.domain.model.MessageStatus
import com.replypilot.domain.model.Recipient
import com.replypilot.domain.model.ScheduleSpec
import com.replypilot.domain.model.ScheduleState
import com.replypilot.domain.model.ZoneMode
import com.replypilot.domain.scheduling.SaveResult
import com.replypilot.domain.scheduling.ValidationField
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleServiceTest {
    private suspend fun TestHarness.saveOk(draft: com.replypilot.domain.model.ScheduledMessage, sendNow: Boolean = false): SaveResult.Saved {
        val result = scheduleService.save(draft, sendNow)
        assertTrue("Expected save to succeed but got $result", result is SaveResult.Saved)
        return result as SaveResult.Saved
    }

    @Test
    fun `creating a schedule materializes one row per recipient`() = runTest {
        val h = TestHarness()
        val saved = h.saveOk(h.draft(recipients = listOf(Recipient("ada", "Ada Okafor"), Recipient("ben", "Ben Cole"))))
        assertEquals(2, saved.queued)
        val rows = h.executions.items
        assertEquals(setOf("Hello Ada", "Hello Ben"), rows.map { it.body }.toSet())
        assertTrue(rows.all { it.status == MessageStatus.SCHEDULED && it.simulated })
    }

    @Test
    fun `validation rejects empty recipients, bad addresses and unresolved variables`() = runTest {
        val h = TestHarness()
        val noRecipients = h.scheduleService.save(h.draft(recipients = emptyList()))
        assertTrue(noRecipients is SaveResult.Invalid)

        val badPhone = h.scheduleService.save(h.draft(channel = ChannelId.SMS, recipients = listOf(Recipient("call me", "X"))))
        assertTrue((badPhone as SaveResult.Invalid).issues.any { it.field == ValidationField.RECIPIENTS })

        val unresolved = h.scheduleService.save(h.draft(body = "Your appointment is {{appointmentDate}}"))
        assertTrue((unresolved as SaveResult.Invalid).issues.any { it.field == ValidationField.BODY })
    }

    @Test
    fun `schedule entirely in the past is rejected`() = runTest {
        val h = TestHarness()
        val result = h.scheduleService.save(h.draft(spec = ScheduleSpec("2025-12-01", "09:00", "UTC")))
        assertTrue(result is SaveResult.Invalid)
    }

    @Test
    fun `identical schedule is rejected as a duplicate`() = runTest {
        val h = TestHarness()
        h.saveOk(h.draft())
        val second = h.scheduleService.save(h.draft())
        assertTrue((second as SaveResult.Invalid).issues.any { it.message.contains("already scheduled") })
    }

    @Test
    fun `editing a schedule replaces pending rows instead of adding to them`() = runTest {
        val h = TestHarness()
        val saved = h.saveOk(h.draft()).schedule
        h.saveOk(saved.copy(body = "Updated {{firstName}}", spec = saved.spec.copy(time = "13:00")))
        val active = h.executions.items.filter { it.status == MessageStatus.SCHEDULED }
        assertEquals(1, active.size)
        assertEquals("Updated Ada", active.single().body)
        assertEquals(1, h.executions.items.count { it.status == MessageStatus.CANCELLED })
    }

    @Test
    fun `recurring schedule only materializes the rolling horizon`() = runTest {
        val h = TestHarness()
        h.saveOk(h.draft(spec = ScheduleSpec("2026-01-05", "12:00", "UTC", frequency = Frequency.DAILY)))
        // Horizon is two days from 10:00 Monday: Monday 12:00 and Tuesday 12:00.
        assertEquals(2, h.executions.items.size)
        h.clock.advance(24 * HOUR_MS)
        h.materializer.materializeAll()
        assertEquals(3, h.executions.items.size)
        // Running it again changes nothing.
        h.materializer.materializeAll()
        assertEquals(3, h.executions.items.size)
    }

    @Test
    fun `send now queues a message for the current minute`() = runTest {
        val h = TestHarness()
        h.saveOk(h.draft(spec = ScheduleSpec("2026-01-05", "10:00", "UTC")), sendNow = true)
        h.processor().process()
        assertEquals(1, h.adapter.sent.size)
    }

    @Test
    fun `pause holds pending rows and resume restores them`() = runTest {
        val h = TestHarness()
        val saved = h.saveOk(h.draft()).schedule
        h.scheduleService.pause(saved.id)
        assertEquals(MessageStatus.PAUSED, h.executions.items.single().status)
        h.clock.advance(3 * HOUR_MS)
        h.processor().process()
        assertTrue(h.adapter.sent.isEmpty())

        h.scheduleService.resume(saved.id)
        h.processor().process()
        assertEquals(1, h.adapter.sent.size)
    }

    @Test
    fun `cancelling a schedule cancels its pending rows`() = runTest {
        val h = TestHarness()
        val saved = h.saveOk(h.draft()).schedule
        h.scheduleService.cancel(saved.id)
        h.clock.advance(3 * HOUR_MS)
        h.processor().process()
        assertTrue(h.adapter.sent.isEmpty())
        assertEquals(ScheduleState.CANCELLED, h.schedules.get(saved.id)?.state)
        assertEquals(MessageStatus.CANCELLED, h.executions.items.single().status)
    }

    @Test
    fun `time zone change re-plans follow-device schedules only`() = runTest {
        val h = TestHarness()
        val follow = h.saveOk(
            h.draft(spec = ScheduleSpec("2026-01-05", "12:00", "UTC", zoneMode = ZoneMode.FOLLOW_DEVICE), body = "Follow {{firstName}}"),
        ).schedule
        h.saveOk(h.draft(spec = ScheduleSpec("2026-01-05", "12:00", "UTC", zoneMode = ZoneMode.FIXED), body = "Fixed {{firstName}}"))

        h.clock.zoneId = ZoneId.of("Africa/Lagos")
        assertEquals(1, h.scheduleService.onTimeZoneChanged())

        val pending = h.executions.items.filter { it.status == MessageStatus.SCHEDULED }
        val followRow = pending.single { it.sourceId == follow.id }
        val fixedRow = pending.single { it.sourceId != follow.id }
        assertEquals(MONDAY_10AM_UTC + 1 * HOUR_MS, followRow.plannedAt) // 12:00 Lagos = 11:00 UTC
        assertEquals(MONDAY_10AM_UTC + 2 * HOUR_MS, fixedRow.plannedAt)
    }

    @Test
    fun `contact fields personalise each recipient`() = runTest {
        val h = TestHarness()
        h.contacts.upsert(
            Contact("c1", "Ada", "Okafor", identities = listOf(ChannelIdentity(ChannelId.DEMO, "ada")),
                customFields = mapOf("appointmentTime" to "9:30")),
        )
        h.saveOk(h.draft(body = "{{firstName}}, see you at {{appointmentTime}}", recipients = listOf(Recipient("ada", "Ada Okafor", "c1"))))
        assertEquals("Ada, see you at 9:30", h.executions.items.single().body)
    }

    @Test
    fun `due rows survive a restart and are sent once by the next process`() = runTest {
        val h = TestHarness()
        h.saveOk(h.draft())
        h.clock.advance(2 * HOUR_MS + MINUTE_MS)
        // Simulate a cold start: brand-new processor instances over the same persisted state.
        h.processor().process()
        h.processor().process()
        assertEquals(1, h.adapter.sent.size)
    }

    @Test
    fun `saving wakes the queue for the first send so send-now is immediate`() = runTest {
        val h = TestHarness()
        h.saveOk(h.draft(spec = ScheduleSpec("2026-01-05", "10:00", "UTC")), sendNow = true)
        val wakeAt = h.wake.requests.filterNotNull().minOrNull()
        assertTrue("expected a wake at or before now, got $wakeAt", wakeAt != null && wakeAt <= h.clock.now())
    }
}
