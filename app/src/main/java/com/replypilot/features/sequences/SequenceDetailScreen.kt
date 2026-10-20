package com.replypilot.features.sequences

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.replypilot.core.design.BackTopBar
import com.replypilot.core.design.ConfirmDialog
import com.replypilot.core.design.Format
import com.replypilot.core.design.ListCard
import com.replypilot.core.design.LocalStatusColors
import com.replypilot.core.design.Notice
import com.replypilot.core.design.NoticeTone
import com.replypilot.core.design.Pill
import com.replypilot.core.design.SectionHeader
import com.replypilot.core.design.SimulatedPill
import com.replypilot.core.design.Spacing
import com.replypilot.core.design.StatusPill
import com.replypilot.domain.automation.EnrollResult
import com.replypilot.domain.automation.SequenceService
import com.replypilot.domain.model.EnrollmentStatus
import com.replypilot.domain.model.MessageSequence
import com.replypilot.navigation.AppNavigator
import com.replypilot.navigation.Routes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SequenceDetailScreen(nav: AppNavigator, id: String) {
    val viewModel: SequenceDetailViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val result by viewModel.result.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var picking by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf(setOf<String>()) }
    var confirmEnroll by rememberSaveable { mutableStateOf(false) }
    var stopId by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(id) { viewModel.load(id) }
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.messageShown()
        }
    }

    val sequence = state.sequence
    Scaffold(
        topBar = {
            BackTopBar(sequence?.name ?: "Sequence", nav::back) {
                if (sequence != null) {
                    IconButton(onClick = { nav.navigate(Routes.sequenceEditor(sequence.id)) }) {
                        Icon(Icons.Filled.Edit, contentDescription = "Edit sequence")
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (sequence == null) {
            if (state.loaded) Notice("This sequence no longer exists.", Modifier.padding(padding).padding(Spacing.lg))
            return@Scaffold
        }
        LazyColumn(
            Modifier.padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Pill(sequence.channel.displayName, MaterialTheme.colorScheme.primary)
                    if (sequence.stopOnReply) Pill("Stops on reply", LocalStatusColors.current.scheduled)
                    Pill("Stops on opt-out", LocalStatusColors.current.scheduled)
                    if (!sequence.enabled) Pill("Disabled", LocalStatusColors.current.paused)
                }
            }
            item { SectionHeader("Steps") }
            items(sequence.steps.size) { index ->
                val step = sequence.steps[index]
                Column(Modifier.fillMaxWidth().padding(vertical = Spacing.xs)) {
                    Text(
                        "Day ${step.dayOffset} · ${step.time}" + if (step.requiresReview) " · needs your review" else "",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(step.body, style = MaterialTheme.typography.bodyMedium, maxLines = 3)
                }
            }
            item {
                if (sequence.enabled) {
                    Button(onClick = { selected = emptySet(); picking = true }, modifier = Modifier.fillMaxWidth()) { Text("Enroll contacts") }
                } else {
                    Notice("Enable this sequence before enrolling anyone.", tone = NoticeTone.WARNING)
                }
            }
            item { SectionHeader("Enrollments (${state.enrollments.size})") }
            if (state.enrollments.isEmpty()) {
                item { Text("Nobody is in this sequence yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            items(state.enrollments, key = { it.enrollment.id }) { row ->
                EnrollmentCard(row, onStop = { stopId = row.enrollment.id }, onOpen = { nav.navigate(Routes.execution(it)) })
            }
        }
    }

    if (picking && sequence != null) {
        ModalBottomSheet(onDismissRequest = { picking = false }) {
            ContactPicker(
                state = state,
                selected = selected,
                onToggle = { cid -> selected = if (cid in selected) selected - cid else selected + cid },
                onContinue = { picking = false; confirmEnroll = true },
            )
        }
    }
    if (confirmEnroll && sequence != null) {
        val count = minOf(selected.size, SequenceService.MAX_ENROLL_PER_ACTION)
        val days = (sequence.steps.maxOfOrNull { it.dayOffset } ?: 0) + 1
        ConfirmDialog(
            title = "Start this sequence?",
            text = "$count ${if (count == 1) "person" else "people"} will each receive up to ${sequence.steps.size} " +
                "message${if (sequence.steps.size == 1) "" else "s"} over $days day(s) on ${sequence.channel.displayName}. " +
                "You are starting this yourself; it stops if they reply${if (sequence.stopOnReply) "" else " (if enabled)"} or opt out." +
                if (selected.size > SequenceService.MAX_ENROLL_PER_ACTION) " Only the first ${SequenceService.MAX_ENROLL_PER_ACTION} are enrolled per action." else "",
            confirmLabel = "Start",
            onConfirm = { viewModel.enroll(selected) },
            onDismiss = { confirmEnroll = false },
        )
    }
    result?.let { EnrollResultDialog(it, viewModel::resultShown) }
    stopId?.let { enrollmentId ->
        ConfirmDialog(
            title = "Stop for this person?",
            text = "Remaining steps are cancelled. Messages already sent are not affected.",
            confirmLabel = "Stop",
            onConfirm = { viewModel.stop(enrollmentId) },
            onDismiss = { stopId = null },
            destructive = true,
        )
    }
}

@Composable
private fun ContactPicker(state: SequenceDetailState, selected: Set<String>, onToggle: (String) -> Unit, onContinue: () -> Unit) {
    val sequence: MessageSequence = state.sequence ?: return
    Column(Modifier.padding(horizontal = Spacing.lg)) {
        Text("Choose contacts", style = MaterialTheme.typography.titleMedium)
        Text(
            "Only contacts with a ${sequence.channel.displayName} address can be enrolled. Up to ${SequenceService.MAX_ENROLL_PER_ACTION} at a time.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.contacts.isEmpty()) {
            Text("You have no contacts yet. Add some in Contacts first.", modifier = Modifier.padding(vertical = Spacing.lg))
        }
        LazyColumn(Modifier.heightIn(max = 420.dp)) {
            items(state.contacts, key = { it.contact.id }) { item ->
                val enabled = item.blockedReason == null
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .clickable(enabled = enabled, role = Role.Checkbox) { onToggle(item.contact.id) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = item.contact.id in selected, onCheckedChange = { onToggle(item.contact.id) }, enabled = enabled)
                    Column(Modifier.weight(1f)) {
                        Text(item.contact.displayName, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            item.blockedReason ?: item.address.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
        Button(onClick = onContinue, enabled = selected.isNotEmpty(), modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.md)) {
            Text("Continue with ${selected.size}")
        }
    }
}

@Composable
private fun EnrollmentCard(row: EnrollmentRow, onStop: () -> Unit, onOpen: (String) -> Unit) {
    val e = row.enrollment
    val colors = LocalStatusColors.current
    ListCard(onClick = null) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(e.recipient.displayName, style = MaterialTheme.typography.titleSmall)
                    Text("Started ${Format.dateTime(e.startedAt)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val color = when (e.status) {
                    EnrollmentStatus.ACTIVE -> colors.scheduled
                    EnrollmentStatus.COMPLETED -> colors.success
                    EnrollmentStatus.STOPPED -> colors.paused
                }
                Pill(e.status.label, color)
            }
            e.stopReason?.let { Text("Stopped: $it", style = MaterialTheme.typography.bodySmall) }
            row.executions.forEach { item ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 40.dp).clickable { onOpen(item.id) },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    Text("Step ${item.occurrenceIndex + 1}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    Text(Format.dateTime(item.plannedAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    StatusPill(item.status)
                    if (item.simulated) SimulatedPill()
                }
            }
            if (e.status == EnrollmentStatus.ACTIVE) {
                TextButton(onClick = onStop) { Text("Stop for ${e.recipient.displayName}") }
            }
        }
    }
}

@Composable
private fun EnrollResultDialog(result: EnrollResult, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${result.enrolled.size} enrolled") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                if (result.enrolled.isNotEmpty()) Text(result.enrolled.joinToString { it.recipient.displayName })
                if (result.skipped.isNotEmpty()) {
                    Text("Skipped:", style = MaterialTheme.typography.titleSmall)
                    result.skipped.forEach { (recipient, reason) -> Text("• ${recipient.displayName} — $reason", style = MaterialTheme.typography.bodySmall) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
    )
}
