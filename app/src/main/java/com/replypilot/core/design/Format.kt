package com.replypilot.core.design

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.abs

/** Human-friendly time formatting shared by every screen. */
object Format {
    private val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
    private val date = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
    private val dayMonth = DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())

    fun time(at: Long, zone: ZoneId = ZoneId.systemDefault()): String = Instant.ofEpochMilli(at).atZone(zone).format(time)

    fun date(at: Long, zone: ZoneId = ZoneId.systemDefault()): String = Instant.ofEpochMilli(at).atZone(zone).format(date)

    fun date(value: LocalDate): String = value.format(date)

    /** "Today 09:00", "Tomorrow 14:30", "Thu 15 Oct 09:00", or a full date for far-off times. */
    fun dateTime(at: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val local = Instant.ofEpochMilli(at).atZone(zone)
        val today = LocalDate.now(zone)
        val day = when (local.toLocalDate()) {
            today -> "Today"
            today.plusDays(1) -> "Tomorrow"
            today.minusDays(1) -> "Yesterday"
            else -> if (abs(local.toLocalDate().toEpochDay() - today.toEpochDay()) < 180) local.format(dayMonth) else local.format(date)
        }
        return "$day ${local.format(time)}"
    }

    /** "in 5 min", "2 h ago", "just now". */
    fun relative(at: Long, now: Long = System.currentTimeMillis()): String {
        val diff = at - now
        val minutes = abs(diff) / 60_000
        val text = when {
            minutes < 1 -> return "just now"
            minutes < 60 -> "$minutes min"
            minutes < 48 * 60 -> "${minutes / 60} h"
            else -> "${minutes / (24 * 60)} d"
        }
        return if (diff > 0) "in $text" else "$text ago"
    }

    fun percent(value: Double?): String = value?.let { "${(it * 100).toInt()}%" } ?: "—"
}
