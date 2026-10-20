package com.replypilot.features.autoresponder

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Science
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.replypilot.core.design.ChannelAvatar
import com.replypilot.core.design.Format
import com.replypilot.core.design.ListCard
import com.replypilot.core.design.LocalStatusColors
import com.replypilot.core.design.MetricTile
import com.replypilot.core.design.Notice
import com.replypilot.core.design.Pill
import com.replypilot.core.design.ResultPill
import com.replypilot.core.design.SectionHeader
import com.replypilot.core.design.SimulatedPill
import com.replypilot.core.design.Spacing
import com.replypilot.domain.model.ExecutionItem
import com.replypilot.navigation.AppNavigator
import com.replypilot.navigation.AutomationTab
import com.replypilot.navigation.InsightsTab
import com.replypilot.navigation.Routes

@Composable
fun ResponderTab(nav: AppNavigator, viewModel: ResponderViewModel, onOpenTab: (AutomationTab) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<ExecutionItem?>(null) }
    val colors = LocalStatusColors.current

    if (state.loading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    LazyColumn(contentPadding = PaddingValues(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        item { PauseCard(state.paused, viewModel::setPaused) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                MetricTile("Active rules of ${state.totalRules}", state.activeRules.toString(), Modifier.weight(1f), colors.autoReply) { onOpenTab(AutomationTab.RULES) }
                MetricTile("Replies sent today", state.repliesToday.toString(), Modifier.weight(1f), colors.success) { nav.navigate(Routes.insights(InsightsTab.ACTIVITY)) }
                MetricTile("Held back today", state.suppressedToday.toString(), Modifier.weight(1f), colors.paused) {
                    nav.navigate(Routes.insights(InsightsTab.ACTIVITY, com.replypilot.navigation.Filters.SUPPRESSED))
                }
            }
        }
        item {
            Notice(
                "Auto-replies work by reading new-message notifications from the apps you enable and answering through their Reply button. " +
                    "A conversation must have a fresh notification, and Notification access must be on. Email can't auto-reply.",
                title = "How auto-replies work",
                action = { TextButton(onClick = { nav.navigate(Routes.SETTINGS_CHANNELS) }) { Text("Connected channels") } },
            )
        }
        item {
            FilledTonalButton(onClick = { nav.navigate(Routes.simulator()) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Science, contentDescription = null)
                Text("  Open the test lab", style = MaterialTheme.typography.labelLarge)
            }
        }

        item { SectionHeader("Waiting for your approval (${state.approvals.size})") }
        if (state.approvals.isEmpty()) {
            item { Text("Nothing to approve. Replies from “Ask me first” and AI-assisted rules appear here.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        items(state.approvals, key = { it.id }) { item ->
            ApprovalCard(
                item = item,
                ruleName = state.ruleNames[item.sourceId] ?: "Deleted rule",
                onApprove = { viewModel.approve(item) },
                onEdit = { editing = item },
                onReject = { viewModel.reject(item) },
                onOpen = { nav.navigate(Routes.execution(item.id)) },
            )
        }

        if (state.takeovers.isNotEmpty()) {
            item { SectionHeader("You're handling these chats") }
            items(state.takeovers, key = { "${it.channel}:${it.conversationKey}" }) { takeover ->
                ListCard(onClick = null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                        ChannelAvatar(takeover.channel)
                        Column(Modifier.weight(1f)) {
                            Text(takeover.conversationKey, style = MaterialTheme.typography.titleSmall)
                            Text("No auto-replies until ${takeover.humanTakeoverUntil?.let { Format.dateTime(it) }.orEmpty()}", style = MaterialTheme.typography.bodySmall)
                        }
                        OutlinedButton(onClick = { viewModel.release(takeover) }) { Text("Release") }
                    }
                }
            }
        }

        item {
            SectionHeader("Recent auto-reply activity") {
                TextButton(onClick = { nav.navigate(Routes.insights(InsightsTab.ACTIVITY)) }) { Text("All activity") }
            }
        }
        if (state.recent.isEmpty()) {
            item { Text("No incoming messages processed yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        items(state.recent, key = { it.id }) { event ->
            ListCard(onClick = null) {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        Text(event.type.label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        if (event.simulated) SimulatedPill()
                        ResultPill(event.result)
                    }
                    val who = listOfNotNull(event.channel?.displayName, event.recipientRef, event.ruleId?.let { state.ruleNames[it] }).joinToString(" · ")
                    Text("$who · ${Format.relative(event.at)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    event.reason?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }

    editing?.let { item ->
        EditApprovalDialog(item, onDismiss = { editing = null }, onApprove = { text -> viewModel.approve(item, text); editing = null })
    }
}

@Composable
private fun PauseCard(paused: Boolean, onChange: (Boolean) -> Unit) {
    val colors = LocalStatusColors.current
    val tone = if (paused) colors.failed else colors.success
    Card(colors = CardDefaults.cardColors(containerColor = tone.copy(alpha = 0.10f))) {
        Row(Modifier.padding(Spacing.md), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Icon(if (paused) Icons.Filled.PauseCircle else Icons.Filled.PlayCircle, contentDescription = null, tint = tone)
            Column(Modifier.weight(1f)) {
                Text(if (paused) "All automation is paused" else "Automation is running", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (paused) "No scheduled messages, sequences or auto-replies will be sent." else "Turn off to stop every scheduled message, sequence and auto-reply at once.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(checked = !paused, onCheckedChange = { running -> onChange(!running) })
        }
    }
}

@Composable
private fun ApprovalCard(item: ExecutionItem, ruleName: String, onApprove: () -> Unit, onEdit: () -> Unit, onReject: () -> Unit, onOpen: () -> Unit) {
    val colors = LocalStatusColors.current
    val isAi = item.statusReason?.startsWith("AI draft") == true
    val isMock = item.statusReason?.contains("mock", ignoreCase = true) == true
    ListCard(onClick = onOpen) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                ChannelAvatar(item.channel)
                Column(Modifier.weight(1f)) {
                    Text(item.recipient.displayName, style = MaterialTheme.typography.titleSmall)
                    Text("$ruleName · ${Format.relative(item.createdAt)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Pill(if (isAi) "AI draft" else "Rule reply", if (isAi) colors.simulated else colors.autoReply)
                if (isMock) SimulatedPill(text = "Mock AI")
                if (item.simulated) SimulatedPill()
            }
            Text("“${item.body}”", style = MaterialTheme.typography.bodyLarge)
            item.expiresAt?.let { Text("Expires ${Format.relative(it)} — the chat's reply button may disappear after that.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Button(onClick = onApprove) { Text("Approve") }
                OutlinedButton(onClick = onEdit) { Text("Edit") }
                TextButton(onClick = onReject) { Text("Reject", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

@Composable
private fun EditApprovalDialog(item: ExecutionItem, onDismiss: () -> Unit, onApprove: (String) -> Unit) {
    var text by remember(item.id) { mutableStateOf(item.body) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit reply to ${item.recipient.displayName}") },
        text = { OutlinedTextField(text, { text = it }, minLines = 3, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) },
        confirmButton = { TextButton(onClick = { onApprove(text) }, enabled = text.isNotBlank()) { Text("Approve & send") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
