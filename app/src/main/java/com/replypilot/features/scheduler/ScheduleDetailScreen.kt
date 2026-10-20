package com.replypilot.features.scheduler

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EventBusy
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.replypilot.core.design.BackTopBar
import com.replypilot.core.design.ChannelAvatar
import com.replypilot.core.design.ConfirmDialog
import com.replypilot.core.design.EmptyState
import com.replypilot.core.design.Format
import com.replypilot.core.design.ListCard
import com.replypilot.core.design.Notice
import com.replypilot.core.design.NoticeTone
import com.replypilot.core.design.SectionHeader
import com.replypilot.core.design.SimulatedPill
import com.replypilot.core.design.Spacing
import com.replypilot.domain.model.ScheduleState
import com.replypilot.domain.model.ScheduledMessage
import com.replypilot.navigation.AppNavigator
import com.replypilot.navigation.Routes

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScheduleDetailScreen(nav: AppNavigator, id: String) {
    val viewModel: ScheduleDetailViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    var confirm by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                ScheduleEvent.Deleted -> nav.back()
                is ScheduleEvent.Message -> snackbar.showSnackbar(event.text)
            }
        }
    }

    val schedule = state.schedule
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            BackTopBar(schedule?.title ?: "Schedule", onBack = nav::back) {
                if (schedule != null) {
                    IconButton(onClick = { nav.navigate(Routes.composer(duplicateOf = id)) }) { Icon(Icons.Filled.ContentCopy, contentDescription = "Duplicate") }
                    if (schedule.state != ScheduleState.CANCELLED) {
                        IconButton(onClick = { nav.navigate(Routes.composer(scheduleId = id)) }) { Icon(Icons.Filled.Edit, contentDescription = "Edit") }
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (schedule == null) {
            if (!state.loading) {
                EmptyState(Icons.Filled.EventBusy, "Schedule not found", "It may have been deleted.", Modifier, action = { TextButton(onClick = nav::back) { Text("Go back") } })
            }
            return@Scaffold
        }
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            item { Summary(schedule, state.description) }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    when (schedule.state) {
                        ScheduleState.ACTIVE, ScheduleState.COMPLETED -> FilledTonalButton(onClick = viewModel::pause) { Text("Pause") }
                        ScheduleState.PAUSED -> FilledTonalButton(onClick = viewModel::resume) { Text("Resume") }
                        else -> Unit
                    }
                    if (schedule.state != ScheduleState.CANCELLED) OutlinedButton(onClick = { confirm = "cancel" }) { Text("Cancel schedule") }
                    OutlinedButton(onClick = { confirm = "delete" }) { Text("Delete") }
                }
            }
            if (schedule.state == ScheduleState.PAUSED) {
                item { Notice("Paused: nothing from this schedule is sent until you resume it.", tone = NoticeTone.WARNING) }
            }
            item { SectionHeader("Recipients") }
            item {
                Text(schedule.recipients.joinToString("\n") { "${it.displayName} · ${it.address}" }, style = MaterialTheme.typography.bodyMedium)
            }
            item { SectionHeader("Message") }
            item { ListCard(onClick = null) { Text(schedule.body, style = MaterialTheme.typography.bodyMedium) } }
            item { SectionHeader("Upcoming (${state.upcomingZone.id})") }
            if (state.upcoming.isEmpty()) {
                item { Text("No future occurrences.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            items(state.upcoming, key = { "o${it.index}" }) { occurrence ->
                Text("#${occurrence.index + 1} · ${Format.dateTime(occurrence.at, state.upcomingZone)}", style = MaterialTheme.typography.bodyMedium)
            }
            item { SectionHeader("Executions") }
            if (state.executions.isEmpty()) {
                item { Text("No messages have been queued yet. Rows are created up to two days ahead.", style = MaterialTheme.typography.bodySmall) }
            }
            items(state.executions, key = { it.id }) { item ->
                ExecutionRow(
                    item, state.now,
                    onOpen = { nav.navigate(Routes.execution(item.id)) },
                    onAction = { action, body -> viewModel.act(item.id, action, body) },
                    onHandoff = { viewModel.manualSend(context, item) },
                )
            }
        }
    }

    when (confirm) {
        "cancel" -> ConfirmDialog(
            "Cancel this schedule?", "Pending messages from it won't be sent. Already-sent messages are unaffected.",
            "Cancel schedule", onConfirm = viewModel::cancel, onDismiss = { confirm = null }, destructive = true, dismissLabel = "Keep it",
        )
        "delete" -> ConfirmDialog(
            "Delete this schedule?", "It and its pending messages are removed. History of sent messages stays in Activity.",
            "Delete", onConfirm = viewModel::delete, onDismiss = { confirm = null }, destructive = true,
        )
    }
}

@Composable
private fun Summary(schedule: ScheduledMessage, description: String) {
    ListCard(onClick = null) {
        Row(verticalAlignment = Alignment.Top) {
            ChannelAvatar(schedule.channel)
            Spacer(Modifier.width(Spacing.md))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(description, style = MaterialTheme.typography.titleSmall)
                Text("${schedule.channel.displayName} · ${schedule.state.label}", style = MaterialTheme.typography.bodySmall)
                if (schedule.attachments.isNotEmpty()) {
                    Text(
                        "Files: ${schedule.attachments.joinToString { it.fileName }}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text("Time zone: ${schedule.spec.timeZone} (${schedule.spec.zoneMode.label.lowercase()})", style = MaterialTheme.typography.bodySmall)
                Text(
                    "Quiet hours: ${schedule.policy.quietHours.label.lowercase()} · Expires after ${schedule.policy.expireAfterMinutes} min · Up to ${schedule.policy.maxRetries} retries",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (schedule.isDemo) SimulatedPill(text = "Demo schedule")
            }
        }
    }
}
