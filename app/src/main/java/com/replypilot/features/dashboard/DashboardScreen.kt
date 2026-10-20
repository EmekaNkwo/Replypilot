package com.replypilot.features.dashboard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Rule
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.replypilot.core.design.AvailabilityPill
import com.replypilot.core.design.ChannelAvatar
import com.replypilot.core.design.Format
import com.replypilot.core.design.ListCard
import com.replypilot.core.design.LocalStatusColors
import com.replypilot.core.design.MetricTile
import com.replypilot.core.design.Notice
import com.replypilot.core.design.NoticeTone
import com.replypilot.core.design.ResultPill
import com.replypilot.core.design.SectionHeader
import com.replypilot.core.design.SimulatedPill
import com.replypilot.core.design.Spacing
import com.replypilot.core.design.StatusPill
import com.replypilot.domain.insights.DashboardStats
import com.replypilot.domain.model.ActivityEvent
import com.replypilot.domain.model.ExecutionItem
import com.replypilot.navigation.AppNavigator
import com.replypilot.navigation.AutomationTab
import com.replypilot.navigation.Filters
import com.replypilot.navigation.InsightsTab
import com.replypilot.navigation.MessagesTab
import com.replypilot.navigation.Routes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(nav: AppNavigator) {
    val viewModel: DashboardViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var fabOpen by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(viewModel) { viewModel.messages.collect { snackbar.showSnackbar(it) } }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("ReplyPilot")
                        if (state.demoMode) {
                            Spacer(Modifier.width(Spacing.sm))
                            SimulatedPill(text = "Demo mode")
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { nav.navigate(Routes.SETTINGS) }) { Icon(Icons.Filled.Settings, contentDescription = "Settings") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = { QuickActions(fabOpen, onToggle = { fabOpen = !fabOpen }) { route -> fabOpen = false; nav.navigate(route) } },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 4.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            item { PauseCard(paused = state.paused, onChange = viewModel::setPaused) }
            items(state.missingPermissions, key = { it.capability.name }) { missing ->
                Notice(
                    missing.capability.requiredFor + " won't work until you allow this.",
                    title = "Not allowed yet: ${missing.capability.title}",
                    tone = NoticeTone.WARNING,
                    action = { TextButton(onClick = { nav.navigate(Routes.SETTINGS_PERMISSIONS) }) { Text("Review permissions") } },
                )
            }
            state.stats?.let { stats -> item { MetricsGrid(stats, nav) } }

            item { SectionHeader("Next scheduled") }
            item {
                val next = state.stats?.nextScheduled
                if (next == null) {
                    ListCard(onClick = { nav.navigate(Routes.composer()) }) {
                        Text("Nothing queued. Tap to schedule a message.", style = MaterialTheme.typography.bodyMedium)
                    }
                } else {
                    UpcomingRow(next, state.now, highlight = true) { nav.navigate(Routes.execution(next.id)) }
                }
            }
            val later = state.stats?.upcoming.orEmpty().drop(1)
            if (later.isNotEmpty()) {
                item { SectionHeader("Upcoming", action = { TextButton(onClick = { nav.switchSection(Routes.messages()) }) { Text("All") } }) }
                items(later, key = { "u" + it.id }) { item -> UpcomingRow(item, state.now) { nav.navigate(Routes.execution(item.id)) } }
            }

            item {
                SectionHeader(
                    "Active rules (${state.activeRules.size})",
                    action = { TextButton(onClick = { nav.switchSection(Routes.automation(AutomationTab.RULES)) }) { Text("Manage") } },
                )
            }
            if (state.activeRules.isEmpty()) {
                item {
                    ListCard(onClick = { nav.navigate(Routes.ruleEditor()) }) {
                        Text("No auto-reply rules are on. Tap to create one.", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            } else {
                item {
                    Text(
                        state.activeRules.take(4).joinToString(" · ") { it.name } + if (state.activeRules.size > 4) " · +${state.activeRules.size - 4} more" else "",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            item { SectionHeader("Channels", action = { TextButton(onClick = { nav.navigate(Routes.SETTINGS_CHANNELS) }) { Text("Manage") } }) }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    items(state.channels, key = { it.id.name }) { status ->
                        Card(
                            modifier = Modifier.clickable { nav.navigate(Routes.SETTINGS_CHANNELS) },
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        ) {
                            Column(Modifier.padding(Spacing.md).width(132.dp), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                                ChannelAvatar(status.id)
                                Text(status.id.displayName, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                AvailabilityPill(status.availability)
                            }
                        }
                    }
                }
            }

            item { SectionHeader("Recent activity", action = { TextButton(onClick = { nav.switchSection(Routes.insights(InsightsTab.ACTIVITY)) }) { Text("All") } }) }
            if (state.recent.isEmpty()) {
                item { Text("No automation activity yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            items(state.recent, key = { "a" + it.id }) { event -> ActivityRow(event, state.ruleNames, state.now) }
        }
    }
}

@Composable
private fun PauseCard(paused: Boolean, onChange: (Boolean) -> Unit) {
    val colors = LocalStatusColors.current
    Surface(
        shape = MaterialTheme.shapes.large,
        color = if (paused) colors.failed.copy(alpha = 0.12f) else MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(Spacing.lg), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (paused) "Automation paused" else "Automation is running", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (paused) "No scheduled messages, sequences or auto-replies will be sent." else "Turn off to stop everything immediately.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(checked = !paused, onCheckedChange = { running -> onChange(!running) })
        }
    }
}

@Composable
private fun MetricsGrid(stats: DashboardStats, nav: AppNavigator) {
    val c = LocalStatusColors.current
    val tiles = listOf(
        Metric("Due today", stats.dueToday, c.scheduled, Routes.messages(MessagesTab.OUTBOX, Filters.DUE_TODAY)),
        Metric("Sent today", stats.sentToday, c.success, Routes.messages(MessagesTab.SENT, Filters.TODAY)),
        Metric("Awaiting execution", stats.awaiting, c.scheduled, Routes.messages(MessagesTab.OUTBOX, Filters.PENDING)),
        Metric("Failed today", stats.failed, c.failed, Routes.messages(MessagesTab.OUTBOX, Filters.FAILED)),
        Metric("Need you", stats.needsAttention + stats.awaitingReview, c.review, Routes.messages(MessagesTab.OUTBOX, Filters.ATTENTION)),
        Metric("Active rules", stats.activeRules, c.autoReply, Routes.automation(AutomationTab.RULES)),
        Metric("Messages processed today", stats.incomingProcessedToday, c.autoReply, Routes.insights(InsightsTab.ACTIVITY, Filters.INCOMING)),
        Metric("Rules needing attention", stats.rulesNeedingAttention, c.review, Routes.insights(InsightsTab.ACTIVITY, Filters.FAILED)),
    )
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm), modifier = Modifier.padding(top = Spacing.sm)) {
        tiles.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                row.forEach { m ->
                    MetricTile(
                        label = m.label, value = m.value.toString(), modifier = Modifier.weight(1f),
                        accent = if (m.value == 0) MaterialTheme.colorScheme.onSurfaceVariant else m.color,
                        onClick = { nav.navigate(m.route) },
                    )
                }
            }
        }
    }
}

private data class Metric(val label: String, val value: Int, val color: Color, val route: String)

@Composable
private fun UpcomingRow(item: ExecutionItem, now: Long, highlight: Boolean = false, onClick: () -> Unit) {
    ListCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ChannelAvatar(item.channel)
            Spacer(Modifier.width(Spacing.md))
            Column(Modifier.weight(1f)) {
                Text(item.recipient.displayName, style = if (highlight) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall, maxLines = 1)
                Text(
                    "${Format.dateTime(item.nextAttemptAt)} · ${Format.relative(item.nextAttemptAt, now)}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                )
                Text(item.body, style = MaterialTheme.typography.bodySmall, maxLines = if (highlight) 2 else 1, overflow = TextOverflow.Ellipsis)
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                StatusPill(item.status)
                if (item.simulated) SimulatedPill()
            }
        }
    }
}

@Composable
private fun ActivityRow(event: ActivityEvent, ruleNames: Map<String, String>, now: Long) {
    Row(Modifier.fillMaxWidth().padding(vertical = Spacing.xs), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            val subject = listOfNotNull(event.ruleId?.let { ruleNames[it] ?: "Deleted rule" }, event.recipientRef, event.channel?.displayName).joinToString(" · ")
            Text(event.type.label, style = MaterialTheme.typography.bodyMedium)
            Text(
                listOf(Format.relative(event.at, now), subject).filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            event.reason?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
        Spacer(Modifier.width(Spacing.sm))
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            ResultPill(event.result)
            if (event.simulated) SimulatedPill()
        }
    }
}

private data class QuickAction(val label: String, val icon: ImageVector, val route: String)

@Composable
private fun QuickActions(open: Boolean, onToggle: () -> Unit, onSelect: (String) -> Unit) {
    val actions = listOf(
        QuickAction("Run automation test", Icons.Filled.Science, Routes.simulator()),
        QuickAction("Create template", Icons.Filled.Description, Routes.templateEditor()),
        QuickAction("Create auto-reply rule", Icons.AutoMirrored.Filled.Rule, Routes.ruleEditor()),
        QuickAction("Schedule a message", Icons.Filled.Schedule, Routes.composer()),
    )
    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        AnimatedVisibility(visible = open) {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                actions.forEach { action ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 2.dp) {
                            Text(action.label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm))
                        }
                        Spacer(Modifier.width(Spacing.sm))
                        SmallFloatingActionButton(onClick = { onSelect(action.route) }) { Icon(action.icon, contentDescription = action.label) }
                    }
                }
            }
        }
        FloatingActionButton(onClick = onToggle) {
            Icon(if (open) Icons.Filled.Close else Icons.Filled.Add, contentDescription = if (open) "Close quick actions" else "Quick actions")
        }
    }
}
