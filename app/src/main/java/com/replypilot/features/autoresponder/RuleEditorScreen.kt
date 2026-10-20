package com.replypilot.features.autoresponder

import android.app.Activity
import android.content.Intent
import android.provider.ContactsContract
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.replypilot.core.accessibility.ChatApp
import com.replypilot.core.design.BackTopBar
import com.replypilot.core.design.ConfirmDialog
import com.replypilot.core.design.EmptyState
import com.replypilot.core.design.LocalStatusColors
import com.replypilot.core.design.Notice
import com.replypilot.core.design.NoticeTone
import com.replypilot.core.design.Spacing
import com.replypilot.domain.model.ChannelId
import com.replypilot.domain.model.ConditionCombinator
import com.replypilot.domain.model.ReplyMode
import com.replypilot.domain.templates.KnownVariables
import com.replypilot.domain.templates.TemplateRenderer
import com.replypilot.features.rules.AddConditionButton
import com.replypilot.features.rules.ConditionCard
import com.replypilot.features.rules.ConditionContext
import com.replypilot.features.rules.DropdownSelector
import com.replypilot.features.rules.NumberField
import com.replypilot.features.rules.OptionalNumberField
import com.replypilot.features.rules.RuleEditorState
import com.replypilot.features.rules.RuleEditorViewModel
import com.replypilot.features.rules.SwitchRow
import com.replypilot.navigation.AppNavigator
import com.replypilot.navigation.Routes

@Composable
fun RuleEditorScreen(nav: AppNavigator, ruleId: String?) {
    val viewModel: RuleEditorViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var confirmDiscard by remember { mutableStateOf(false) }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                RuleEditorViewModel.EditorEvent.Saved -> nav.back()
                is RuleEditorViewModel.EditorEvent.Message -> snackbar.showSnackbar(event.text)
            }
        }
    }
    fun leave() {
        if (state.dirty) confirmDiscard = true else nav.back()
    }
    BackHandler(enabled = state.dirty) { confirmDiscard = true }

    Scaffold(
        topBar = {
            BackTopBar(if (ruleId == null) "New rule" else "Edit rule", onBack = ::leave) {
                TextButton(onClick = viewModel::save, enabled = !state.saving && !state.loading) { Text("Save") }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        when {
            state.loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            state.notFound -> EmptyState(Icons.Filled.SearchOff, "Rule not found", "It may have been deleted.", Modifier.padding(padding))
            else -> EditorContent(state, viewModel, nav, Modifier.padding(padding))
        }
    }

    if (confirmDiscard) {
        ConfirmDialog(
            title = "Discard changes?",
            text = "Your edits to this rule haven't been saved.",
            confirmLabel = "Discard",
            destructive = true,
            onConfirm = { nav.back() },
            onDismiss = { confirmDiscard = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorContent(state: RuleEditorState, vm: RuleEditorViewModel, nav: AppNavigator, modifier: Modifier) {
    val draft = state.draft
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(horizontal = Spacing.lg, vertical = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        OutlinedTextField(
            value = draft.name,
            onValueChange = { name -> vm.update { it.copy(name = name) } },
            label = { Text("Rule name") },
            placeholder = { Text("e.g. Pricing questions") },
            isError = state.showErrors && draft.name.isBlank(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        SwitchRow("Rule is on", draft.enabled, { on -> vm.update { it.copy(enabled = on) } }, detail = "Switched-off rules are kept but never run.")
        if (state.showErrors && state.errors.isNotEmpty()) {
            Notice(state.errors.joinToString("\n") { "• $it" }, tone = NoticeTone.ERROR, title = "Before saving")
        }

        Block("WHEN", "An incoming message arrives on…") { WhenSection(state, vm, nav) }
        Block("IF", if (draft.conditions.isEmpty()) "No conditions — every message matches" else "These conditions are true") { IfSection(state, vm) }
        Block("THEN", "ReplyPilot will…") { ThenSection(state, vm) }
        Block("TEST", "Try a sample message") { TestSection(state, vm) }

        Button(onClick = vm::save, enabled = !state.saving, modifier = Modifier.fillMaxWidth()) { Text(if (state.saving) "Saving…" else "Save rule") }
        Spacer(Modifier.height(Spacing.xl))
    }
}

@Composable
private fun Block(keyword: String, subtitle: String, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(keyword, style = MaterialTheme.typography.titleMedium, color = LocalStatusColors.current.autoReply)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            content()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WhenSection(state: RuleEditorState, vm: RuleEditorViewModel, nav: AppNavigator) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        state.receivingChannels.forEach { channel ->
            FilterChip(
                selected = channel in state.draft.channels,
                onClick = { vm.toggleChannel(channel) },
                label = { Text(channel.displayName) },
            )
        }
    }
    Text(
        if (state.draft.channels.isEmpty()) "No channel selected: the rule applies to every channel that receives messages." else "Only messages on the selected channels are checked.",
        style = MaterialTheme.typography.bodySmall,
    )
    val notReady = state.receivingChannels.filter { it in state.draft.channels.ifEmpty { state.receivingChannels.toSet() } && !state.canAutoReplyNow(it) }
    if (notReady.isNotEmpty()) {
        Notice(
            notReady.joinToString("\n") { "${it.displayName}: ${state.channelStatus[it]?.detail.orEmpty()}" },
            tone = NoticeTone.WARNING,
            title = "Can't auto-reply yet",
            action = { TextButton(onClick = { nav.navigate(Routes.SETTINGS_CHANNELS) }) { Text("Set up channels") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IfSection(state: RuleEditorState, vm: RuleEditorViewModel) {
    val draft = state.draft
    if (draft.conditions.size > 1) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            ConditionCombinator.entries.forEachIndexed { i, combinator ->
                SegmentedButton(
                    selected = draft.combinator == combinator,
                    onClick = { vm.update { it.copy(combinator = combinator) } },
                    shape = SegmentedButtonDefaults.itemShape(i, ConditionCombinator.entries.size),
                ) { Text(combinator.label) }
            }
        }
    }
    var phoneCallback by remember { mutableStateOf<((String) -> Unit)?>(null) }
    val pickPhone = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        val callback = phoneCallback
        phoneCallback = null
        if (result.resultCode == Activity.RESULT_OK && uri != null && callback != null) vm.savePhoneContact(uri, callback)
    }
    val chatApps = ChatApp.entries.filter { draft.channels.isEmpty() || it.channel in draft.channels }
    val context = ConditionContext(
        state.contacts, state.groups, state.otherRules, state.settings,
        pickFromPhone = { onSaved ->
            phoneCallback = onSaved
            pickPhone.launch(vm.phoneContacts.pickIntent(ChannelId.WHATSAPP))
        },
        chatApps = chatApps,
        pickChat = vm::pickChatContact,
    )
    draft.conditions.forEachIndexed { index, condition ->
        ConditionCard(index, condition, context, state.showErrors, { vm.updateCondition(index, it) }, { vm.removeCondition(index) })
    }
    AddConditionButton(vm::addCondition)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ThenSection(state: RuleEditorState, vm: RuleEditorViewModel) {
    val draft = state.draft
    ReplyMode.entries.forEach { mode ->
        Row(
            Modifier.fillMaxWidth().selectable(selected = draft.replyMode == mode, role = Role.RadioButton, onClick = { vm.update { it.copy(replyMode = mode) } }),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = draft.replyMode == mode, onClick = null)
            Column(Modifier.padding(start = Spacing.sm)) {
                Text(mode.label, style = MaterialTheme.typography.bodyLarge)
                Text(mode.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (draft.replyMode == ReplyMode.AI_ASSISTED) {
        Notice(
            if (state.settings.aiAutoSend) {
                "AI drafts above ${(state.settings.aiMinConfidence * 100).toInt()}% confidence from a real provider may be sent automatically. Mock drafts always wait for you. The text below guides the AI."
            } else {
                "AI drafts always wait for your approval (auto-send is off). If no AI provider is set up, this rule is skipped and the reason is logged. The text below guides the AI."
            },
            tone = NoticeTone.SIMULATED,
            title = "AI-assisted",
        )
    }

    var useTemplate by rememberSaveable(draft.templateId != null) { mutableStateOf(draft.templateId != null) }
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        listOf(false, true).forEachIndexed { i, template ->
            SegmentedButton(
                selected = useTemplate == template,
                onClick = {
                    useTemplate = template
                    if (!template) vm.update { it.copy(templateId = null) }
                },
                shape = SegmentedButtonDefaults.itemShape(i, 2),
            ) { Text(if (template) "Use a template" else "Write a reply") }
        }
    }
    if (useTemplate) {
        if (state.templates.isEmpty()) {
            Text("No templates yet. Create one in the Templates tab.", style = MaterialTheme.typography.bodySmall)
        } else {
            DropdownSelector(
                label = "Template",
                selected = state.templates.firstOrNull { it.id == draft.templateId },
                options = state.templates,
                optionLabel = { it?.let { t -> "${t.name} · ${t.category.label}" } ?: "Choose a template" },
                onSelect = { t -> t?.let { picked -> vm.update { it.copy(templateId = picked.id) } } },
            )
        }
    } else {
        OutlinedTextField(
            value = draft.response,
            onValueChange = { text -> vm.update { it.copy(response = text) } },
            label = { Text(if (draft.replyMode == ReplyMode.AI_ASSISTED) "Guidance for the AI" else "Reply") },
            isError = state.showErrors && draft.response.isBlank(),
            minLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            (KnownVariables.all.map { it.name } + "senderName").forEach { name ->
                AssistChip(onClick = { vm.update { it.copy(response = it.response + "{{$name}}") } }, label = { Text("{{$name}}") })
            }
        }
    }
    ResponsePreview(state)

    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        NumberField("Cooldown", draft.cooldownMinutes, { v -> vm.update { it.copy(cooldownMinutes = v) } }, Modifier.weight(1f), max = 10_080, suffix = "min")
        NumberField("Delay", draft.delaySeconds, { v -> vm.update { it.copy(delaySeconds = v) } }, Modifier.weight(1f), max = 3_600, suffix = "s")
    }
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        OptionalNumberField("Max replies per day", draft.maxRepliesPerDay, { v -> vm.update { it.copy(maxRepliesPerDay = v) } }, Modifier.weight(1f))
        NumberField("Priority", draft.priority, { v -> vm.update { it.copy(priority = v) } }, Modifier.weight(1f), max = 10_000)
    }
    Text("Cooldown is per conversation. Higher priority rules are checked first.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    SwitchRow(
        "Stop after this rule matches",
        draft.stopProcessing,
        { on -> vm.update { it.copy(stopProcessing = on) } },
        detail = "When off, lower-priority rules may also reply to the same message (max 3 replies).",
    )
}

@Composable
private fun ResponsePreview(state: RuleEditorState) {
    val body = state.draft.templateId?.let { id -> state.templates.firstOrNull { it.id == id }?.body } ?: state.draft.response
    if (body.isBlank()) return
    val rendered = TemplateRenderer.render(body, KnownVariables.samples + ("senderName" to "Ada Okafor") + ("businessName" to state.settings.businessName.ifBlank { KnownVariables.samples["businessName"].orEmpty() }))
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(Spacing.md)) {
            Text("Preview with sample values", style = MaterialTheme.typography.labelMedium)
            Text(rendered.text, style = MaterialTheme.typography.bodyLarge)
            if (!rendered.isComplete) {
                Text(
                    "Unknown placeholders: ${rendered.unresolved.joinToString { "{{$it}}" }}. A reply with missing values is never sent — add a fallback like {{firstName|there}}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun TestSection(state: RuleEditorState, vm: RuleEditorViewModel) {
    val test = state.test
    OutlinedTextField(
        value = test.text,
        onValueChange = { text -> vm.updateTest { it.copy(text = text) } },
        label = { Text("Sample incoming message") },
        placeholder = { Text("Hi, how much is your service?") },
        modifier = Modifier.fillMaxWidth(),
    )
    DropdownSelector(
        label = "From",
        selected = state.contacts.firstOrNull { it.id == test.contactId },
        options = listOf(null) + state.contacts,
        optionLabel = { it?.displayName ?: "Someone not in my contacts" },
        onSelect = { c -> vm.updateTest { it.copy(contactId = c?.id) } },
    )
    if (test.contactId == null) {
        OutlinedTextField(
            value = test.senderName,
            onValueChange = { name -> vm.updateTest { it.copy(senderName = name) } },
            label = { Text("Sender name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    SwitchRow("Include my other rules", test.includeOtherRules, { on -> vm.updateTest { it.copy(includeOtherRules = on) } }, detail = "See whether a higher-priority rule would answer first.")
    Button(onClick = vm::runTest, enabled = test.text.isNotBlank()) { Text("Run test") }
    test.result?.let { EvaluationView(it) }
}
