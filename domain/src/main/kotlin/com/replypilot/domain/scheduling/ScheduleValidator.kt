package com.replypilot.domain.scheduling

import com.replypilot.domain.model.Attachment
import com.replypilot.domain.model.ChannelCapability
import com.replypilot.domain.model.ChannelId
import com.replypilot.domain.model.ChannelStatus
import com.replypilot.domain.model.Frequency
import com.replypilot.domain.model.Recipient
import com.replypilot.domain.model.ScheduleSpec
import com.replypilot.domain.model.ScheduleState
import com.replypilot.domain.model.ScheduledMessage
import com.replypilot.domain.templates.TemplateRenderer
import java.time.ZoneId

enum class Severity { ERROR, WARNING }

enum class ValidationField { RECIPIENTS, CHANNEL, BODY, ATTACHMENTS, SCHEDULE, GENERAL }

data class ValidationIssue(val field: ValidationField, val severity: Severity, val message: String)

val List<ValidationIssue>.hasErrors: Boolean get() = any { it.severity == Severity.ERROR }

/** Pre-flight checks run before a schedule is saved. Nothing reaches the queue without passing these. */
class ScheduleValidator(
    private val maxRecipients: Int = MAX_RECIPIENTS,
) {
    fun validate(
        draft: ScheduledMessage,
        channel: ChannelStatus?,
        variablesFor: (Recipient) -> Map<String, String>,
        existing: List<ScheduledMessage>,
        now: Long,
        deviceZone: ZoneId,
    ): List<ValidationIssue> = buildList {
        addAll(validateChannel(channel))
        addAll(validateRecipients(draft))
        addAll(validateGroups(draft, channel))
        addAll(validateBody(draft, channel, variablesFor))
        addAll(validateAttachments(draft, channel))
        addAll(validateSpec(draft.spec, now, deviceZone))
        addAll(detectDuplicate(draft, existing, deviceZone, now))
    }

    private fun validateGroups(draft: ScheduledMessage, channel: ChannelStatus?): List<ValidationIssue> {
        if (draft.recipients.none { it.isGroup } || channel == null) return emptyList()
        return if (channel.descriptor.supports(ChannelCapability.GROUP_CONVERSATIONS)) emptyList()
        else listOf(ValidationIssue(ValidationField.RECIPIENTS, Severity.ERROR, "${channel.descriptor.id.displayName} can't send to group chats."))
    }

    private fun validateChannel(channel: ChannelStatus?): List<ValidationIssue> {
        if (channel == null) return listOf(ValidationIssue(ValidationField.CHANNEL, Severity.ERROR, "Choose a channel."))
        val descriptor = channel.descriptor
        val canSend = descriptor.supports(ChannelCapability.SEND_MESSAGE)
        val canHandoff = descriptor.supports(ChannelCapability.MANUAL_HANDOFF)
        return when {
            !canSend && !canHandoff -> listOf(
                ValidationIssue(ValidationField.CHANNEL, Severity.ERROR, "${descriptor.id.displayName} can't send scheduled messages."),
            )
            !canSend -> listOf(
                ValidationIssue(
                    ValidationField.CHANNEL, Severity.WARNING,
                    "${descriptor.id.displayName} can't send unattended. At the scheduled time you'll be asked to send it yourself.",
                ),
            )
            !channel.availability.isUsable -> listOf(
                ValidationIssue(ValidationField.CHANNEL, Severity.WARNING, "${descriptor.id.displayName}: ${channel.detail}"),
            )
            else -> emptyList()
        }
    }

    private fun validateRecipients(draft: ScheduledMessage): List<ValidationIssue> = buildList {
        if (draft.recipients.isEmpty()) add(ValidationIssue(ValidationField.RECIPIENTS, Severity.ERROR, "Add at least one recipient."))
        if (draft.recipients.size > maxRecipients) {
            add(ValidationIssue(ValidationField.RECIPIENTS, Severity.ERROR, "At most $maxRecipients recipients per schedule."))
        }
        val normalized = draft.recipients.map { AddressRules.recipientKey(it, draft.channel) }
        if (normalized.size != normalized.toSet().size) {
            add(ValidationIssue(ValidationField.RECIPIENTS, Severity.ERROR, "The same recipient is listed more than once."))
        }
        draft.recipients.filterNot { AddressRules.isValid(it, draft.channel) }.forEach {
            val message = if (it.isNamedChat) "Chat name \"${it.address}\" is empty or too long."
            else "\"${it.address}\" isn't a valid ${draft.channel.displayName} address. ${AddressRules.hint(draft.channel)}."
            add(ValidationIssue(ValidationField.RECIPIENTS, Severity.ERROR, message))
        }
    }

    private fun validateBody(
        draft: ScheduledMessage,
        channel: ChannelStatus?,
        variablesFor: (Recipient) -> Map<String, String>,
    ): List<ValidationIssue> = buildList {
        if (draft.body.isBlank()) {
            // A file on its own is a valid message; the text is then just an optional caption.
            if (draft.attachments.isEmpty()) add(ValidationIssue(ValidationField.BODY, Severity.ERROR, "Write a message or attach a file."))
            return@buildList
        }
        val max = channel?.descriptor?.maxMessageLength
        draft.recipients.forEach { recipient ->
            val rendered = TemplateRenderer.render(draft.body, variablesFor(recipient))
            if (!rendered.isComplete) {
                add(
                    ValidationIssue(
                        ValidationField.BODY, Severity.ERROR,
                        "${recipient.displayName}: no value for ${rendered.unresolved.joinToString { "{{$it}}" }}. Add the value or a fallback like {{firstName|there}}.",
                    ),
                )
            } else if (max != null && rendered.text.length > max) {
                add(ValidationIssue(ValidationField.BODY, Severity.ERROR, "${recipient.displayName}: message is ${rendered.text.length} characters; the limit is $max."))
            }
        }
    }

    private fun validateAttachments(draft: ScheduledMessage, channel: ChannelStatus?): List<ValidationIssue> = buildList {
        val files = draft.attachments
        if (files.isEmpty()) return@buildList
        if (channel != null && !channel.descriptor.supports(ChannelCapability.ATTACHMENTS)) {
            add(ValidationIssue(ValidationField.ATTACHMENTS, Severity.ERROR, "${channel.descriptor.id.displayName} can't send attachments from ReplyPilot."))
        }
        if (files.size > Attachment.MAX_FILES) {
            add(ValidationIssue(ValidationField.ATTACHMENTS, Severity.ERROR, "At most ${Attachment.MAX_FILES} files per message."))
        }
        files.filter { it.sizeBytes > Attachment.MAX_FILE_BYTES }.forEach {
            add(ValidationIssue(ValidationField.ATTACHMENTS, Severity.ERROR, "\"${it.fileName}\" is larger than ${Attachment.MAX_FILE_BYTES / (1024 * 1024)} MB."))
        }
        if (draft.channel == ChannelId.WHATSAPP && files.any { it.isMedia } && files.any { !it.isMedia }) {
            add(
                ValidationIssue(
                    ValidationField.ATTACHMENTS, Severity.WARNING,
                    "WhatsApp takes photos/videos and documents in separate shares. Auto-send sends each file in turn; " +
                        "if you send it yourself, send the photos and the documents as two messages.",
                ),
            )
        }
        if (files.size > 1 && draft.channel == ChannelId.SMS) {
            add(ValidationIssue(ValidationField.ATTACHMENTS, Severity.WARNING, "Many SMS apps send only one picture per MMS; extra files may be dropped."))
        }
    }

    fun validateSpec(spec: ScheduleSpec, now: Long, deviceZone: ZoneId): List<ValidationIssue> = buildList {
        val start = TimeWindows.parseDate(spec.startDate)
        if (start == null) add(ValidationIssue(ValidationField.SCHEDULE, Severity.ERROR, "Pick a valid start date."))
        if (TimeWindows.parseTime(spec.time) == null) add(ValidationIssue(ValidationField.SCHEDULE, Severity.ERROR, "Pick a valid time."))
        if (runCatching { ZoneId.of(spec.timeZone) }.isFailure) add(ValidationIssue(ValidationField.SCHEDULE, Severity.ERROR, "Unknown time zone \"${spec.timeZone}\"."))
        if (spec.interval < 1) add(ValidationIssue(ValidationField.SCHEDULE, Severity.ERROR, "Repeat interval must be at least 1."))
        if (spec.frequency == Frequency.WEEKLY && spec.weekdays.any { it !in 1..7 }) {
            add(ValidationIssue(ValidationField.SCHEDULE, Severity.ERROR, "Weekdays must be Monday to Sunday."))
        }
        val end = spec.endDate?.let(TimeWindows::parseDate)
        if (spec.endDate != null && end == null) add(ValidationIssue(ValidationField.SCHEDULE, Severity.ERROR, "Pick a valid end date."))
        if (start != null && end != null && end.isBefore(start)) add(ValidationIssue(ValidationField.SCHEDULE, Severity.ERROR, "End date is before the start date."))
        if (spec.maxOccurrences != null && spec.maxOccurrences < 1) add(ValidationIssue(ValidationField.SCHEDULE, Severity.ERROR, "Occurrence limit must be at least 1."))
        if (isEmpty() && RecurrenceCalculator.next(spec, deviceZone, now) == null) {
            add(ValidationIssue(ValidationField.SCHEDULE, Severity.ERROR, "This schedule has no future send times."))
        }
    }

    private fun detectDuplicate(
        draft: ScheduledMessage,
        existing: List<ScheduledMessage>,
        deviceZone: ZoneId,
        now: Long,
    ): List<ValidationIssue> {
        val addresses = draft.recipients.map { AddressRules.recipientKey(it, draft.channel) }.toSet()
        val next = RecurrenceCalculator.next(draft.spec, deviceZone, now)?.at ?: return emptyList()
        val duplicate = existing.firstOrNull { other ->
            other.id != draft.id &&
                other.state in setOf(ScheduleState.ACTIVE, ScheduleState.PAUSED, ScheduleState.COMPLETED) &&
                other.channel == draft.channel &&
                other.body.trim() == draft.body.trim() &&
                other.attachments.map { it.id } == draft.attachments.map { it.id } &&
                other.recipients.map { AddressRules.recipientKey(it, other.channel) }.toSet() == addresses &&
                RecurrenceCalculator.next(other.spec, deviceZone, now)?.at == next
        } ?: return emptyList()
        return listOf(
            ValidationIssue(ValidationField.GENERAL, Severity.ERROR, "An identical message is already scheduled (\"${duplicate.title}\")."),
        )
    }

    companion object {
        const val MAX_RECIPIENTS = 50
    }
}
