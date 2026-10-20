package com.replypilot.features.scheduler

import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.replypilot.core.design.AvailabilityPill
import com.replypilot.core.design.Format
import com.replypilot.core.design.ListCard
import com.replypilot.core.design.Notice
import com.replypilot.core.design.NoticeTone
import com.replypilot.core.design.Spacing
import com.replypilot.core.design.channelIcon
import com.replypilot.domain.model.ChannelCapability
import com.replypilot.domain.model.ChannelId
import com.replypilot.domain.model.ChannelStatus
import com.replypilot.domain.model.Frequency
import com.replypilot.domain.model.MonthlyMode
import com.replypilot.domain.model.QuietHoursBehavior
import com.replypilot.domain.model.Recipient
import com.replypilot.domain.model.ZoneMode
import com.replypilot.domain.scheduling.AddressRules
import com.replypilot.domain.scheduling.RecurrenceCalculator
import com.replypilot.domain.scheduling.Severity
import com.replypilot.domain.scheduling.ValidationField
import com.replypilot.domain.scheduling.ValidationIssue
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/** Errors (once the user has tried to save) and warnings (always) for one section. */
@Composable
fun FieldIssues(issues: List<ValidationIssue>, field: ValidationField, showErrors: Boolean) {
    issues.filter { it.field == field && (showErrors || it.severity == Severity.WARNING) }.forEach { issue ->
        Notice(issue.message, tone = if (issue.severity == Severity.ERROR) NoticeTone.ERROR else NoticeTone.WARNING)
    }
}

@Composable
fun ChannelSection(channels: List<ChannelStatus>, selected: ChannelId, onSelect: (ChannelId) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        items(channels, key = { it.id.name }) { status ->
            FilterChip(
                selected = status.id == selected,
                onClick = { onSelect(status.id) },
                label = { Text(status.id.displayName) },
                leadingIcon = { Icon(channelIcon(status.id), contentDescription = null, modifier = Modifier.size(18.dp)) },
            )
        }
    }
    val status = channels.firstOrNull { it.id == selected } ?: return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        AvailabilityPill(status.availability)
        Text(status.detail, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
    }
    val descriptor = status.descriptor
    when {
        descriptor.isSimulated -> Notice("Demo channel: nothing is really sent. Use it to see how scheduling works.", tone = NoticeTone.SIMULATED)
        !descriptor.supports(ChannelCapability.SEND_MESSAGE) && descriptor.supports(ChannelCapability.MANUAL_HANDOFF) -> Notice(
            "${descriptor.id.displayName} can't send scheduled messages unattended. At the scheduled time ReplyPilot alerts you and opens " +
                "${descriptor.id.displayName} with the message ready — one tap to send.",
            title = "Manual send",
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RecipientSection(
    channel: ChannelId,
    recipients: List<Recipient>,
    sources: @Composable () -> Unit,
    onAddManual: (String) -> String?,
    onRemove: (Recipient) -> Unit,
) {
    var manual by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        recipients.forEach { r ->
            InputChip(
                selected = false, onClick = { onRemove(r) },
                label = {
                    Text(
                        when {
                            r.isGroup -> "Group: ${r.address}"
                            r.displayName == r.address -> r.address
                            else -> "${r.displayName} · ${r.address}"
                        },
                    )
                },
                trailingIcon = { Icon(Icons.Filled.Close, contentDescription = "Remove ${r.displayName}", modifier = Modifier.size(16.dp)) },
            )
        }
    }
    sources()
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = manual,
            onValueChange = { manual = it; error = null },
            modifier = Modifier.weight(1f),
            label = { Text("Or type an address") },
            supportingText = { Text(error ?: AddressRules.hint(channel)) },
            isError = error != null,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = when (channel) {
                ChannelId.EMAIL -> KeyboardType.Email
                ChannelId.SMS, ChannelId.WHATSAPP, ChannelId.WHATSAPP_BUSINESS -> KeyboardType.Phone
                else -> KeyboardType.Text
            }),
        )
        TextButton(onClick = {
            error = onAddManual(manual)
            if (error == null) manual = ""
        }, enabled = manual.isNotBlank()) { Text("Add") }
    }
}

/** Shows placeholders that would reach the recipient unfilled in the error colour. */
fun highlightPlaceholders(text: String, color: Color): AnnotatedString = buildAnnotatedString {
    var last = 0
    Regex("""\{\{[^}]*\}\}|\{\{|\}\}""").findAll(text).forEach { match ->
        append(text.substring(last, match.range.first))
        withStyle(SpanStyle(color = color, fontWeight = FontWeight.Bold)) { append(match.value) }
        last = match.range.last + 1
    }
    append(text.substring(last))
}

@Composable
fun PreviewCard(title: String, text: String, unresolved: Set<String>, limit: Int?) {
    ListCard(onClick = null) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(highlightPlaceholders(text, MaterialTheme.colorScheme.error), style = MaterialTheme.typography.bodyMedium)
            val count = "${text.length}${limit?.let { " / $it" } ?: ""} characters"
            Text(
                if (unresolved.isEmpty()) count else "$count · missing: ${unresolved.joinToString { "{{$it}}" }}",
                style = MaterialTheme.typography.labelSmall,
                color = if (unresolved.isNotEmpty() || (limit != null && text.length > limit)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WhenSection(
    form: ComposerForm,
    onEdit: ((ComposerForm) -> ComposerForm) -> Unit,
    onPickDate: () -> Unit,
    onPickTime: () -> Unit,
    onPickZone: () -> Unit,
    onPickEndDate: () -> Unit,
    onAddSkipDate: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().toggleable(
            value = form.sendNow,
            role = Role.Switch,
            onValueChange = { checked -> onEdit { it.copy(sendNow = checked) } },
        ),
    ) {
        Column(Modifier.weight(1f)) {
            Text("Send now", style = MaterialTheme.typography.bodyLarge)
            Text("Queue it immediately instead of at a set time", style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = form.sendNow, onCheckedChange = null)
    }
    if (form.sendNow) return

    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        OutlinedButton(onClick = onPickDate) { Text(Format.date(form.date)) }
        OutlinedButton(onClick = onPickTime) { Text(form.time.toString()) }
        OutlinedButton(onClick = onPickZone) { Text(form.zoneId) }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        ZoneMode.entries.forEach { mode ->
            FilterChip(selected = form.zoneMode == mode, onClick = { onEdit { it.copy(zoneMode = mode) } }, label = { Text(mode.label) })
        }
    }
    if (form.zoneMode == ZoneMode.FOLLOW_DEVICE) {
        Text("The time stays ${form.time} wherever you travel; the time-zone choice above is ignored.", style = MaterialTheme.typography.bodySmall)
    }

    Text("Repeat", style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Frequency.entries.forEach { freq ->
            FilterChip(selected = form.frequency == freq, onClick = { onEdit { it.copy(frequency = freq) } }, label = { Text(freq.label) })
        }
    }
    if (form.frequency == Frequency.ONCE) return

    val unit = when (form.frequency) {
        Frequency.DAILY -> "day"
        Frequency.WEEKLY -> "week"
        Frequency.MONTHLY -> "month"
        else -> "year"
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text("Every")
        // Local text so the field can be emptied while typing; the form only takes valid numbers.
        var intervalText by remember(form.frequency) { mutableStateOf(form.interval.toString()) }
        OutlinedTextField(
            value = intervalText,
            onValueChange = { v ->
                intervalText = v.filter(Char::isDigit).take(2)
                intervalText.toIntOrNull()?.takeIf { it >= 1 }?.let { n -> onEdit { it.copy(interval = n) } }
            },
            isError = intervalText.toIntOrNull()?.let { it < 1 } ?: true,
            modifier = Modifier.size(width = 80.dp, height = 56.dp), singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        Text(if (form.interval == 1) unit else "${unit}s")
    }
    if (form.frequency == Frequency.WEEKLY) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            DayOfWeek.entries.forEach { day ->
                val selected = day.value in form.weekdays
                FilterChip(
                    selected = selected,
                    onClick = { onEdit { it.copy(weekdays = if (selected) it.weekdays - day.value else it.weekdays + day.value) } },
                    label = { Text(day.getDisplayName(TextStyle.SHORT, Locale.getDefault())) },
                )
            }
        }
        if (form.weekdays.isEmpty()) Text("No days picked: repeats on the start date's weekday.", style = MaterialTheme.typography.bodySmall)
    }
    if (form.frequency == Frequency.MONTHLY) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            MonthlyMode.entries.forEach { mode ->
                FilterChip(selected = form.monthlyMode == mode, onClick = { onEdit { it.copy(monthlyMode = mode) } }, label = { Text(mode.label) })
            }
        }
    }
    Text("Ends", style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.Center) {
        OutlinedButton(onClick = onPickEndDate) { Text(form.endDate?.let { "Until ${Format.date(it)}" } ?: "Set end date") }
        if (form.endDate != null) TextButton(onClick = { onEdit { it.copy(endDate = null) } }) { Text("No end date") }
    }
    OutlinedTextField(
        value = form.maxOccurrences,
        onValueChange = { v -> onEdit { it.copy(maxOccurrences = v.filter(Char::isDigit).take(4)) } },
        label = { Text("Stop after this many sends (optional)") },
        singleLine = true, modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
    Text("Skip dates (holidays)", style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        form.skipDates.sorted().forEach { date ->
            InputChip(
                selected = false, onClick = { onEdit { it.copy(skipDates = it.skipDates - date) } },
                label = { Text(Format.date(date)) },
                trailingIcon = { Icon(Icons.Filled.Close, contentDescription = "Remove ${Format.date(date)}", modifier = Modifier.size(16.dp)) },
            )
        }
        AssistChip(onClick = onAddSkipDate, label = { Text("Add date") })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DeliverySection(form: ComposerForm, onEdit: ((ComposerForm) -> ComposerForm) -> Unit) {
    Text("During quiet hours", style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        QuietHoursBehavior.entries.forEach { b ->
            FilterChip(selected = form.quietHours == b, onClick = { onEdit { it.copy(quietHours = b) } }, label = { Text(b.label) })
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        OutlinedTextField(
            value = form.expireMinutes,
            onValueChange = { v -> onEdit { it.copy(expireMinutes = v.filter(Char::isDigit).take(5)) } },
            label = { Text("Expire after (min)") }, singleLine = true, modifier = Modifier.weight(1f),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            supportingText = { Text("Not sent if this late") },
        )
        OutlinedTextField(
            value = form.maxRetries,
            onValueChange = { v -> onEdit { it.copy(maxRetries = v.filter(Char::isDigit).take(2)) } },
            label = { Text("Max retries") }, singleLine = true, modifier = Modifier.weight(1f),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            supportingText = { Text("0–10, with backoff") },
        )
    }
}

@Composable
fun UpcomingPreview(form: ComposerForm, upcoming: List<RecurrenceCalculator.Occurrence>, zone: ZoneId) {
    if (form.sendNow) {
        Text("Will be queued as soon as you save.", style = MaterialTheme.typography.bodyMedium)
        return
    }
    if (upcoming.isEmpty()) {
        Text("No future send times with these settings.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        return
    }
    Text(RecurrenceCalculator.describe(form.spec(System.currentTimeMillis())), style = MaterialTheme.typography.bodyMedium)
    upcoming.forEach { occurrence ->
        Text("• ${Format.dateTime(occurrence.at, zone)} (${zone.id})", style = MaterialTheme.typography.bodySmall)
    }
    if (LocalDate.now(zone) != LocalDate.now()) {
        Text("Times shown in ${zone.id}.", style = MaterialTheme.typography.labelSmall)
    }
}
