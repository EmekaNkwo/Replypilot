package com.replypilot.domain.scheduling

import com.replypilot.domain.model.TimeWindow
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

object TimeWindows {
    fun parseTime(value: String): LocalTime? = runCatching { LocalTime.parse(value) }.getOrNull()
    fun parseDate(value: String): LocalDate? = runCatching { LocalDate.parse(value) }.getOrNull()

    /**
     * True when [at] falls inside the window. For an overnight window (22:00–07:00) the
     * early-morning part belongs to the previous day, so day filters apply to the evening it started.
     */
    fun contains(window: TimeWindow, at: Long, zone: ZoneId): Boolean {
        val start = parseTime(window.start) ?: return false
        val end = parseTime(window.end) ?: return false
        val local = Instant.ofEpochMilli(at).atZone(zone)
        val time = local.toLocalTime()
        val day = local.dayOfWeek.value
        if (start == end) return day in window.days
        return if (start < end) {
            day in window.days && time >= start && time < end
        } else {
            val previousDay = local.minusDays(1).dayOfWeek.value
            (time >= start && day in window.days) || (time < end && previousDay in window.days)
        }
    }

    /** The first instant after [at] when the window is no longer active (used to defer sends past quiet hours). */
    fun endOf(window: TimeWindow, at: Long, zone: ZoneId): Long {
        if (!contains(window, at, zone)) return at
        val end = parseTime(window.end) ?: return at
        val local = Instant.ofEpochMilli(at).atZone(zone)
        var candidate: ZonedDateTime = ZonedDateTime.of(local.toLocalDate(), end, zone)
        if (!candidate.isAfter(local)) candidate = ZonedDateTime.of(local.toLocalDate().plusDays(1), end, zone)
        return candidate.toInstant().toEpochMilli()
    }

    fun startOfNextDay(at: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(at).atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

    fun startOfDay(at: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(at).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
}
