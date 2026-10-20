package com.replypilot.domain.scheduling

import com.replypilot.domain.model.Frequency
import com.replypilot.domain.model.MonthlyMode
import com.replypilot.domain.model.ScheduleSpec
import com.replypilot.domain.model.ZoneMode
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/**
 * Expands a [ScheduleSpec] into concrete instants.
 *
 * Wall-clock times are resolved with [ZonedDateTime.of], which deliberately handles daylight-saving:
 * a time inside a spring-forward gap moves forward by the gap length (02:30 → 03:30), and a time in an
 * autumn overlap uses the earlier offset, so a recurring message is sent once, never twice.
 */
object RecurrenceCalculator {
    /** [index] counts real occurrences from the start of the series (skipped dates are not counted). */
    data class Occurrence(val index: Int, val date: LocalDate, val at: Long)

    private const val MAX_CANDIDATES = 20_000

    fun zoneFor(spec: ScheduleSpec, deviceZone: ZoneId): ZoneId = when (spec.zoneMode) {
        ZoneMode.FIXED -> runCatching { ZoneId.of(spec.timeZone) }.getOrDefault(deviceZone)
        ZoneMode.FOLLOW_DEVICE -> deviceZone
    }

    /** Occurrences with `after < at <= until`, in order. */
    fun occurrencesBetween(spec: ScheduleSpec, deviceZone: ZoneId, after: Long, until: Long): List<Occurrence> =
        occurrences(spec, deviceZone)
            .dropWhile { it.at <= after }
            .takeWhile { it.at <= until }
            .toList()

    fun upcoming(spec: ScheduleSpec, deviceZone: ZoneId, after: Long, count: Int): List<Occurrence> =
        occurrences(spec, deviceZone).dropWhile { it.at <= after }.take(count).toList()

    fun next(spec: ScheduleSpec, deviceZone: ZoneId, after: Long): Occurrence? =
        upcoming(spec, deviceZone, after, 1).firstOrNull()

    fun occurrences(spec: ScheduleSpec, deviceZone: ZoneId): Sequence<Occurrence> {
        val start = TimeWindows.parseDate(spec.startDate) ?: return emptySequence()
        val time = TimeWindows.parseTime(spec.time) ?: return emptySequence()
        val end = spec.endDate?.let(TimeWindows::parseDate)
        val zone = zoneFor(spec, deviceZone)
        val skip = spec.skipDates.mapNotNull(TimeWindows::parseDate).toSet()
        val interval = spec.interval.coerceAtLeast(1)

        return sequence {
            var index = 0
            for (date in candidateDates(spec, start, interval).take(MAX_CANDIDATES)) {
                if (end != null && date.isAfter(end)) break
                if (spec.maxOccurrences != null && index >= spec.maxOccurrences) break
                if (date in skip) continue
                yield(Occurrence(index, date, toEpoch(date, time, zone)))
                index++
            }
        }
    }

    fun toEpoch(date: LocalDate, time: LocalTime, zone: ZoneId): Long =
        ZonedDateTime.of(date, time, zone).toInstant().toEpochMilli()

    private fun candidateDates(spec: ScheduleSpec, start: LocalDate, interval: Int): Sequence<LocalDate> =
        when (spec.frequency) {
            Frequency.ONCE -> sequenceOf(start)
            Frequency.DAILY -> generateSequence(start) { it.plusDays(interval.toLong()) }
            Frequency.WEEKLY -> weekly(start, interval, spec.weekdays)
            Frequency.MONTHLY -> generateSequence(0L) { it + interval }
                .map { monthly(start, start.plusMonths(it), spec.monthlyMode) }
            Frequency.YEARLY -> generateSequence(0L) { it + interval }.map { start.plusYears(it) }
        }

    private fun weekly(start: LocalDate, interval: Int, weekdays: Set<Int>): Sequence<LocalDate> {
        val days = weekdays.filter { it in 1..7 }.sorted().ifEmpty { listOf(start.dayOfWeek.value) }
        val firstMonday = start.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return generateSequence(firstMonday) { it.plusWeeks(interval.toLong()) }
            .flatMap { monday -> days.asSequence().map { monday.plusDays((it - 1).toLong()) } }
            .filter { !it.isBefore(start) }
    }

    /** Days that don't exist in a month (31st in April) clamp to the month's last day. */
    private fun monthly(start: LocalDate, month: LocalDate, mode: MonthlyMode): LocalDate = when (mode) {
        MonthlyMode.DAY_OF_MONTH -> month.withDayOfMonth(minOf(start.dayOfMonth, month.lengthOfMonth()))
        MonthlyMode.LAST_DAY -> month.with(TemporalAdjusters.lastDayOfMonth())
        MonthlyMode.NTH_WEEKDAY -> {
            val ordinal = (start.dayOfMonth - 1) / 7 + 1
            val candidate = month.with(TemporalAdjusters.dayOfWeekInMonth(ordinal, start.dayOfWeek))
            if (candidate.month != month.month) month.with(TemporalAdjusters.lastInMonth(start.dayOfWeek)) else candidate
        }
    }

    fun describe(spec: ScheduleSpec, locale: Locale = Locale.getDefault()): String {
        val n = spec.interval.coerceAtLeast(1)
        fun every(unit: String) = if (n == 1) "Every $unit" else "Every $n ${unit}s"
        val start = TimeWindows.parseDate(spec.startDate)
        val base = when (spec.frequency) {
            Frequency.ONCE -> "Once on ${spec.startDate}"
            Frequency.DAILY -> every("day")
            Frequency.WEEKLY -> {
                val days = spec.weekdays.sorted().ifEmpty { listOfNotNull(start?.dayOfWeek?.value) }
                val names = days.joinToString(", ") { DayOfWeek.of(it).getDisplayName(TextStyle.SHORT, locale) }
                "${every("week")} on $names"
            }
            Frequency.MONTHLY -> {
                val detail = when (spec.monthlyMode) {
                    MonthlyMode.DAY_OF_MONTH -> start?.let { "on day ${it.dayOfMonth}" }
                    MonthlyMode.LAST_DAY -> "on the last day"
                    MonthlyMode.NTH_WEEKDAY -> start?.let {
                        val ordinal = listOf("first", "second", "third", "fourth", "last")[((it.dayOfMonth - 1) / 7).coerceAtMost(4)]
                        "on the $ordinal ${it.dayOfWeek.getDisplayName(TextStyle.FULL, locale)}"
                    }
                }
                listOfNotNull(every("month"), detail).joinToString(" ")
            }
            Frequency.YEARLY -> listOfNotNull(
                every("year"),
                start?.let { "on ${it.dayOfMonth} ${it.month.getDisplayName(TextStyle.SHORT, locale)}" },
            ).joinToString(" ")
        }
        val tail = buildList {
            add("at ${spec.time}")
            if (spec.frequency != Frequency.ONCE) {
                spec.endDate?.let { add("until $it") }
                spec.maxOccurrences?.let { add("for $it occurrence${if (it == 1) "" else "s"}") }
            }
            if (spec.skipDates.isNotEmpty()) add("skipping ${spec.skipDates.size} date${if (spec.skipDates.size == 1) "" else "s"}")
        }
        return (listOf(base) + tail).joinToString(" ")
    }

    fun daysBetween(a: LocalDate, b: LocalDate): Long = ChronoUnit.DAYS.between(a, b)
}
