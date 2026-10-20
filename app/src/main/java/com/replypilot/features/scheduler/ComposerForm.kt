package com.replypilot.features.scheduler

import com.replypilot.domain.model.Attachment
import com.replypilot.domain.model.ChannelId
import com.replypilot.domain.model.Frequency
import com.replypilot.domain.model.MonthlyMode
import com.replypilot.domain.model.QuietHoursBehavior
import com.replypilot.domain.model.Recipient
import com.replypilot.domain.model.ScheduleSpec
import com.replypilot.domain.model.ScheduledMessage
import com.replypilot.domain.model.SendPolicy
import com.replypilot.domain.model.ZoneMode
import com.replypilot.domain.scheduling.ScheduleMaterializer
import com.replypilot.domain.scheduling.TimeWindows
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Everything the user edits in the composer, as plain immutable values. */
data class ComposerForm(
    val editingId: String = "",
    val original: ScheduledMessage? = null,
    val title: String = "",
    val channel: ChannelId = ChannelId.DEMO,
    val recipients: List<Recipient> = emptyList(),
    val body: String = "",
    /** Bumped when the body is replaced from outside the text field (template, assistant). */
    val bodyVersion: Int = 0,
    val templateId: String? = null,
    val attachments: List<Attachment> = emptyList(),
    val sendNow: Boolean = false,
    val date: LocalDate = LocalDate.now(),
    val time: LocalTime = LocalTime.of(9, 0),
    val zoneId: String = ZoneId.systemDefault().id,
    val zoneMode: ZoneMode = ZoneMode.FIXED,
    val frequency: Frequency = Frequency.ONCE,
    val interval: Int = 1,
    val weekdays: Set<Int> = emptySet(),
    val monthlyMode: MonthlyMode = MonthlyMode.DAY_OF_MONTH,
    val endDate: LocalDate? = null,
    val maxOccurrences: String = "",
    val skipDates: Set<LocalDate> = emptySet(),
    val quietHours: QuietHoursBehavior = QuietHoursBehavior.DEFER,
    val expireMinutes: String = "1440",
    val maxRetries: String = "3",
) {
    fun spec(now: Long): ScheduleSpec {
        if (sendNow) {
            val zone = runCatching { ZoneId.of(zoneId) }.getOrDefault(ZoneId.systemDefault())
            val local = Instant.ofEpochMilli(now).atZone(zone)
            return ScheduleSpec(local.toLocalDate().toString(), local.toLocalTime().withSecond(0).withNano(0).toString(), zone.id, ZoneMode.FIXED)
        }
        return ScheduleSpec(
            startDate = date.toString(),
            time = time.withSecond(0).withNano(0).toString(),
            timeZone = zoneId,
            zoneMode = zoneMode,
            frequency = frequency,
            interval = interval.coerceAtLeast(1),
            weekdays = if (frequency == Frequency.WEEKLY) weekdays else emptySet(),
            monthlyMode = monthlyMode,
            endDate = endDate?.takeIf { frequency != Frequency.ONCE }?.toString(),
            maxOccurrences = maxOccurrences.trim().toIntOrNull()?.takeIf { frequency != Frequency.ONCE },
            skipDates = if (frequency == Frequency.ONCE) emptySet() else skipDates.map { it.toString() }.toSet(),
        )
    }

    fun toDraft(now: Long): ScheduledMessage {
        val base = original ?: ScheduledMessage(id = "", title = "", channel = channel, recipients = emptyList(), body = "", spec = spec(now))
        return base.copy(
            id = editingId,
            title = title.trim().ifBlank { recipients.firstOrNull()?.let { "Message to ${it.displayName}" } ?: "Scheduled message" },
            channel = channel,
            recipients = recipients,
            body = body,
            templateId = templateId,
            attachments = attachments,
            spec = spec(now),
            policy = SendPolicy(
                expireAfterMinutes = (expireMinutes.trim().toIntOrNull() ?: 1440).coerceIn(MIN_EXPIRY, MAX_EXPIRY),
                maxRetries = (maxRetries.trim().toIntOrNull() ?: 3).coerceIn(0, ScheduleMaterializer.MAX_RETRIES),
                // "Send now" is an explicit instruction to send immediately, so quiet hours don't hold it back.
                quietHours = if (sendNow) QuietHoursBehavior.SEND_ANYWAY else quietHours,
            ),
            isDemo = original?.isDemo ?: (channel == ChannelId.DEMO),
        )
    }

    companion object {
        const val MIN_EXPIRY = 5
        const val MAX_EXPIRY = 7 * 24 * 60

        fun from(schedule: ScheduledMessage, keepId: Boolean): ComposerForm {
            val spec = schedule.spec
            return ComposerForm(
                editingId = if (keepId) schedule.id else "",
                original = if (keepId) schedule else null,
                title = schedule.title,
                channel = schedule.channel,
                recipients = schedule.recipients,
                body = schedule.body,
                templateId = schedule.templateId,
                attachments = schedule.attachments,
                date = TimeWindows.parseDate(spec.startDate) ?: LocalDate.now(),
                time = TimeWindows.parseTime(spec.time) ?: LocalTime.of(9, 0),
                zoneId = spec.timeZone,
                zoneMode = spec.zoneMode,
                frequency = spec.frequency,
                interval = spec.interval,
                weekdays = spec.weekdays,
                monthlyMode = spec.monthlyMode,
                endDate = spec.endDate?.let(TimeWindows::parseDate),
                maxOccurrences = spec.maxOccurrences?.toString().orEmpty(),
                skipDates = spec.skipDates.mapNotNull(TimeWindows::parseDate).toSet(),
                quietHours = schedule.policy.quietHours,
                expireMinutes = schedule.policy.expireAfterMinutes.toString(),
                maxRetries = schedule.policy.maxRetries.toString(),
            )
        }
    }
}
