package com.replypilot.features.scheduler

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.replypilot.core.design.Format
import com.replypilot.core.design.Spacing
import com.replypilot.core.design.statusColor
import com.replypilot.domain.model.ExecutionItem
import com.replypilot.domain.model.MessageStatus
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

/** Month grid with a dot per status present on each day, plus the selected day's messages. */
@Composable
fun CalendarTab(state: MessagesUiState, callbacks: ExecutionCallbacks) {
    val today = Instant.ofEpochMilli(state.now.takeIf { it > 0 } ?: System.currentTimeMillis()).atZone(state.zone).toLocalDate()
    var monthText by rememberSaveable { mutableStateOf(YearMonth.from(today).toString()) }
    var selectedText by rememberSaveable { mutableStateOf(today.toString()) }
    val month = YearMonth.parse(monthText)
    val selected = LocalDate.parse(selectedText)

    val byDay: Map<LocalDate, List<ExecutionItem>> = state.executions
        .filter { it.status != MessageStatus.CANCELLED }
        .groupBy { Instant.ofEpochMilli(displayTime(it)).atZone(state.zone).toLocalDate() }
    val dayItems = byDay[selected].orEmpty().sortedBy { displayTime(it) }

    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { monthText = month.minusMonths(1).toString() }) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous month")
                }
                Text(
                    "${month.month.getDisplayName(TextStyle.FULL, Locale.getDefault())} ${month.year}",
                    style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), textAlign = TextAlign.Center,
                )
                IconButton(onClick = { monthText = month.plusMonths(1).toString() }) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next month")
                }
            }
        }
        item { MonthGrid(month, today, selected, byDay) { selectedText = it.toString() } }
        item {
            Text(
                "${Format.date(selected)} · ${dayItems.size} message${if (dayItems.size == 1) "" else "s"}",
                style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = Spacing.md),
            )
        }
        if (dayItems.isEmpty()) {
            item { Text("Nothing planned on this day.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        items(dayItems, key = { it.id }) { item ->
            ExecutionRow(
                item, state.now,
                onOpen = { callbacks.open(item) },
                onAction = { action, body -> callbacks.act(item, action, body) },
                onHandoff = { callbacks.handoff(item) },
            )
        }
    }
}

private fun displayTime(item: ExecutionItem): Long =
    if (item.status in MessageStatus.SUCCESS) item.lastAttemptAt ?: item.plannedAt else item.plannedAt

@Composable
private fun MonthGrid(month: YearMonth, today: LocalDate, selected: LocalDate, byDay: Map<LocalDate, List<ExecutionItem>>, onSelect: (LocalDate) -> Unit) {
    val first = month.atDay(1)
    val leading = first.dayOfWeek.value - 1
    val cells = List(leading) { null } + (1..month.lengthOfMonth()).map { month.atDay(it) }
    Column {
        Row {
            DayOfWeek.entries.forEach { dow ->
                Text(
                    dow.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                    modifier = Modifier.weight(1f), textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        cells.chunked(7).forEach { week ->
            Row {
                week.forEach { date -> DayCell(date, date == today, date == selected, byDay[date].orEmpty(), onSelect, Modifier.weight(1f)) }
                repeat(7 - week.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun DayCell(date: LocalDate?, isToday: Boolean, isSelected: Boolean, items: List<ExecutionItem>, onSelect: (LocalDate) -> Unit, modifier: Modifier) {
    if (date == null) {
        Spacer(modifier.aspectRatio(1f))
        return
    }
    val background = when {
        isSelected -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surface
    }
    Column(
        modifier = modifier
            .aspectRatio(1f)
            .padding(2.dp)
            .background(background, CircleShape)
            .clickable { onSelect(date) }
            .semantics { contentDescription = "${Format.date(date)}, ${items.size} messages" },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            date.dayOfMonth.toString(),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
            color = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(2.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            items.map { it.status }.distinctBy { statusColorKey(it) }.take(3).forEach { status ->
                Box(Modifier.size(5.dp).background(statusColor(status), CircleShape))
            }
        }
    }
}

private fun statusColorKey(status: MessageStatus): Int = when (status) {
    MessageStatus.SUBMITTED, MessageStatus.DELIVERED -> 0
    MessageStatus.FAILED -> 1
    MessageStatus.NEEDS_ATTENTION, MessageStatus.AWAITING_REVIEW -> 2
    MessageStatus.PAUSED, MessageStatus.EXPIRED, MessageStatus.SKIPPED, MessageStatus.CANCELLED, MessageStatus.DRAFT -> 3
    else -> 4
}
