package com.replypilot.features.scheduler

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.replypilot.core.design.ChannelAvatar
import com.replypilot.core.design.ConfirmDialog
import com.replypilot.core.design.EmptyState
import com.replypilot.core.design.Format
import com.replypilot.core.design.ListCard
import com.replypilot.core.design.LocalStatusColors
import com.replypilot.core.design.Notice
import com.replypilot.core.design.Pill
import com.replypilot.core.design.SimulatedPill
import com.replypilot.core.design.Spacing
import com.replypilot.domain.model.ExecutionItem
import com.replypilot.domain.model.MessageStatus
import com.replypilot.domain.model.ScheduleState
import com.replypilot.domain.scheduling.TimeWindows
import com.replypilot.navigation.Filters

private val listPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp)

@Composable
fun ScheduledTab(state: MessagesUiState, onOpen: (String) -> Unit, onCreate: () -> Unit) {
    if (!state.loading && state.schedules.isEmpty()) {
        EmptyState(
            Icons.Filled.Schedule, "Nothing scheduled yet",
            "Write a message once and ReplyPilot will queue it for the time you choose, or on a repeating schedule.",
            action = { Button(onClick = onCreate) { Text("Schedule a message") } },
        )
        return
    }
    val colors = LocalStatusColors.current
    LazyColumn(contentPadding = listPadding, verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        items(state.schedules, key = { it.schedule.id }) { row ->
            val s = row.schedule
            ListCard(onClick = { onOpen(s.id) }) {
                Row(verticalAlignment = Alignment.Top) {
                    ChannelAvatar(s.channel)
                    Spacer(Modifier.width(Spacing.md))
                    Column(Modifier.weight(1f)) {
                        Text(s.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${s.recipients.size} recipient${if (s.recipients.size == 1) "" else "s"} · ${s.channel.displayName}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(row.description, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(Spacing.xs))
                        Text(
                            row.nextRun?.let { "Next: ${Format.dateTime(it)}" } ?: "No upcoming sends",
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        val stateColor = when (s.state) {
                            ScheduleState.ACTIVE -> colors.scheduled
                            ScheduleState.PAUSED, ScheduleState.CANCELLED, ScheduleState.DRAFT -> colors.paused
                            ScheduleState.COMPLETED -> colors.success
                        }
                        if (s.state == ScheduleState.COMPLETED && row.pending > 0) {
                            Pill("Last send queued", colors.scheduled)
                        } else {
                            Pill(s.state.label, stateColor)
                        }
                        if (row.needsAttention > 0) Pill("${row.needsAttention} need you", colors.review)
                        if (s.isDemo) SimulatedPill(text = "Demo")
                    }
                }
            }
        }
    }
}

enum class OutboxFilter(val label: String, val key: String?) {
    ALL("All", null),
    DUE_TODAY("Due today", Filters.DUE_TODAY),
    ATTENTION("Needs you", Filters.ATTENTION),
    REVIEW("Awaiting review", Filters.REVIEW),
    RETRYING("Retrying", "retrying"),
    FAILED("Failed", Filters.FAILED),
    ;

    fun matches(item: ExecutionItem, state: MessagesUiState): Boolean = when (this) {
        ALL -> true
        DUE_TODAY -> {
            val start = TimeWindows.startOfDay(state.now, state.zone)
            val end = TimeWindows.startOfNextDay(state.now, state.zone)
            item.plannedAt in start until end
        }
        ATTENTION -> item.status == MessageStatus.NEEDS_ATTENTION || item.status == MessageStatus.AWAITING_REVIEW
        REVIEW -> item.status == MessageStatus.AWAITING_REVIEW
        RETRYING -> item.status == MessageStatus.RETRY_SCHEDULED
        FAILED -> item.status == MessageStatus.FAILED
    }

    companion object {
        fun from(key: String?): OutboxFilter = entries.firstOrNull { it.key != null && it.key == key } ?: ALL
    }
}

private val OUTBOX_STATUSES = MessageStatus.PENDING + setOf(MessageStatus.NEEDS_ATTENTION, MessageStatus.FAILED)

@Composable
fun OutboxTab(state: MessagesUiState, initialFilter: String?, callbacks: ExecutionCallbacks) {
    var filter by rememberSaveable(initialFilter) { mutableStateOf(OutboxFilter.from(initialFilter)) }
    val items = state.executions
        .filter { it.status in OUTBOX_STATUSES && filter.matches(it, state) }
        .sortedWith(compareBy<ExecutionItem> { outboxRank(it.status) }.thenBy { it.nextAttemptAt })

    val failedCount = state.executions.count { it.status == MessageStatus.FAILED }
    var confirmClear by remember { mutableStateOf(false) }
    Column {
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            items(OutboxFilter.entries) { option ->
                FilterChip(selected = filter == option, onClick = { filter = option }, label = { Text(option.label) })
            }
        }
        if (failedCount > 0 && (filter == OutboxFilter.ALL || filter == OutboxFilter.FAILED)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("$failedCount failed", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { confirmClear = true }) { Text("Clear failed") }
            }
        }
        if (confirmClear) {
            ConfirmDialog(
                title = "Clear $failedCount failed message${if (failedCount == 1) "" else "s"}?",
                text = "They're removed from the Outbox. What happened stays in Insights → Activity.",
                confirmLabel = "Clear failed",
                onConfirm = callbacks.clearFailed,
                onDismiss = { confirmClear = false },
                destructive = true,
                dismissLabel = "Keep them",
            )
        }
        if (items.isEmpty()) {
            EmptyState(
                Icons.Filled.Inbox, if (filter == OutboxFilter.ALL) "Outbox is empty" else "Nothing here",
                "Messages waiting to be sent, approved or handled by you appear here.",
            )
            return
        }
        LazyColumn(contentPadding = listPadding, verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            if (items.any { it.status == MessageStatus.NEEDS_ATTENTION && it.handoff != null }) {
                item {
                    Notice(
                        "Some channels can't send unattended. Tap \"Send manually\" to open the app with your message, then mark it as sent.",
                        title = "Manual sends",
                    )
                }
            }
            items(items, key = { it.id }) { item ->
                ExecutionRow(
                    item, state.now,
                    onOpen = { callbacks.open(item) },
                    onAction = { action, body -> callbacks.act(item, action, body) },
                    onHandoff = { callbacks.handoff(item) },
                )
            }
        }
    }
}

/** Things that need the user float to the top. */
private fun outboxRank(status: MessageStatus): Int = when (status) {
    MessageStatus.AWAITING_REVIEW, MessageStatus.NEEDS_ATTENTION -> 0
    MessageStatus.FAILED -> 1
    MessageStatus.EXECUTING, MessageStatus.DUE, MessageStatus.RETRY_SCHEDULED -> 2
    else -> 3
}

@Composable
fun SentTab(state: MessagesUiState, todayOnly: Boolean, callbacks: ExecutionCallbacks) {
    var query by rememberSaveable { mutableStateOf("") }
    var today by rememberSaveable(todayOnly) { mutableStateOf(todayOnly) }
    val dayStart = TimeWindows.startOfDay(state.now, state.zone)
    val items = state.executions
        .filter { it.status in MessageStatus.SUCCESS }
        .filter { !today || (it.lastAttemptAt ?: it.updatedAt) >= dayStart }
        .filter { item ->
            query.isBlank() || listOf(item.recipient.displayName, item.recipient.address, item.body, item.channel.displayName)
                .any { it.contains(query.trim(), ignoreCase = true) }
        }
        .sortedByDescending { it.lastAttemptAt ?: it.updatedAt }

    Column {
        OutlinedTextField(
            value = query, onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text("Search sent messages") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            singleLine = true,
        )
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            FilterChip(selected = !today, onClick = { today = false }, label = { Text("All") })
            FilterChip(selected = today, onClick = { today = true }, label = { Text("Today") })
        }
        Text(
            "Submitted = accepted by the channel. Delivered = the channel confirmed delivery.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        if (items.isEmpty()) {
            EmptyState(Icons.Filled.CheckCircle, "No sent messages", if (query.isBlank()) "Messages appear here once a channel accepts them." else "No sent message matches \"$query\".")
            return
        }
        LazyColumn(contentPadding = listPadding, verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            items(items, key = { it.id }) { item ->
                ExecutionRow(item, state.now, onOpen = { callbacks.open(item) }, onAction = { _, _ -> }, onHandoff = {}, showActions = false)
            }
        }
    }
}
