package com.replypilot.features.sequences

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.replypilot.core.design.ChannelAvatar
import com.replypilot.core.design.EmptyState
import com.replypilot.core.design.ListCard
import com.replypilot.core.design.LocalStatusColors
import com.replypilot.core.design.Pill
import com.replypilot.core.design.SimulatedPill
import com.replypilot.core.design.Spacing
import com.replypilot.domain.model.ChannelId
import com.replypilot.navigation.AppNavigator
import com.replypilot.navigation.Routes

/** Content of the Sequences tab inside the Automation section (no Scaffold of its own). */
@Composable
fun SequencesTab(nav: AppNavigator) {
    val viewModel: SequencesViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()

    Box(Modifier.fillMaxSize()) {
        if (state.loaded && state.items.isEmpty()) {
            EmptyState(
                icon = Icons.AutoMirrored.Filled.ListAlt,
                title = "No sequences yet",
                body = "A sequence sends a planned series of messages over several days — for example a welcome, " +
                    "a helpful follow-up on day 2 and a check-in on day 5. It stops automatically when the person replies or opts out.",
                action = { Button(onClick = { nav.navigate(Routes.sequenceEditor()) }) { Text("New sequence") } },
            )
        } else {
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                items(state.items, key = { it.sequence.id }) { item ->
                    val sequence = item.sequence
                    ListCard(onClick = { nav.navigate(Routes.sequence(sequence.id)) }) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                            ChannelAvatar(sequence.channel)
                            Column(Modifier.weight(1f)) {
                                Text(sequence.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    "${sequence.steps.size} step${if (sequence.steps.size == 1) "" else "s"} over " +
                                        "${(sequence.steps.maxOfOrNull { it.dayOffset } ?: 0) + 1} day(s) · ${sequence.channel.displayName}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), modifier = Modifier.padding(top = Spacing.xs)) {
                                    Pill("${item.activeEnrollments} active", LocalStatusColors.current.scheduled)
                                    if (sequence.channel == ChannelId.DEMO) SimulatedPill()
                                    if (!sequence.enabled) Pill("Disabled", LocalStatusColors.current.paused)
                                }
                            }
                            Switch(
                                checked = sequence.enabled,
                                onCheckedChange = { viewModel.setEnabled(sequence, it) },
                                modifier = Modifier.semantics { contentDescription = "Sequence ${sequence.name} enabled" },
                            )
                        }
                    }
                }
            }
            ExtendedFloatingActionButton(
                onClick = { nav.navigate(Routes.sequenceEditor()) },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("New sequence") },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            )
        }
    }
}
