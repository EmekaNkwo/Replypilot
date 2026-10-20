package com.replypilot.features.rules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.replypilot.core.design.ConfirmDialog
import com.replypilot.core.design.EmptyState
import com.replypilot.core.design.ListCard
import com.replypilot.core.design.LocalStatusColors
import com.replypilot.core.design.Notice
import com.replypilot.core.design.Pill
import com.replypilot.core.design.Spacing
import com.replypilot.domain.model.AutoReplyRule
import com.replypilot.domain.model.ReplyMode
import com.replypilot.navigation.AppNavigator
import com.replypilot.navigation.Routes

/** Rules in evaluation order. Rendered inside the Automation scaffold, which owns the FAB and snackbar. */
@Composable
fun RulesTab(nav: AppNavigator, viewModel: RulesViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var pendingDelete by remember { mutableStateOf<AutoReplyRule?>(null) }

    when {
        state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        state.rules.isEmpty() -> EmptyState(
            icon = Icons.Filled.AutoAwesome,
            title = "No auto-reply rules yet",
            body = "Rules decide when ReplyPilot answers incoming messages — for example, replying with your prices when someone asks \"how much\".",
            action = { Button(onClick = { nav.navigate(Routes.ruleEditor()) }) { Text("Create your first rule") } },
        )
        else -> {
            val enabledOrder = state.rules.filter { it.enabled }.mapIndexed { i, r -> r.id to i + 1 }.toMap()
            LazyColumn(
                contentPadding = PaddingValues(start = Spacing.lg, end = Spacing.lg, top = Spacing.md, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                item {
                    Notice(
                        "Rules are checked from top to bottom. The first matching rule with “stop after match” ends the check. " +
                            "Use the arrows to change the order.",
                    )
                }
                itemsIndexed(state.rules, key = { _, rule -> rule.id }) { index, rule ->
                    RuleCard(
                        rule = rule,
                        position = enabledOrder[rule.id],
                        summary = RuleText.summary(rule, state.templates, state.contactNames, state.ruleNames),
                        canMoveUp = index > 0,
                        canMoveDown = index < state.rules.lastIndex,
                        onOpen = { nav.navigate(Routes.ruleEditor(rule.id)) },
                        onToggle = { viewModel.setEnabled(rule, it) },
                        onMove = { up -> viewModel.move(rule, up) },
                        onDuplicate = { viewModel.duplicate(rule) },
                        onTest = { nav.navigate(Routes.simulator(rule.id)) },
                        onDelete = { pendingDelete = rule },
                    )
                }
            }
        }
    }

    pendingDelete?.let { rule ->
        ConfirmDialog(
            title = "Delete “${rule.name}”?",
            text = "This rule will stop replying immediately. Its past activity stays in the history.",
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = { viewModel.delete(rule) },
            onDismiss = { pendingDelete = null },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RuleCard(
    rule: AutoReplyRule,
    position: Int?,
    summary: String,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onOpen: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onMove: (Boolean) -> Unit,
    onDuplicate: () -> Unit,
    onTest: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = LocalStatusColors.current
    var menuOpen by remember { mutableStateOf(false) }
    ListCard(onClick = onOpen) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    position?.let { "#$it" } ?: "Off",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (position != null) colors.autoReply else colors.paused,
                    modifier = Modifier.width(36.dp),
                )
                Text(rule.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Switch(checked = rule.enabled, onCheckedChange = onToggle)
                Box {
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More actions for ${rule.name}") }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text("Edit") }, onClick = { menuOpen = false; onOpen() })
                        DropdownMenuItem(text = { Text("Test in lab") }, onClick = { menuOpen = false; onTest() })
                        DropdownMenuItem(text = { Text("Duplicate") }, onClick = { menuOpen = false; onDuplicate() })
                        DropdownMenuItem(text = { Text("Delete", color = MaterialTheme.colorScheme.error) }, onClick = { menuOpen = false; onDelete() })
                    }
                }
            }
            Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(Spacing.sm))
            Row(verticalAlignment = Alignment.CenterVertically) {
                FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    val modeColor = when (rule.replyMode) {
                        ReplyMode.RULE_AUTOMATIC -> colors.autoReply
                        ReplyMode.MANUAL_APPROVAL -> colors.review
                        ReplyMode.AI_ASSISTED -> colors.simulated
                    }
                    Pill(rule.replyMode.label, modeColor)
                    Pill("Priority ${rule.priority}", colors.scheduled, outlined = true)
                    if (rule.cooldownMinutes > 0) Pill("Cooldown ${rule.cooldownMinutes} min", colors.paused, outlined = true)
                    if (rule.stopProcessing) Pill("Stop after match", colors.paused, outlined = true)
                    rule.maxRepliesPerDay?.let { Pill("Max $it/day", colors.paused, outlined = true) }
                    if (!rule.enabled) Pill("Off", colors.paused)
                }
                IconButton(onClick = { onMove(true) }, enabled = canMoveUp) { Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Check ${rule.name} earlier") }
                IconButton(onClick = { onMove(false) }, enabled = canMoveDown) { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Check ${rule.name} later") }
            }
        }
    }
}

