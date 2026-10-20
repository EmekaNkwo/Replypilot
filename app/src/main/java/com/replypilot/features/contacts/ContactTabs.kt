package com.replypilot.features.contacts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.replypilot.core.design.ConfirmDialog
import com.replypilot.core.design.EmptyState
import com.replypilot.core.design.ListCard
import com.replypilot.core.design.Notice
import com.replypilot.core.design.SectionHeader
import com.replypilot.core.design.Spacing
import com.replypilot.domain.templates.KnownVariables
import com.replypilot.navigation.AppNavigator
import com.replypilot.navigation.Routes

@Composable
internal fun GroupsTab(state: ContactsState, viewModel: ContactsViewModel, nav: AppNavigator) {
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    var renaming by rememberSaveable { mutableStateOf<String?>(null) }
    var removing by rememberSaveable { mutableStateOf<String?>(null) }

    if (state.loaded && state.groups.isEmpty()) {
        EmptyState(
            icon = Icons.Filled.Groups,
            title = "No groups yet",
            body = "Add contacts to groups (like VIP or Clients) from the contact editor. Rules can then reply differently to each group.",
        )
        return
    }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        items(state.groups, key = { it.first }) { (group, members) ->
            ListCard(onClick = { expanded = if (expanded == group) null else group }) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(group, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        Text("${members.size} member${if (members.size == 1) "" else "s"}", style = MaterialTheme.typography.bodySmall)
                    }
                    if (expanded == group) {
                        members.forEach { contact ->
                            TextButton(onClick = { nav.navigate(Routes.contactEditor(contact.id)) }) { Text(contact.displayName) }
                        }
                        Row {
                            TextButton(onClick = { renaming = group }) { Text("Rename") }
                            TextButton(onClick = { removing = group }) { Text("Remove group") }
                        }
                    }
                }
            }
        }
    }

    renaming?.let { group ->
        var name by rememberSaveable(group) { mutableStateOf(group) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename group") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Group name") }) },
            confirmButton = {
                TextButton(onClick = { viewModel.renameGroup(group, name); renaming = null }, enabled = name.isNotBlank()) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }
    removing?.let { group ->
        ConfirmDialog(
            title = "Remove \"$group\"?",
            text = "The group is removed from its members. The contacts themselves are kept. Rules that target this group will stop matching.",
            confirmLabel = "Remove",
            onConfirm = { viewModel.removeGroup(group) },
            onDismiss = { removing = null },
            destructive = true,
        )
    }
}

@Composable
internal fun PersonalizationTab(state: ContactsState, viewModel: ContactsViewModel) {
    var business by rememberSaveable(state.businessName) { mutableStateOf(state.businessName) }
    var hours by rememberSaveable(state.supportHours) { mutableStateOf(state.supportHours) }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        item { SectionHeader("Business details") }
        item {
            OutlinedTextField(value = business, onValueChange = { business = it }, label = { Text("Business name — {{businessName}}") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        item {
            OutlinedTextField(value = hours, onValueChange = { hours = it }, label = { Text("Support hours — {{supportHours}}") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        item {
            Button(
                onClick = { viewModel.saveBusinessVariables(business, hours) },
                enabled = business != state.businessName || hours != state.supportHours,
            ) { Text("Save") }
        }
        item { SectionHeader("Available variables") }
        items(KnownVariables.all, key = { it.name }) { variable ->
            Column(Modifier.fillMaxWidth().padding(vertical = Spacing.xs)) {
                Text("{{${variable.name}}}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text("${variable.description} · e.g. ${variable.sample}", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (state.customFields.isNotEmpty()) {
            item { SectionHeader("Custom contact fields in use") }
            items(state.customFields, key = { "field-$it" }) { field ->
                Text("{{$field}}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(vertical = Spacing.xs))
            }
        }
        item {
            Notice(
                "Write {{firstName|there}} to use \"there\" when a contact has no first name. A message with a placeholder that has no " +
                    "value and no fallback is never sent — it waits in the outbox for you to fix it.",
                title = "Fallbacks",
            )
        }
    }
}
