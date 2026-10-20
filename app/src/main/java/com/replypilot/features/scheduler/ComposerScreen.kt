package com.replypilot.features.scheduler

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.replypilot.core.accessibility.ChatApp
import com.replypilot.core.design.BackTopBar
import com.replypilot.core.design.ConfirmDialog
import com.replypilot.core.design.SectionHeader
import com.replypilot.core.design.Spacing
import com.replypilot.domain.model.ChannelCapability
import com.replypilot.domain.model.ZoneMode
import com.replypilot.domain.scheduling.Severity
import com.replypilot.domain.scheduling.ValidationField
import com.replypilot.domain.scheduling.VariableResolver
import com.replypilot.domain.templates.KnownVariables
import com.replypilot.domain.templates.TemplateRenderer
import com.replypilot.navigation.AppNavigator
import com.replypilot.navigation.Routes
import java.time.ZoneId
import kotlinx.coroutines.launch

private enum class Dialog { CONTACTS, TEMPLATES, DATE, TIME, ZONE, END_DATE, SKIP_DATE, DISCARD }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ComposerScreen(nav: AppNavigator, scheduleId: String?, templateId: String?, duplicateOf: String?) {
    val viewModel: ComposerViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val assistant by viewModel.assistantState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var dialog by remember { mutableStateOf<Dialog?>(null) }
    var showAssistant by remember { mutableStateOf(false) }
    val form = state.form

    // Local text state so variables insert at the cursor; replaced when the body changes from outside.
    var bodyField by remember { mutableStateOf(TextFieldValue("")) }
    LaunchedEffect(form.bodyVersion, state.loading) {
        if (!state.loading) bodyField = TextFieldValue(form.body, TextRange(form.body.length))
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is ComposerEvent.Message -> snackbar.showSnackbar(event.text)
                is ComposerEvent.Saved -> {
                    snackbar.showSnackbar(event.text)
                    nav.back()
                }
            }
        }
    }

    val leave = { if (state.dirty) dialog = Dialog.DISCARD else nav.back() }
    BackHandler(enabled = state.dirty) { dialog = Dialog.DISCARD }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            BackTopBar(if (state.isEditing) "Edit schedule" else "Schedule message", onBack = leave) {
                TextButton(onClick = viewModel::save, enabled = !state.saving && !state.loading) { Text("Save") }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (state.loading) {
            Column(Modifier.fillMaxSize().padding(padding), horizontalAlignment = Alignment.CenterHorizontally) { CircularProgressIndicator(Modifier.padding(32.dp)) }
            return@Scaffold
        }
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            OutlinedTextField(
                value = form.title, onValueChange = { v -> viewModel.edit { it.copy(title = v) } },
                label = { Text("Title (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )

            SectionHeader("Channel")
            ChannelSection(state.channels, form.channel, viewModel::setChannel)
            // The channel card already explains manual-only channels; show only other channel problems here.
            FieldIssues(state.issues.filterNot { it.message.contains("can't send unattended") }, ValidationField.CHANNEL, state.showErrors)

            SectionHeader("To")
            RecipientSection(
                form.channel, form.recipients,
                sources = {
                    RecipientSources(
                        channel = form.channel,
                        groupChatsSupported = state.channels.firstOrNull { it.id == form.channel }?.descriptor
                            ?.supports(ChannelCapability.GROUP_CONVERSATIONS) == true,
                        savedContacts = state.contacts,
                        phone = viewModel.phoneContacts,
                        onPickSaved = { dialog = Dialog.CONTACTS },
                        onAdd = { added -> viewModel.addRecipients(added)?.let { msg -> scope.launch { snackbar.showSnackbar(msg) } } },
                        onMessage = { msg -> scope.launch { snackbar.showSnackbar(msg) } },
                        onPickChat = if (ChatApp.forChannel(form.channel) != null) ({ viewModel.pickChat() }) else null,
                    )
                },
                onAddManual = viewModel::addManual, onRemove = viewModel::removeRecipient,
            )
            FieldIssues(state.issues, ValidationField.RECIPIENTS, state.showErrors)

            SectionHeader("Message")
            val limit = state.channelStatus?.descriptor?.maxMessageLength
            OutlinedTextField(
                value = bodyField,
                onValueChange = { bodyField = it; viewModel.setBody(it.text) },
                modifier = Modifier.fillMaxWidth(), minLines = 4,
                label = { Text(if (form.attachments.isEmpty()) "Message" else "Caption (optional)") },
                supportingText = { Text("${bodyField.text.length}${limit?.let { " / $it" } ?: ""} characters before personalization") },
            )
            val customFields = state.contacts.flatMap { it.customFields.keys }.toSet() - KnownVariables.names
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                (KnownVariables.names + customFields).forEach { name ->
                    AssistChip(onClick = {
                        val token = "{{$name}}"
                        val sel = bodyField.selection
                        val text = bodyField.text.replaceRange(sel.min, sel.max, token)
                        bodyField = TextFieldValue(text, TextRange(sel.min + token.length))
                        viewModel.setBody(text)
                    }, label = { Text(name) })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                OutlinedButton(onClick = { dialog = Dialog.TEMPLATES }) { Text("Use template") }
                OutlinedButton(onClick = { showAssistant = true; viewModel.openAssistant() }) {
                    Icon(Icons.Filled.AutoAwesome, contentDescription = null)
                    Text(" Assistant")
                }
            }
            FieldIssues(state.issues, ValidationField.BODY, state.showErrors)

            AttachmentSection(
                attachments = form.attachments,
                importing = state.importing,
                supported = state.channelStatus?.descriptor?.supports(ChannelCapability.ATTACHMENTS) != false,
                channelName = form.channel.displayName,
                onAdd = viewModel::attachFiles,
                onRemove = viewModel::removeAttachment,
            )
            FieldIssues(state.issues, ValidationField.ATTACHMENTS, state.showErrors)

            if (form.body.isNotBlank()) {
                val first = form.recipients.firstOrNull()
                if (first != null) {
                    val contact = first.contactId?.let { id -> state.contacts.firstOrNull { it.id == id } }
                    val rendered = TemplateRenderer.render(form.body, VariableResolver.forRecipient(first, contact, state.settings))
                    PreviewCard("Preview for ${first.displayName}", rendered.text, rendered.unresolved, limit)
                }
                val sample = TemplateRenderer.render(form.body, KnownVariables.samples + VariableResolver.global(state.settings))
                PreviewCard("Preview with sample values", sample.text, sample.unresolved, limit)
            }

            SectionHeader("When")
            WhenSection(
                form, onEdit = viewModel::edit,
                onPickDate = { dialog = Dialog.DATE }, onPickTime = { dialog = Dialog.TIME }, onPickZone = { dialog = Dialog.ZONE },
                onPickEndDate = { dialog = Dialog.END_DATE }, onAddSkipDate = { dialog = Dialog.SKIP_DATE },
            )
            FieldIssues(state.issues, ValidationField.SCHEDULE, state.showErrors)

            SectionHeader("Upcoming sends")
            val previewZone = if (form.zoneMode == ZoneMode.FOLLOW_DEVICE) ZoneId.systemDefault() else viewModel.zone()
            UpcomingPreview(form, state.upcoming, previewZone)

            SectionHeader("Delivery rules")
            DeliverySection(form, viewModel::edit)

            FieldIssues(state.issues, ValidationField.GENERAL, true)
            val errorCount = state.issues.count { it.severity == Severity.ERROR }
            if (state.showErrors && errorCount > 0) {
                Text("$errorCount problem${if (errorCount == 1) "" else "s"} to fix before saving.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = viewModel::save, enabled = !state.saving, modifier = Modifier.fillMaxWidth()) {
                Text(if (form.sendNow) "Send now" else if (state.isEditing) "Save changes" else "Save schedule")
            }
        }
    }

    if (state.pickedChats.isNotEmpty()) {
        ConfirmChatsDialog(
            channelName = form.channel.displayName,
            names = state.pickedChats,
            onConfirm = viewModel::confirmPickedChats,
            onDismiss = viewModel::dismissPickedChats,
        )
    }

    when (dialog) {
        Dialog.CONTACTS -> ContactPickDialog(
            state.contacts, form.channel, form.recipients.mapNotNull { it.contactId }.toSet(),
            onToggle = viewModel::toggleContact, onDismiss = { dialog = null },
        )
        Dialog.TEMPLATES -> TemplatePickDialog(state.templates, onPick = viewModel::applyTemplate, onDismiss = { dialog = null })
        Dialog.DATE -> DatePickDialog(form.date, onPick = { d -> viewModel.edit { it.copy(date = d) } }, onDismiss = { dialog = null })
        Dialog.TIME -> TimePickDialog(form.time, onPick = { t -> viewModel.edit { it.copy(time = t) } }, onDismiss = { dialog = null })
        Dialog.ZONE -> ZonePickDialog(form.zoneId, onPick = { z -> viewModel.edit { it.copy(zoneId = z) } }, onDismiss = { dialog = null })
        Dialog.END_DATE -> DatePickDialog(form.endDate ?: form.date, onPick = { d -> viewModel.edit { it.copy(endDate = d) } }, onDismiss = { dialog = null })
        Dialog.SKIP_DATE -> DatePickDialog(form.date, onPick = { d -> viewModel.edit { it.copy(skipDates = it.skipDates + d) } }, onDismiss = { dialog = null })
        Dialog.DISCARD -> ConfirmDialog(
            "Discard changes?", "Your edits to this message haven't been saved.", "Discard",
            onConfirm = nav::back, onDismiss = { dialog = null }, destructive = true,
        )
        null -> Unit
    }

    if (showAssistant) {
        AssistantSheet(
            assistant, hasText = form.body.isNotBlank(),
            onRun = viewModel::runAssistant,
            onUse = { viewModel.replaceBody(it); showAssistant = false },
            onOpenSettings = { showAssistant = false; nav.navigate(Routes.SETTINGS_ASSISTANT) },
            onDismiss = { showAssistant = false },
        )
    }
}
