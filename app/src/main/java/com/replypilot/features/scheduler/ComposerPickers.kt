package com.replypilot.features.scheduler

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.replypilot.core.design.Spacing
import com.replypilot.domain.model.ChannelId
import com.replypilot.domain.model.Contact
import com.replypilot.domain.model.MessageTemplate
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DatePickDialog(initial: LocalDate?, onPick: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    // Material's date picker works in UTC midnight millis.
    val state = rememberDatePickerState(initialSelectedDateMillis = (initial ?: LocalDate.now()).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                onDismiss()
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) { DatePicker(state = state) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimePickDialog(initial: LocalTime, onPick: (LocalTime) -> Unit, onDismiss: () -> Unit) {
    val state = rememberTimePickerState(initialHour = initial.hour, initialMinute = initial.minute)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Send time") },
        text = { TimePicker(state = state) },
        confirmButton = { TextButton(onClick = { onPick(LocalTime.of(state.hour, state.minute)); onDismiss() }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ZonePickDialog(current: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val all = remember { ZoneId.getAvailableZoneIds().filter { it.contains('/') || it == "UTC" }.sorted() }
    val suggested = listOf(ZoneId.systemDefault().id, "UTC", current).distinct()
    val matches = if (query.isBlank()) suggested + all.filterNot { it in suggested } else all.filter { it.contains(query.trim(), ignoreCase = true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Time zone") },
        text = {
            Column {
                OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("Search, e.g. Lagos") }, singleLine = true)
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(matches.take(300), key = { it }) { id ->
                        Text(
                            id + if (id == ZoneId.systemDefault().id) " (this device)" else "",
                            modifier = Modifier.fillMaxWidth().clickable { onPick(id); onDismiss() }.padding(vertical = 12.dp),
                            color = if (id == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
fun ContactPickDialog(
    contacts: List<Contact>,
    channel: ChannelId,
    selectedIds: Set<String>,
    onToggle: (Contact) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val reachable = contacts.filter { it.addressOn(channel) != null }
        .filter { query.isBlank() || it.displayName.contains(query.trim(), ignoreCase = true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose contacts") },
        text = {
            Column {
                Text(
                    "Showing contacts with a ${channel.displayName} address. Opted-out contacts can't be selected.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.sm), placeholder = { Text("Search") }, singleLine = true)
                if (reachable.isEmpty()) Text("No contacts on ${channel.displayName}. Add an address in Contacts or type one below.", style = MaterialTheme.typography.bodyMedium)
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(reachable, key = { it.id }) { contact ->
                        Row(
                            Modifier.fillMaxWidth().clickable(enabled = !contact.optedOut) { onToggle(contact) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = contact.id in selectedIds, onCheckedChange = { onToggle(contact) }, enabled = !contact.optedOut)
                            Column {
                                Text(contact.displayName, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    (contact.addressOn(channel).orEmpty()) + if (contact.optedOut) " · opted out" else "",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
fun TemplatePickDialog(templates: List<MessageTemplate>, onPick: (MessageTemplate) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Use a template") },
        text = {
            if (templates.isEmpty()) {
                Text("No templates yet. Create them under Automation → Templates.")
            } else {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(templates, key = { it.id }) { template ->
                        Column(Modifier.fillMaxWidth().clickable { onPick(template); onDismiss() }.padding(vertical = 10.dp)) {
                            Text(template.name, style = MaterialTheme.typography.titleSmall)
                            Text(template.category.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            Text(template.body, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                        }
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
