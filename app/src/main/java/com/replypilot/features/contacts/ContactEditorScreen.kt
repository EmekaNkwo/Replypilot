package com.replypilot.features.contacts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.replypilot.core.design.BackTopBar
import com.replypilot.core.design.ConfirmDialog
import com.replypilot.core.design.Format
import com.replypilot.core.design.Notice
import com.replypilot.core.design.NoticeTone
import com.replypilot.core.design.SectionHeader
import com.replypilot.core.design.Spacing
import com.replypilot.domain.model.ChannelId
import com.replypilot.domain.scheduling.AddressRules
import com.replypilot.features.sequences.OptionDropdown
import com.replypilot.features.sequences.SwitchRow
import com.replypilot.navigation.AppNavigator

@Composable
fun ContactEditorScreen(nav: AppNavigator, id: String?) {
    val viewModel: ContactEditorViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var channelMenu by remember { mutableStateOf(false) }

    LaunchedEffect(id) { viewModel.load(id) }
    LaunchedEffect(state.finished) { if (state.finished) nav.back() }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.messageShown()
        }
    }

    Scaffold(
        topBar = {
            BackTopBar(if (state.isNew) "New contact" else "Edit contact", nav::back) {
                if (!state.isNew) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, contentDescription = "Delete contact") }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (state.notFound) {
            Notice("This contact no longer exists.", Modifier.padding(padding).padding(Spacing.lg))
            return@Scaffold
        }
        if (state.loading) return@Scaffold
        Column(
            Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            OutlinedTextField(
                value = state.firstName, onValueChange = { v -> viewModel.update { it.copy(firstName = v) } },
                label = { Text("First name") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                isError = state.showErrors && state.firstName.isBlank(),
            )
            OutlinedTextField(
                value = state.lastName, onValueChange = { v -> viewModel.update { it.copy(lastName = v) } },
                label = { Text("Last name") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )

            SectionHeader("Addresses")
            Notice("A phone number on WhatsApp isn't assumed to be the same person on SMS or Telegram — add each channel you use with this person.")
            state.identities.forEachIndexed { index, draft ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = draft.address,
                        onValueChange = { viewModel.setIdentity(index, draft.copy(address = it)) },
                        label = { Text(draft.channel.displayName) },
                        placeholder = { Text(AddressRules.hint(draft.channel)) },
                        isError = state.showErrors && draft.error != null,
                        supportingText = if (state.showErrors && draft.error != null) ({ Text(draft.error.orEmpty()) }) else null,
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { viewModel.removeIdentity(index) }) {
                        Icon(Icons.Filled.Close, contentDescription = "Remove ${draft.channel.displayName} address")
                    }
                }
            }
            Row {
                OutlinedButton(onClick = { channelMenu = true }) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.width(Spacing.sm))
                    Text("Add address")
                }
                DropdownMenu(expanded = channelMenu, onDismissRequest = { channelMenu = false }) {
                    ChannelId.entries.forEach { channel ->
                        DropdownMenuItem(text = { Text(channel.displayName) }, onClick = { viewModel.addIdentity(channel); channelMenu = false })
                    }
                }
            }
            val channels = state.identities.map { it.channel }.distinct()
            if (channels.size > 1) {
                OptionDropdown(
                    label = "Preferred channel",
                    options = channels,
                    selected = state.preferredChannel?.takeIf { it in channels } ?: channels.first(),
                    optionLabel = { it.displayName },
                    onSelect = { c -> viewModel.update { it.copy(preferredChannel = c) } },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            SectionHeader("Organise")
            ChipInput("Tags", state.tags, { v -> viewModel.update { it.copy(tags = v) } })
            ChipInput("Groups", state.groups, { v -> viewModel.update { it.copy(groups = v) } })
            OutlinedTextField(
                value = state.timeZone, onValueChange = { v -> viewModel.update { it.copy(timeZone = v) } },
                label = { Text("Time zone (optional)") }, placeholder = { Text("e.g. Africa/Lagos") },
                isError = !state.timeZoneValid, singleLine = true, modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("Sequence steps use this zone when set.") },
            )

            SectionHeader("Communication")
            SwitchRow(
                "Opted out",
                state.optedOutAt?.let { "Since ${Format.dateTime(it)}. No scheduled messages, sequences or auto-replies are sent." }
                    ?: "When on, ReplyPilot never sends this person anything.",
                state.optedOut,
                viewModel::setOptedOut,
            )
            OutlinedTextField(
                value = state.notes, onValueChange = { v -> viewModel.update { it.copy(notes = v.take(ContactEditorViewModel.MAX_NOTES)) } },
                label = { Text("Notes") }, minLines = 2, modifier = Modifier.fillMaxWidth(),
            )

            SectionHeader("Custom fields")
            Text(
                "Use these in messages as {{fieldName}}, e.g. appointmentDate.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.fields.forEachIndexed { index, field ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    OutlinedTextField(
                        value = field.key, onValueChange = { viewModel.setField(index, field.copy(key = it.trim())) },
                        label = { Text("Name") }, singleLine = true, modifier = Modifier.weight(1f),
                        isError = field.error != null,
                    )
                    OutlinedTextField(
                        value = field.value, onValueChange = { viewModel.setField(index, field.copy(value = it)) },
                        label = { Text("Value") }, singleLine = true, modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { viewModel.removeField(index) }) { Icon(Icons.Filled.Close, contentDescription = "Remove field") }
                }
                field.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
            OutlinedButton(onClick = viewModel::addField) { Text("Add field") }

            if (state.errors.isNotEmpty()) {
                Notice(state.errors.joinToString("\n"), tone = NoticeTone.ERROR, title = "Fix these before saving")
            }
            Button(onClick = viewModel::save, modifier = Modifier.fillMaxWidth()) { Text("Save contact") }
            Spacer(Modifier.height(Spacing.xl))
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = "Delete contact?",
            text = "${state.firstName} is removed from this device. Messages already scheduled to them keep their address.",
            confirmLabel = "Delete",
            onConfirm = viewModel::delete,
            onDismiss = { confirmDelete = false },
            destructive = true,
        )
    }
}
