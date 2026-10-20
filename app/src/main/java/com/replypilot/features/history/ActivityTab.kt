package com.replypilot.features.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import com.replypilot.core.design.EmptyState
import com.replypilot.core.design.Format
import com.replypilot.core.design.ListCard
import com.replypilot.core.design.ResultPill
import com.replypilot.core.design.SimulatedPill
import com.replypilot.core.design.Spacing
import com.replypilot.domain.model.ActivityEvent
import com.replypilot.domain.model.ActivityResult
import com.replypilot.domain.model.ActivityType
import com.replypilot.navigation.AppNavigator
import com.replypilot.navigation.Filters

enum class ActivityFilter(val label: String) {
    ALL("All"),
    INCOMING("Incoming"),
    SUPPRESSED("Suppressed"),
    FAILED("Failed"),
    SENT("Sent"),
    SIMULATED("Demo only"),
    REAL("Real only"),
    ;

    fun matches(event: ActivityEvent): Boolean = when (this) {
        ALL -> true
        INCOMING -> event.type == ActivityType.RULE_MATCHED || event.type == ActivityType.NO_RULE_MATCHED
        SUPPRESSED -> event.result == ActivityResult.SUPPRESSED
        FAILED -> event.result == ActivityResult.FAILED
        SENT -> event.type in SENT_TYPES
        SIMULATED -> event.simulated
        REAL -> !event.simulated
    }

    companion object {
        private val SENT_TYPES = setOf(ActivityType.MESSAGE_SUBMITTED, ActivityType.REPLY_SENT, ActivityType.DELIVERY_CONFIRMED)

        fun from(filter: String?): ActivityFilter = when (filter) {
            Filters.INCOMING -> INCOMING
            Filters.SUPPRESSED -> SUPPRESSED
            Filters.FAILED -> FAILED
            else -> ALL
        }
    }
}

/** Searchable automation history. Records hold identifiers and reasons only, never message bodies. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ActivityTab(
    events: List<ActivityEvent>,
    ruleNames: Map<String, String>,
    initialFilter: String?,
    loading: Boolean,
    nav: AppNavigator,
) {
    var filter by rememberSaveable { mutableStateOf(ActivityFilter.from(initialFilter)) }
    var query by rememberSaveable { mutableStateOf("") }
    var selected by remember { mutableStateOf<ActivityEvent?>(null) }

    val visible = remember(events, filter, query, ruleNames) {
        val q = query.trim().lowercase()
        events.filter { filter.matches(it) }.filter { event ->
            q.isEmpty() || listOfNotNull(
                event.type.label, event.reason, event.recipientRef, event.channel?.displayName,
                event.ruleId?.let { ruleNames[it] }, event.result.label,
            ).any { it.lowercase().contains(q) }
        }
    }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = Spacing.lg)) {
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.md),
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                placeholder = { Text("Search by contact, rule, channel or reason") },
                singleLine = true,
            )
            FlowRow(Modifier.padding(vertical = Spacing.sm), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                ActivityFilter.entries.forEach { f ->
                    FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f.label) })
                }
            }
            Text(
                "History keeps what happened and why — not the content of your messages.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Spacing.sm))
        }
        if (!loading && visible.isEmpty()) {
            item {
                EmptyState(
                    Icons.Filled.History,
                    if (events.isEmpty()) "No activity yet" else "Nothing matches",
                    if (events.isEmpty()) "Scheduled sends, matched rules and suppressed replies will be listed here." else "Try another filter or search.",
                )
            }
        }
        items(visible, key = { it.id }) { event ->
            ActivityRow(event, event.ruleId?.let { ruleNames[it] }, onClick = { selected = event })
            Spacer(Modifier.height(Spacing.sm))
        }
        item { Spacer(Modifier.height(Spacing.xl)) }
    }

    selected?.let { event ->
        ActivityDiagnosticsSheet(event, event.ruleId?.let { ruleNames[it] }, nav, onDismiss = { selected = null })
    }
}

@Composable
private fun ActivityRow(event: ActivityEvent, ruleName: String?, onClick: () -> Unit) {
    ListCard(onClick = onClick) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md), verticalAlignment = Alignment.Top) {
            event.channel?.let { ChannelAvatar(it) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Text(event.type.label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f, fill = false))
                    ResultPill(event.result)
                    if (event.simulated) SimulatedPill()
                }
                val subject = listOfNotNull(event.recipientRef, ruleName?.let { "Rule: $it" }, event.attempt?.takeIf { it > 0 }?.let { "Attempt $it" })
                if (subject.isNotEmpty()) {
                    Text(subject.joinToString(" · "), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                event.reason?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Text(Format.dateTime(event.at), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
