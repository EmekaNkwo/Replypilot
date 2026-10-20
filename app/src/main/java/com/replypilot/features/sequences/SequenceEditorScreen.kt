package com.replypilot.features.sequences

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.replypilot.core.design.BackTopBar
import com.replypilot.core.design.ConfirmDialog
import com.replypilot.core.design.Notice
import com.replypilot.core.design.NoticeTone
import com.replypilot.core.design.SectionHeader
import com.replypilot.core.design.Spacing
import com.replypilot.domain.model.MessageSequence
import com.replypilot.domain.model.MessageTemplate
import com.replypilot.domain.model.QuietHoursBehavior
import com.replypilot.domain.model.SequenceStep
import com.replypilot.features.templates.TemplateAnalysis
import com.replypilot.features.templates.VariableChips
import com.replypilot.features.templates.VariableWarnings
import com.replypilot.navigation.AppNavigator

@Composable
fun SequenceEditorScreen(nav: AppNavigator, id: String?) {
    val viewModel: SequenceEditorViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(id) { viewModel.load(id) }
    LaunchedEffect(state.finished) { if (state.finished) nav.back() }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.messageShown()
        }
    }

    Scaffold(
        topBar = {
            BackTopBar(if (state.isNew) "New sequence" else "Edit sequence", nav::back) {
                if (!state.isNew) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, contentDescription = "Delete sequence") }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (state.notFound) {
            Notice("This sequence no longer exists.", Modifier.padding(padding).padding(Spacing.lg))
            return@Scaffold
        }
        if (state.loading) return@Scaffold
        Column(
            Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            OutlinedTextField(
                value = state.name, onValueChange = viewModel::setName, label = { Text("Name") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OptionDropdown(
                label = "Channel",
                options = state.channels.map { it.id },
                selected = state.channel,
                optionLabel = { it.displayName },
                onSelect = viewModel::setChannel,
                modifier = Modifier.fillMaxWidth(),
            )
            if (!state.canSendUnattended) {
                Notice(
                    "${state.channel.displayName} can't send unattended. When a step is due you'll get a one-tap handoff to send it yourself.",
                    tone = NoticeTone.WARNING,
                )
            }
            OutlinedTextField(
                value = state.timeZone, onValueChange = viewModel::setTimeZone, label = { Text("Time zone") },
                isError = !state.timeZoneValid,
                supportingText = { Text(if (state.timeZoneValid) "Step times use this zone, or the contact's own zone when known." else "Use a zone like Africa/Lagos or Europe/London.") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )

            SectionHeader("Steps (${state.steps.size} of ${MessageSequence.MAX_STEPS})")
            state.steps.forEachIndexed { index, step ->
                StepCard(
                    index = index,
                    step = step,
                    count = state.steps.size,
                    templates = state.templates,
                    customFields = state.customFields,
                    viewModel = viewModel,
                )
            }
            if (state.steps.size < MessageSequence.MAX_STEPS) {
                OutlinedButton(onClick = viewModel::addStep, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.width(Spacing.sm))
                    Text("Add step")
                }
            }

            SectionHeader("Stop conditions")
            SwitchRow("Stop when they reply", "Remaining steps are cancelled as soon as the person writes back.", state.stopOnReply, viewModel::setStopOnReply)
            Notice("Opt-outs always stop a sequence. If someone replies STOP or is marked as opted out, no further steps are sent.")

            SectionHeader("Quiet hours")
            OptionDropdown(
                label = "If a step is due during quiet hours",
                options = QuietHoursBehavior.entries,
                selected = state.quietHours,
                optionLabel = { it.label },
                onSelect = viewModel::setQuietHours,
                modifier = Modifier.fillMaxWidth(),
            )

            if (state.errors.isNotEmpty()) {
                Notice(state.errors.joinToString("\n"), tone = NoticeTone.ERROR, title = "Fix these before saving")
            }
            Button(onClick = viewModel::save, enabled = !state.saving, modifier = Modifier.fillMaxWidth()) {
                Text(if (state.saving) "Saving…" else "Save sequence")
            }
            Spacer(Modifier.height(Spacing.xl))
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = "Delete sequence?",
            text = "Everyone currently in \"${state.name}\" is stopped and their remaining steps are cancelled.",
            confirmLabel = "Delete",
            onConfirm = viewModel::delete,
            onDismiss = { confirmDelete = false },
            destructive = true,
        )
    }
}

@Composable
private fun StepCard(
    index: Int,
    step: SequenceStep,
    count: Int,
    templates: List<MessageTemplate>,
    customFields: Set<String>,
    viewModel: SequenceEditorViewModel,
) {
    var templateMenu by remember { mutableStateOf(false) }
    var dayText by remember(step.dayOffset) { mutableStateOf(step.dayOffset.toString()) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Step ${index + 1}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                IconButton(onClick = { viewModel.moveStep(index, -1) }, enabled = index > 0) {
                    Icon(Icons.Filled.ArrowUpward, contentDescription = "Move step ${index + 1} up")
                }
                IconButton(onClick = { viewModel.moveStep(index, 1) }, enabled = index < count - 1) {
                    Icon(Icons.Filled.ArrowDownward, contentDescription = "Move step ${index + 1} down")
                }
                IconButton(onClick = { viewModel.removeStep(index) }, enabled = count > 1) {
                    Icon(Icons.Filled.Close, contentDescription = "Remove step ${index + 1}")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = dayText,
                    onValueChange = { text ->
                        dayText = text.filter(Char::isDigit).take(3)
                        dayText.toIntOrNull()?.let { day -> viewModel.updateStep(index) { it.copy(dayOffset = day) } }
                    },
                    label = { Text("Day") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(96.dp),
                )
                TimeField(step.time, { time -> viewModel.updateStep(index) { it.copy(time = time) } })
            }
            OutlinedTextField(
                value = step.body,
                onValueChange = { body -> viewModel.updateStep(index) { it.copy(body = body, templateId = null) } },
                label = { Text("Message") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            Row {
                TextButton(onClick = { templateMenu = true }, enabled = templates.isNotEmpty()) {
                    Text(if (templates.isEmpty()) "No templates yet" else "Use a template")
                }
                DropdownMenu(expanded = templateMenu, onDismissRequest = { templateMenu = false }) {
                    templates.forEach { template ->
                        DropdownMenuItem(text = { Text(template.name) }, onClick = {
                            viewModel.applyTemplate(index, template)
                            templateMenu = false
                        })
                    }
                }
            }
            VariableChips(customFields, { viewModel.insertVariable(index, it) })
            VariableWarnings(TemplateAnalysis.usages(step.body, customFields))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = step.requiresReview, onCheckedChange = { checked -> viewModel.updateStep(index) { it.copy(requiresReview = checked) } })
                Text("Review before sending — this step waits for your approval", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
internal fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
