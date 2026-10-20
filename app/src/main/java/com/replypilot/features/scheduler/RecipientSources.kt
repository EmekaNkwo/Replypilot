package com.replypilot.features.scheduler

import android.Manifest
import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
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
import com.replypilot.core.contacts.PhoneGroup
import com.replypilot.core.design.Spacing
import com.replypilot.domain.model.ChannelId
import com.replypilot.domain.model.Contact
import com.replypilot.domain.model.Recipient
import com.replypilot.domain.model.RecipientKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class SourceDialog { PHONE_CONTACTS, PHONE_GROUPS, APP_GROUPS, CHAT_GROUP }

/**
 * Every way to add recipients without having to create them in ReplyPilot first:
 * a phone contact (system picker, no permission), a phone contact group (asks for contacts access),
 * contacts or groups saved in ReplyPilot, and — on channels that support it — a group chat by name.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RecipientSources(
    channel: ChannelId,
    groupChatsSupported: Boolean,
    savedContacts: List<Contact>,
    phone: PhoneContacts,
    onPickSaved: () -> Unit,
    onAdd: (List<Recipient>) -> Unit,
    onMessage: (String) -> Unit,
    /** Opens the channel's app to choose one or more chats. */
    onPickChat: (() -> Unit)? = null,
) {
    var dialog by remember { mutableStateOf<SourceDialog?>(null) }
    val phoneUsable = channel != ChannelId.INSTAGRAM && channel != ChannelId.MESSENGER

    val pickContact = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        if (result.resultCode != Activity.RESULT_OK || uri == null) return@rememberLauncherForActivityResult
        val picked = phone.readPicked(uri)
        val address = picked?.let { phone.addressFor(it, channel) }
        if (picked == null || address == null) onMessage("That contact has no ${channel.displayName} address.")
        else onAdd(listOf(Recipient(address, picked.name.ifBlank { address })))
    }
    // Which dialog to open once contacts access is granted.
    var afterPermission by remember { mutableStateOf(SourceDialog.PHONE_CONTACTS) }
    val askContacts = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        when {
            granted -> dialog = afterPermission
            afterPermission == SourceDialog.PHONE_CONTACTS -> {
                onMessage("Contacts access wasn't allowed, so you can pick one contact at a time.")
                pickContact.launch(phone.pickIntent(channel))
            }
            else -> onMessage("Contacts access wasn't allowed. You can still pick single contacts.")
        }
    }
    // Several contacts at once needs contacts access; without it, the system picker still allows one at a time.
    val choosePhoneContacts = {
        if (phone.hasPermission()) dialog = SourceDialog.PHONE_CONTACTS
        else { afterPermission = SourceDialog.PHONE_CONTACTS; askContacts.launch(Manifest.permission.READ_CONTACTS) }
    }

    // The primary way to choose: inside the channel itself (its chats), or the phone's Contacts for SMS and Email.
    when {
        onPickChat != null -> Column(Modifier.fillMaxWidth()) {
            FilledTonalButton(onClick = onPickChat, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Forum, contentDescription = null)
                Text("  Choose chats in ${channel.displayName}")
            }
            Text(
                "Open one chat or several, then tap Done to come back.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        channel == ChannelId.SMS || channel == ChannelId.EMAIL -> FilledTonalButton(
            onClick = choosePhoneContacts, modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Filled.Contacts, contentDescription = null)
            Text("  Choose in Contacts")
        }
    }
    Text("Or:", style = MaterialTheme.typography.labelMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        if (phoneUsable) {
            AssistChip(
                onClick = choosePhoneContacts,
                label = { Text("Phone contacts") },
                leadingIcon = { Icon(Icons.Filled.PersonAdd, contentDescription = null) },
            )
            AssistChip(
                onClick = {
                    if (phone.hasPermission()) dialog = SourceDialog.PHONE_GROUPS
                    else { afterPermission = SourceDialog.PHONE_GROUPS; askContacts.launch(Manifest.permission.READ_CONTACTS) }
                },
                label = { Text("Phone group") },
                leadingIcon = { Icon(Icons.Filled.Groups, contentDescription = null) },
            )
        }
        AssistChip(onClick = onPickSaved, label = { Text("Saved in ReplyPilot") }, leadingIcon = { Icon(Icons.Filled.Contacts, contentDescription = null) })
        if (savedContacts.any { it.groups.isNotEmpty() }) {
            AssistChip(onClick = { dialog = SourceDialog.APP_GROUPS }, label = { Text("ReplyPilot group") }, leadingIcon = { Icon(Icons.Filled.Groups, contentDescription = null) })
        }
        if (groupChatsSupported) {
            AssistChip(
                onClick = { dialog = SourceDialog.CHAT_GROUP },
                label = { Text("${channel.displayName} group") },
                leadingIcon = { Icon(Icons.Filled.Forum, contentDescription = null) },
            )
        }
    }

    when (dialog) {
        SourceDialog.PHONE_CONTACTS -> PhoneContactsDialog(phone, channel, onDismiss = { dialog = null }) { added -> onAdd(added) }
        SourceDialog.PHONE_GROUPS -> PhoneGroupDialog(phone, channel, onDismiss = { dialog = null }) { added, skipped ->
            onAdd(added)
            if (skipped > 0) onMessage("$skipped member${if (skipped == 1) "" else "s"} skipped: no ${channel.displayName} address.")
        }
        SourceDialog.APP_GROUPS -> AppGroupDialog(savedContacts, channel, onDismiss = { dialog = null }) { onAdd(it) }
        SourceDialog.CHAT_GROUP -> ChatGroupDialog(channel, onDismiss = { dialog = null }) { name ->
            onAdd(listOf(Recipient(name, name, kind = RecipientKind.GROUP)))
        }
        null -> Unit
    }
}

@Composable
private fun PhoneGroupDialog(phone: PhoneContacts, channel: ChannelId, onDismiss: () -> Unit, onPicked: (List<Recipient>, Int) -> Unit) {
    var groups by remember { mutableStateOf<List<PhoneGroup>?>(null) }
    LaunchedEffect(Unit) { groups = withContext(Dispatchers.IO) { phone.groups() } }
    var loading by remember { mutableStateOf(false) }
    var chosen by remember { mutableStateOf<PhoneGroup?>(null) }
    LaunchedEffect(chosen) {
        val group = chosen ?: return@LaunchedEffect
        loading = true
        val members = withContext(Dispatchers.IO) { phone.members(group, channel) }
        val recipients = members.mapNotNull { m -> phone.addressFor(m, channel)?.let { Recipient(it, m.name.ifBlank { it }) } }
        onPicked(recipients, members.size - recipients.size)
        onDismiss()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Phone contact groups") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                val list = groups
                when {
                    list == null || loading -> CircularProgressIndicator(Modifier.padding(Spacing.lg))
                    list.isEmpty() -> Text("Your phone has no contact groups (labels) with members.", style = MaterialTheme.typography.bodyMedium)
                    else -> LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(list, key = { it.title }) { group ->
                            Column(Modifier.fillMaxWidth().clickable { chosen = group }.padding(vertical = Spacing.sm)) {
                                Text(group.title, style = MaterialTheme.typography.bodyLarge)
                                Text("${group.memberCount} member${if (group.memberCount == 1) "" else "s"}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                Text("Members are added as individual recipients (each gets their own message).", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun AppGroupDialog(contacts: List<Contact>, channel: ChannelId, onDismiss: () -> Unit, onPicked: (List<Recipient>) -> Unit) {
    val groups = contacts.flatMap { it.groups }.distinct().sorted()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("ReplyPilot groups") },
        text = {
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                items(groups, key = { it }) { group ->
                    val members = contacts.filter { group in it.groups && !it.optedOut && it.addressOn(channel) != null }
                    Column(
                        Modifier.fillMaxWidth().clickable(enabled = members.isNotEmpty()) {
                            onPicked(members.mapNotNull { c -> c.addressOn(channel)?.let { Recipient(it, c.displayName, c.id) } })
                            onDismiss()
                        }.padding(vertical = Spacing.sm),
                    ) {
                        Text(group, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            if (members.isEmpty()) "No members with a ${channel.displayName} address" else "${members.size} reachable on ${channel.displayName} (opted-out excluded)",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun ChatGroupDialog(channel: ChannelId, onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Send to a ${channel.displayName} group") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(
                    "Type the group's name exactly as it appears in ${channel.displayName}. With Auto-send on, ReplyPilot finds the group by name " +
                        "and sends one message into it; without it, you'll get a reminder to paste it in.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(name, { name = it.take(100) }, label = { Text("Group name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { onAdd(name.trim()); onDismiss() }, enabled = name.isNotBlank()) { Text("Add group") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Shown after returning from the messaging app. One chat: confirm or correct its name. Several: untick any that
 * shouldn't be added. Names must match what the app shows, because chats are found by name at send time.
 */
@Composable
fun ConfirmChatsDialog(channelName: String, names: List<String>, onConfirm: (List<String>) -> Unit, onDismiss: () -> Unit) {
    var single by remember(names) { mutableStateOf(names.first()) }
    var kept by remember(names) { mutableStateOf(names.toSet()) }
    val result = if (names.size == 1) listOf(single.trim()).filter { it.isNotEmpty() } else names.filter { it in kept }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (names.size == 1) "Add this $channelName chat?" else "Add ${names.size} $channelName chats?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(
                    "ReplyPilot finds each chat by its name when it's time to send, so names must match what $channelName shows.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (names.size == 1) {
                    OutlinedTextField(single, { single = it.take(100) }, label = { Text("Chat name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                } else {
                    LazyColumn(Modifier.heightIn(max = 320.dp)) {
                        items(names, key = { it }) { name ->
                            val toggle = { kept = if (name in kept) kept - name else kept + name }
                            Row(Modifier.fillMaxWidth().clickable(onClick = toggle), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = name in kept, onCheckedChange = { toggle() })
                                Text(name, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(result) }, enabled = result.isNotEmpty()) {
                Text(if (result.size <= 1) "Add chat" else "Add ${result.size} chats")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
