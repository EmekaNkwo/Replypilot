package com.replypilot.domain

import com.replypilot.domain.model.MessageStateMachine
import com.replypilot.domain.model.MessageStatus
import com.replypilot.domain.model.TimeWindow
import com.replypilot.domain.scheduling.RetryPolicy
import com.replypilot.domain.scheduling.TimeWindows
import com.replypilot.domain.templates.TemplateRenderer
import java.time.LocalDateTime
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TemplateAndWindowTest {
    private fun at(day: Int, hour: Int, minute: Int = 0) =
        LocalDateTime.of(2026, 1, day, hour, minute).toInstant(ZoneOffset.UTC).toEpochMilli()

    @Test
    fun `renders variables and fallbacks`() {
        val result = TemplateRenderer.render("Hi {{firstName}}, {{ lastName | friend }}!", mapOf("firstName" to "Ada"))
        assertEquals("Hi Ada, friend!", result.text)
        assertTrue(result.isComplete)
    }

    @Test
    fun `unresolved placeholders are reported and left visible`() {
        val result = TemplateRenderer.render("See you on {{appointmentDate}}", emptyMap())
        assertEquals(setOf("appointmentDate"), result.unresolved)
        assertTrue(result.text.contains("{{appointmentDate}}"))
    }

    @Test
    fun `malformed braces are treated as unresolved`() {
        assertFalse(TemplateRenderer.render("Hello {{firstName", mapOf("firstName" to "Ada")).isComplete)
    }

    @Test
    fun `blank values do not count as resolved`() {
        assertFalse(TemplateRenderer.render("Hi {{firstName}}", mapOf("firstName" to " ")).isComplete)
    }

    @Test
    fun `overnight window covers late evening and early morning`() {
        val quiet = TimeWindow("22:00", "07:00")
        assertTrue(TimeWindows.contains(quiet, at(5, 23), ZoneOffset.UTC))
        assertTrue(TimeWindows.contains(quiet, at(6, 6, 59), ZoneOffset.UTC))
        assertFalse(TimeWindows.contains(quiet, at(6, 7), ZoneOffset.UTC))
        assertFalse(TimeWindows.contains(quiet, at(5, 12), ZoneOffset.UTC))
    }

    @Test
    fun `day filter applies to the evening an overnight window starts`() {
        // Friday-only window: Saturday 03:00 is still inside, Friday 03:00 is not.
        val window = TimeWindow("22:00", "07:00", days = setOf(5))
        assertTrue(TimeWindows.contains(window, at(10, 3), ZoneOffset.UTC))
        assertFalse(TimeWindows.contains(window, at(9, 3), ZoneOffset.UTC))
    }

    @Test
    fun `endOf returns the end of the active window`() {
        val quiet = TimeWindow("22:00", "07:00")
        assertEquals(at(6, 7), TimeWindows.endOf(quiet, at(5, 23), ZoneOffset.UTC))
        assertEquals(at(6, 7), TimeWindows.endOf(quiet, at(6, 2), ZoneOffset.UTC))
    }

    @Test
    fun `retry backoff doubles and is capped`() {
        assertEquals(60_000L, RetryPolicy.delayFor(1))
        assertEquals(120_000L, RetryPolicy.delayFor(2))
        assertEquals(3_600_000L, RetryPolicy.delayFor(20))
    }

    @Test
    fun `state machine forbids skipping straight to delivered`() {
        assertFalse(MessageStateMachine.canTransition(MessageStatus.SCHEDULED, MessageStatus.DELIVERED))
        assertTrue(MessageStateMachine.canTransition(MessageStatus.SUBMITTED, MessageStatus.DELIVERED))
        assertFalse(MessageStateMachine.canTransition(MessageStatus.CANCELLED, MessageStatus.SCHEDULED))
    }
}
