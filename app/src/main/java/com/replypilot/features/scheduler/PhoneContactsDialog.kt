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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.replypilot.core.contacts.PhoneContacts
import com.replypilot.core.design.Spacing
import com.replypilot.domain.model.ChannelId
import com.replypilot.domain.model.Recipient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Choose several phone contacts at once. Only people with an address on [channel] are listed. */
@Composable
fun PhoneContactsDialog(phone: PhoneContacts, channel: ChannelId, onDismiss: () -> Unit, onPicked: (List<Recipient>) -> Unit) {
    var people by remember { mutableStateOf<List<Recipient>?>(null) }
    LaunchedEffect(channel) {
        people = withContext(Dispatchers.IO) {
            phone.all(channel).mapNotNull { c -> phone.addressFor(c, channel)?.let { Recipient(it, c.name.ifBlank { it }) } }
                .distinctBy { it.address }
        }
    }
    var query by remember { mutableStateOf("") }
    var chosen by remember { mutableStateOf(setOf<String>()) }
    val shown = people.orEmpty().filter { query.isBlank() || it.displayName.contains(query.trim(), ignoreCase = true) || it.address.contains(query.trim()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Phone contacts") },
        text = {
            Column {
                OutlinedTextField(query, { query = it }, placeholder = { Text("Search name or number") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                when {
                    people == null -> CircularProgressIndicator(Modifier.padding(Spacing.lg))
                    people.orEmpty().isEmpty() -> Text("No contacts with a ${channel.displayName} address.", Modifier.padding(top = Spacing.sm))
                    else -> LazyColumn(Modifier.heightIn(max = 380.dp).padding(top = Spacing.xs)) {
                        items(shown, key = { it.address }) { person ->
                            val toggle = { chosen = if (person.address in chosen) chosen - person.address else chosen + person.address }
                            Row(Modifier.fillMaxWidth().clickable(onClick = toggle), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = person.address in chosen, onCheckedChange = { toggle() })
                                Column {
                                    Text(person.displayName, style = MaterialTheme.typography.bodyLarge)
                                    Text(person.address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onPicked(people.orEmpty().filter { it.address in chosen }); onDismiss() },
                enabled = chosen.isNotEmpty(),
            ) { Text(if (chosen.isEmpty()) "Add" else "Add ${chosen.size}") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
