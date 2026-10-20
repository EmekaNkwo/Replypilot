package com.replypilot.features.analytics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MarkEmailRead
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.replypilot.core.design.ChannelAvatar
import com.replypilot.core.design.EmptyState
import com.replypilot.core.design.Format
import com.replypilot.core.design.ListCard
import com.replypilot.core.design.LocalStatusColors
import com.replypilot.core.design.Notice
import com.replypilot.core.design.SectionHeader
import com.replypilot.core.design.SimulatedPill
import com.replypilot.core.design.Spacing
import com.replypilot.core.design.StatusPill
import com.replypilot.navigation.AppNavigator
import com.replypilot.navigation.Routes

@Composable
fun DeliveryTab(state: InsightsUiState, nav: AppNavigator) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = Spacing.lg)) {
        item {
            Spacer(Modifier.height(Spacing.md))
            Notice(
                "“Submitted” means the channel accepted the message. “Delivered” is shown only when a channel confirms it. " +
                    "Channels that can confirm delivery: " +
                    state.deliveryChannels.joinToString { it.displayName }.ifEmpty { "none configured" } + ". " +
                    "Other channels stop at Submitted, even if the message arrived.",
                title = "Submitted is not the same as delivered",
            )
        }
        item { SectionHeader("Last ${state.days} days by channel") }
        if (state.delivery.isEmpty()) {
            item { EmptyState(Icons.Filled.MarkEmailRead, "No sends yet", "Delivery reports appear after messages are sent.") }
        } else {
            item { DeliveryTable(state.delivery) }
        }
        if (state.recentSubmitted.isNotEmpty()) {
            item { SectionHeader("Recent sends") }
            items(state.recentSubmitted, key = { it.id }) { item ->
                ListCard(onClick = { nav.navigate(Routes.execution(item.id)) }, modifier = Modifier.padding(bottom = Spacing.sm)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                        ChannelAvatar(item.channel)
                        Column(Modifier.weight(1f)) {
                            Text(item.recipient.displayName, style = MaterialTheme.typography.titleSmall)
                            Text(
                                "${item.source.label} · ${Format.dateTime(item.lastAttemptAt ?: item.updatedAt)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                            StatusPill(item.status)
                            if (item.simulated) SimulatedPill()
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(Spacing.xl)) }
    }
}

@Composable
private fun DeliveryTable(rows: List<DeliveryRow>) {
    val c = LocalStatusColors.current
    ListCard(onClick = null) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Row {
                Text("Channel", Modifier.weight(2f), style = MaterialTheme.typography.labelMedium)
                listOf("Submitted", "Delivered", "Failed", "Attention").forEach {
                    Text(it, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.End, maxLines = 1)
                }
            }
            rows.forEach { row ->
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(2f)) {
                        Text(row.channel.displayName, style = MaterialTheme.typography.bodyMedium)
                        if (!row.confirmsDelivery) {
                            Text("No delivery reports", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Cell("${row.submitted}", c.scheduled)
                    Cell(if (row.confirmsDelivery) "${row.delivered}" else "—", c.success)
                    Cell("${row.failed}", c.failed)
                    Cell("${row.needsAttention}", c.review)
                }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Cell(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(text, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = color, textAlign = TextAlign.End)
}
