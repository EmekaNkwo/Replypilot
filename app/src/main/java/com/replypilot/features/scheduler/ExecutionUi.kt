package com.replypilot.features.scheduler

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.replypilot.core.design.ChannelAvatar
import com.replypilot.core.design.ConfirmDialog
import com.replypilot.core.design.Format
import com.replypilot.core.design.ListCard
import com.replypilot.core.design.Pill
import com.replypilot.core.design.LocalStatusColors
import com.replypilot.core.design.SimulatedPill
import com.replypilot.core.design.Spacing
import com.replypilot.core.design.StatusPill
import com.replypilot.core.handoff.HandoffLauncher
import com.replypilot.domain.model.ExecutionItem
import com.replypilot.domain.model.ExecutionSource
import com.replypilot.domain.model.FailureCode
import com.replypilot.domain.model.MessageStatus
import com.replypilot.domain.scheduling.ActionResult
import com.replypilot.domain.scheduling.QueueActions

enum class ExecutionAction(val label: String) {
    APPROVE("Approve"),
    REJECT("Reject"),
    MARK_SENT("Mark as sent"),
    RETRY("Retry"),
    CANCEL("Cancel"),
}

/** Which user actions make sense for a row in a given state (the domain re-validates every one). */
fun actionsFor(status: MessageStatus): List<ExecutionAction> = when (status) {
    MessageStatus.AWAITING_REVIEW -> listOf(ExecutionAction.APPROVE, ExecutionAction.REJECT)
    MessageStatus.NEEDS_ATTENTION -> listOf(ExecutionAction.MARK_SENT, ExecutionAction.RETRY, ExecutionAction.CANCEL)
    MessageStatus.FAILED -> listOf(ExecutionAction.RETRY)
    MessageStatus.SCHEDULED, MessageStatus.DUE, MessageStatus.RETRY_SCHEDULED, MessageStatus.PAUSED -> listOf(ExecutionAction.CANCEL)
    else -> emptyList()
}

suspend fun QueueActions.perform(id: String, action: ExecutionAction, editedBody: String? = null): String {
    val result = when (action) {
        ExecutionAction.APPROVE -> approve(id, editedBody)
        ExecutionAction.REJECT -> reject(id)
        ExecutionAction.MARK_SENT -> markSentManually(id)
        ExecutionAction.RETRY -> retry(id)
        ExecutionAction.CANCEL -> cancel(id)
    }
    return when (result) {
        ActionResult.Done -> when (action) {
            ExecutionAction.APPROVE -> "Approved — it will be sent shortly"
            ExecutionAction.REJECT -> "Reply rejected"
            ExecutionAction.MARK_SENT -> "Marked as sent"
            ExecutionAction.RETRY -> "Queued for another attempt"
            ExecutionAction.CANCEL -> "Message cancelled"
        }
        is ActionResult.Rejected -> result.reason
    }
}

fun sourceLabel(item: ExecutionItem): String = when (item.source) {
    ExecutionSource.SCHEDULED -> "Scheduled"
    ExecutionSource.SEQUENCE -> "Sequence step ${item.occurrenceIndex + 1}"
    ExecutionSource.AUTO_REPLY -> "Auto-reply"
}

/** One-line timing description appropriate to the row's state. */
fun timingLabel(item: ExecutionItem, now: Long): String = when (item.status) {
    MessageStatus.SUBMITTED, MessageStatus.DELIVERED -> "Sent ${Format.dateTime(item.lastAttemptAt ?: item.updatedAt)}"
    MessageStatus.RETRY_SCHEDULED -> "Retry ${Format.relative(item.nextAttemptAt, now)}"
    MessageStatus.SCHEDULED, MessageStatus.DUE, MessageStatus.PAUSED ->
        if (item.nextAttemptAt > item.plannedAt) "Waiting until ${Format.dateTime(item.nextAttemptAt)}" else Format.dateTime(item.plannedAt)
    else -> "Updated ${Format.relative(item.updatedAt, now)}"
}

/** Approve with optional edit. */
@Composable
fun ApproveDialog(initialBody: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initialBody) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Approve message") },
        text = {
            Column {
                Text("Edit the text if needed. It will be sent as soon as the queue runs.", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(Spacing.sm))
                OutlinedTextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth(), minLines = 3, label = { Text("Message") })
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text); onDismiss() }, enabled = text.isNotBlank()) { Text("Approve & send") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Hosts the dialogs needed before an action runs (approve/edit, confirm cancel, confirm retry of an
 * interrupted send) and forwards the final decision.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExecutionActionButtons(
    item: ExecutionItem,
    onAction: (ExecutionAction, String?) -> Unit,
    onHandoff: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var approving by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf<ExecutionAction?>(null) }
    val actions = actionsFor(item.status)
    val canHandoff = item.handoff != null && item.status == MessageStatus.NEEDS_ATTENTION
    if (actions.isEmpty() && !canHandoff) return

    FlowRow(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        if (canHandoff) TextButton(onClick = onHandoff) { Text("Send manually") }
        actions.forEach { action ->
            TextButton(onClick = {
                when {
                    action == ExecutionAction.APPROVE -> approving = true
                    action == ExecutionAction.CANCEL || action == ExecutionAction.REJECT -> confirming = action
                    action == ExecutionAction.RETRY && item.failureCode == FailureCode.INTERRUPTED -> confirming = action
                    else -> onAction(action, null)
                }
            }) { Text(action.label) }
        }
    }
    if (approving) ApproveDialog(item.body, onConfirm = { onAction(ExecutionAction.APPROVE, it) }, onDismiss = { approving = false })
    confirming?.let { action ->
        val (title, text) = when (action) {
            ExecutionAction.RETRY -> "Retry an interrupted send?" to
                "The app stopped while this was being sent, so it may already have reached ${item.recipient.displayName}. Check the conversation first to avoid sending it twice."
            ExecutionAction.REJECT -> "Reject this reply?" to "It won't be sent. The sender won't be notified."
            else -> "Cancel this message?" to "It won't be sent to ${item.recipient.displayName}. This can't be undone."
        }
        ConfirmDialog(
            title = title, text = text,
            // Never show two buttons with the same word: "Cancel" here means cancelling the message.
            confirmLabel = when (action) {
                ExecutionAction.CANCEL -> "Cancel message"
                ExecutionAction.REJECT -> "Reject reply"
                else -> action.label
            },
            dismissLabel = if (action == ExecutionAction.RETRY) "Not now" else "Keep it",
            onConfirm = { onAction(action, null) }, onDismiss = { confirming = null },
            destructive = action != ExecutionAction.RETRY,
        )
    }
}

@Composable
fun ExecutionRow(
    item: ExecutionItem,
    now: Long,
    onOpen: () -> Unit,
    onAction: (ExecutionAction, String?) -> Unit,
    onHandoff: () -> Unit,
    showActions: Boolean = true,
) {
    ListCard(onClick = onOpen) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ChannelAvatar(item.channel)
                Spacer(Modifier.width(Spacing.md))
                Column(Modifier.weight(1f)) {
                    Text(item.recipient.displayName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${sourceLabel(item)} · ${timingLabel(item, now)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                StatusPill(item.status)
            }
            Spacer(Modifier.height(Spacing.sm))
            Text(item.body, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            item.statusReason?.takeIf { item.status !in MessageStatus.SUCCESS || item.simulated }?.let {
                Spacer(Modifier.height(Spacing.xs))
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (item.simulated || item.source == ExecutionSource.AUTO_REPLY) {
                Spacer(Modifier.height(Spacing.xs))
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    if (item.source == ExecutionSource.AUTO_REPLY) Pill("Auto-reply", LocalStatusColors.current.autoReply)
                    if (item.simulated) SimulatedPill()
                }
            }
            if (showActions) ExecutionActionButtons(item, onAction, onHandoff)
        }
    }
}
