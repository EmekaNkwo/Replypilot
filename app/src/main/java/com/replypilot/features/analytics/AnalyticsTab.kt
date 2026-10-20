package com.replypilot.features.analytics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.replypilot.core.design.EmptyState
import com.replypilot.core.design.Format
import com.replypilot.core.design.ListCard
import com.replypilot.core.design.LocalStatusColors
import com.replypilot.core.design.MetricTile
import com.replypilot.core.design.Notice
import com.replypilot.core.design.NoticeTone
import com.replypilot.core.design.SectionHeader
import com.replypilot.core.design.Spacing
import com.replypilot.domain.insights.AnalyticsReport
import com.replypilot.domain.insights.DailyCount
import java.time.format.TextStyle
import java.util.Locale

@Composable
fun AnalyticsTab(state: InsightsUiState, onDaysChange: (Int) -> Unit) {
    val report = state.report
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = Spacing.lg)) {
        item {
            Row(Modifier.padding(top = Spacing.md), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                listOf(7, 30).forEach { d ->
                    FilterChip(selected = state.days == d, onClick = { onDaysChange(d) }, label = { Text("Last $d days") })
                }
            }
        }
        if (report == null) return@LazyColumn
        val total = report.days.sumOf { it.submitted + it.failed }
        if (total == 0 && report.incomingProcessed == 0) {
            item {
                EmptyState(
                    Icons.Filled.Insights,
                    "No activity yet",
                    "Numbers appear here once messages are sent or incoming messages are processed. Everything is computed from data on this device.",
                )
            }
            return@LazyColumn
        }
        item { Summary(report) }
        val simulatedShare = report.simulatedShare
        if (simulatedShare != null && simulatedShare > 0) {
            item {
                Spacer(Modifier.height(Spacing.md))
                Notice(
                    "${Format.percent(simulatedShare)} of these sends were demo/simulated — no real message was sent for them.",
                    tone = NoticeTone.SIMULATED,
                    title = "Includes simulated activity",
                )
            }
        }
        item {
            SectionHeader("Sends per day")
            DailyBarChart(report.days)
        }
        item { Breakdown("By channel", report.byChannel.entries.sortedByDescending { it.value }.map { it.key.displayName to it.value }) }
        item { Breakdown("By source", report.bySource.entries.sortedByDescending { it.value }.map { it.key.label to it.value }) }
        item { Breakdown("Top matched rules", report.topRules, empty = "No rules matched in this period.") }
        item { Breakdown("Why replies were suppressed", report.suppressions, empty = "No auto-replies were suppressed.") }
        item { Spacer(Modifier.height(Spacing.xl)) }
    }
}

@Composable
private fun Summary(report: AnalyticsReport) {
    val c = LocalStatusColors.current
    Column(Modifier.padding(top = Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            MetricTile("Sent or submitted", report.days.sumOf { it.submitted }.toString(), Modifier.weight(1f), accent = c.success)
            MetricTile("Failed", report.days.sumOf { it.failed }.toString(), Modifier.weight(1f), accent = c.failed)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            MetricTile("Success rate", Format.percent(report.successRate), Modifier.weight(1f))
            MetricTile("Incoming messages processed", report.incomingProcessed.toString(), Modifier.weight(1f), accent = c.autoReply)
        }
    }
}

/** Grouped bar chart: submitted (success colour) and failed (failure colour) per day, with axis labels and a legend. */
@Composable
fun DailyBarChart(days: List<DailyCount>) {
    val c = LocalStatusColors.current
    val maxValue = (days.maxOfOrNull { maxOf(it.submitted, it.failed) } ?: 0).coerceAtLeast(1)
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val summary = days.joinToString("; ") { "${it.date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())} ${it.date.dayOfMonth}: ${it.submitted} sent, ${it.failed} failed" }
    val labelEvery = if (days.size > 10) 5 else 1

    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            LegendItem(c.success, "Sent / submitted")
            LegendItem(c.failed, "Failed")
        }
        Spacer(Modifier.height(Spacing.sm))
        Row(Modifier.fillMaxWidth().height(160.dp)) {
            Column(Modifier.fillMaxHeight160(), verticalArrangement = Arrangement.SpaceBetween, horizontalAlignment = Alignment.End) {
                Text("$maxValue", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("0", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(Spacing.xs))
            Canvas(
                Modifier.weight(1f).height(160.dp).semantics { contentDescription = "Bar chart of daily sends. $summary" },
            ) {
                val slot = size.width / days.size.coerceAtLeast(1)
                val barWidth = (slot * 0.36f).coerceAtMost(18.dp.toPx())
                drawLine(gridColor, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())
                drawLine(gridColor, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 0.5.dp.toPx())
                days.forEachIndexed { index, day ->
                    val center = slot * index + slot / 2
                    listOf(day.submitted to c.success, day.failed to c.failed).forEachIndexed { i, (value, color) ->
                        if (value > 0) {
                            val h = size.height * value / maxValue
                            val left = if (i == 0) center - barWidth - 1.dp.toPx() else center + 1.dp.toPx()
                            drawRoundRect(color, Offset(left, size.height - h), Size(barWidth, h), CornerRadius(3.dp.toPx()))
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 28.dp)) {
            days.forEachIndexed { index, day ->
                Text(
                    if (index % labelEvery == 0) (if (days.size > 10) "${day.date.dayOfMonth}" else day.date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())) else "",
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

private fun Modifier.fillMaxHeight160() = this.height(160.dp).width(24.dp)

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(color, RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(Spacing.xs))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun Breakdown(title: String, rows: List<Pair<String, Int>>, empty: String = "Nothing in this period.") {
    SectionHeader(title)
    ListCard(onClick = null) {
        if (rows.isEmpty()) {
            Text(empty, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            val max = rows.maxOf { it.second }.coerceAtLeast(1)
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                rows.forEachIndexed { index, (label, value) ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                        Box(
                            Modifier.width((80 * value / max).coerceAtLeast(4).dp).height(6.dp)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.6f), RoundedCornerShape(3.dp)),
                        )
                        Spacer(Modifier.width(Spacing.sm))
                        Text("$value", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}
