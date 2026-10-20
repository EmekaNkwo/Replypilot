package com.replypilot.domain

import com.replypilot.domain.model.Frequency
import com.replypilot.domain.model.MonthlyMode
import com.replypilot.domain.model.ScheduleSpec
import com.replypilot.domain.model.ZoneMode
import com.replypilot.domain.scheduling.RecurrenceCalculator
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecurrenceCalculatorTest {
    private val utc = ZoneOffset.UTC

    private fun dates(spec: ScheduleSpec, count: Int = 10, zone: ZoneId = utc) =
        RecurrenceCalculator.occurrences(spec, zone).take(count).map { it.date.toString() }.toList()

    @Test
    fun `daily schedule repeats every interval days`() {
        val spec = ScheduleSpec("2026-01-01", "09:00", "UTC", frequency = Frequency.DAILY, interval = 2)
        assertEquals(listOf("2026-01-01", "2026-01-03", "2026-01-05"), dates(spec, 3))
    }

    @Test
    fun `weekly schedule uses selected weekdays and never precedes start date`() {
        // 2026-01-07 is a Wednesday; Monday of that week is before the start and must be skipped.
        val spec = ScheduleSpec("2026-01-07", "09:00", "UTC", frequency = Frequency.WEEKLY, weekdays = setOf(1, 3, 5))
        assertEquals(listOf("2026-01-07", "2026-01-09", "2026-01-12", "2026-01-14"), dates(spec, 4))
    }

    @Test
    fun `biweekly schedule skips alternate weeks`() {
        val spec = ScheduleSpec("2026-01-05", "09:00", "UTC", frequency = Frequency.WEEKLY, interval = 2, weekdays = setOf(1))
        assertEquals(listOf("2026-01-05", "2026-01-19", "2026-02-02"), dates(spec, 3))
    }

    @Test
    fun `monthly on the 31st clamps to the last day of shorter months`() {
        val spec = ScheduleSpec("2026-01-31", "09:00", "UTC", frequency = Frequency.MONTHLY)
        assertEquals(listOf("2026-01-31", "2026-02-28", "2026-03-31", "2026-04-30"), dates(spec, 4))
    }

    @Test
    fun `monthly nth weekday keeps the same ordinal weekday`() {
        // 2026-01-12 is the second Monday of January.
        val spec = ScheduleSpec("2026-01-12", "09:00", "UTC", frequency = Frequency.MONTHLY, monthlyMode = MonthlyMode.NTH_WEEKDAY)
        assertEquals(listOf("2026-01-12", "2026-02-09", "2026-03-09"), dates(spec, 3))
    }

    @Test
    fun `monthly last day follows month length`() {
        val spec = ScheduleSpec("2026-01-15", "09:00", "UTC", frequency = Frequency.MONTHLY, monthlyMode = MonthlyMode.LAST_DAY)
        assertEquals(listOf("2026-01-31", "2026-02-28", "2026-03-31"), dates(spec, 3))
    }

    @Test
    fun `yearly birthday on 29 February falls back to 28 February`() {
        val spec = ScheduleSpec("2028-02-29", "09:00", "UTC", frequency = Frequency.YEARLY)
        assertEquals(listOf("2028-02-29", "2029-02-28"), dates(spec, 2))
    }

    @Test
    fun `end date and max occurrences stop the series`() {
        val byEnd = ScheduleSpec("2026-01-01", "09:00", "UTC", frequency = Frequency.DAILY, endDate = "2026-01-03")
        assertEquals(3, dates(byEnd, 100).size)
        val byCount = ScheduleSpec("2026-01-01", "09:00", "UTC", frequency = Frequency.DAILY, maxOccurrences = 4)
        assertEquals(4, dates(byCount, 100).size)
    }

    @Test
    fun `skipped dates are not sent and do not count toward the limit`() {
        val spec = ScheduleSpec(
            "2026-01-01", "09:00", "UTC", frequency = Frequency.DAILY, maxOccurrences = 3, skipDates = setOf("2026-01-02"),
        )
        val occurrences = RecurrenceCalculator.occurrences(spec, utc).toList()
        assertEquals(listOf("2026-01-01", "2026-01-03", "2026-01-04"), occurrences.map { it.date.toString() })
        assertEquals(listOf(0, 1, 2), occurrences.map { it.index })
    }

    @Test
    fun `time inside a spring-forward gap moves forward instead of disappearing`() {
        val ny = ZoneId.of("America/New_York")
        val spec = ScheduleSpec("2026-03-07", "02:30", "America/New_York", frequency = Frequency.DAILY)
        val occurrences = RecurrenceCalculator.occurrences(spec, utc).take(3).toList()
        val local = occurrences.map { Instant.ofEpochMilli(it.at).atZone(ny).toLocalTime() }
        assertEquals(listOf(LocalTime.of(2, 30), LocalTime.of(3, 30), LocalTime.of(2, 30)), local)
    }

    @Test
    fun `time inside an autumn overlap is sent exactly once`() {
        val spec = ScheduleSpec("2026-11-01", "01:30", "America/New_York", frequency = Frequency.ONCE)
        assertEquals(1, RecurrenceCalculator.occurrences(spec, utc).count())
    }

    @Test
    fun `fixed zone ignores device zone while follow-device uses it`() {
        val lagos = ZoneId.of("Africa/Lagos")
        val fixed = ScheduleSpec("2026-01-05", "09:00", "UTC", zoneMode = ZoneMode.FIXED)
        val follow = fixed.copy(zoneMode = ZoneMode.FOLLOW_DEVICE)
        val fixedAt = RecurrenceCalculator.occurrences(fixed, lagos).first().at
        val followAt = RecurrenceCalculator.occurrences(follow, lagos).first().at
        assertEquals(LocalDate.of(2026, 1, 5).atTime(9, 0).toInstant(utc).toEpochMilli(), fixedAt)
        assertEquals(fixedAt - 3_600_000, followAt)
    }

    @Test
    fun `next returns null when every occurrence is in the past`() {
        val spec = ScheduleSpec("2020-01-01", "09:00", "UTC")
        assertNull(RecurrenceCalculator.next(spec, utc, MONDAY_10AM_UTC))
    }

    @Test
    fun `describe produces readable summaries`() {
        val spec = ScheduleSpec("2026-01-05", "09:00", "UTC", frequency = Frequency.WEEKLY, interval = 2, weekdays = setOf(1, 5), maxOccurrences = 6)
        assertEquals("Every 2 weeks on Mon, Fri at 09:00 for 6 occurrences", RecurrenceCalculator.describe(spec, java.util.Locale.UK))
    }

    @Test
    fun `yearly description names the date`() {
        val spec = ScheduleSpec("2026-10-19", "17:00", "UTC", frequency = Frequency.YEARLY)
        assertEquals("Every year on 19 Oct at 17:00", RecurrenceCalculator.describe(spec, java.util.Locale.UK))
    }
}
