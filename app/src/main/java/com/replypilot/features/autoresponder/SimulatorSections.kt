package com.replypilot.features.autoresponder

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.replypilot.core.design.ListCard
import com.replypilot.core.design.LocalStatusColors
import com.replypilot.core.design.Notice
import com.replypilot.core.design.NoticeTone
import com.replypilot.core.design.SectionHeader
import com.replypilot.core.design.Spacing
import com.replypilot.core.design.StatusPill
import com.replypilot.domain.simulation.QueueScenario
import com.replypilot.features.rules.DropdownSelector
import com.replypilot.features.rules.NumberField
import com.replypilot.features.rules.SwitchRow
import com.replypilot.integrations.demo.DemoOutcome
import com.replypilot.navigation.AppNavigator
import com.replypilot.navigation.InsightsTab
import com.replypilot.navigation.MessagesTab
import com.replypilot.navigation.Routes
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RuleTestSection(state: SimulatorState, vm: SimulatorViewModel) {
    val form = state.form
    var pickDate by remember { mutableStateOf(false) }
    var pickTime by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Notice("Describe a situation and see exactly how your rules respond. Nothing is sent and no history is written.", tone = NoticeTone.SIMULATED, title = "Simulation only")
        OutlinedTextField(form.text, { t -> vm.updateForm { it.copy(text = t) } }, label = { Text("Incoming message") }, modifier = Modifier.fillMaxWidth())
        DropdownSelector("Channel", form.channel, state.receivingChannels, { it.displayName }, { c -> vm.updateForm { it.copy(channel = c) } })
        DropdownSelector(
            "From",
            state.contacts.firstOrNull { it.id == form.contactId },
            listOf(null) + state.contacts,
            { it?.displayName ?: "Someone not in my contacts" },
            { c -> vm.updateForm { it.copy(contactId = c?.id) } },
        )
        if (form.contactId == null) {
            OutlinedTextField(form.senderName, { n -> vm.updateForm { it.copy(senderName = n) } }, label = { Text("Sender name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            OutlinedButton(onClick = { pickDate = true }) { Text(form.date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))) }
            OutlinedButton(onClick = { pickTime = true }) { Text(form.time.toString()) }
            TextButton(onClick = vm::resetTime) { Text("Now") }
        }
        SectionHeader("Situation")
        SwitchRow("Group chat", form.isGroup, { v -> vm.updateForm { it.copy(isGroup = v) } })
        SwitchRow("ReplyPilot already replied in this chat", form.repliedRecently, { v -> vm.updateForm { it.copy(repliedRecently = v) } }, detail = "Tests cooldowns")
        if (form.repliedRecently) {
            NumberField("Minutes ago", form.minutesSinceLastReply, { v -> vm.updateForm { it.copy(minutesSinceLastReply = v) } }, Modifier.fillMaxWidth(), max = 100_000)
        }
        NumberField(
            "Auto-replies in this chat in the last ${state.settings.loopWindowMinutes} min",
            form.recentAutoReplies, { v -> vm.updateForm { it.copy(recentAutoReplies = v) } }, Modifier.fillMaxWidth(), max = 50,
        )
        NumberField("Messages already sent today", form.sentToday, { v -> vm.updateForm { it.copy(sentToday = v) } }, Modifier.fillMaxWidth(), max = 100_000)
        SwitchRow("You have taken over this chat", form.takeoverActive, { v -> vm.updateForm { it.copy(takeoverActive = v) } })
        SwitchRow("Contact has opted out", form.contactOptedOut, { v -> vm.updateForm { it.copy(contactOptedOut = v) } }, detail = "Only applies to a saved contact")
        SwitchRow("Same message already processed", form.duplicate, { v -> vm.updateForm { it.copy(duplicate = v) } }, detail = "Tests duplicate-notification suppression")
        SwitchRow("Message was sent by me", form.fromSelf, { v -> vm.updateForm { it.copy(fromSelf = v) } })
        SwitchRow("Channel can reply automatically", form.channelCanAutoReply, { v -> vm.updateForm { it.copy(channelCanAutoReply = v) } }, detail = "Turn off to see the permission-missing outcome")
        state.focusedRule?.let { rule ->
            SwitchRow("Only test “${rule.name}”", form.onlyFocusedRule, { v -> vm.updateForm { it.copy(onlyFocusedRule = v) } }, detail = "Off: run all your rules in order")
        }
        Button(onClick = vm::runRuleTest, enabled = form.text.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Simulate") }
        state.ruleResult?.let { EvaluationView(it) }
        Spacer(Modifier.height(Spacing.xl))
    }

    if (pickDate) {
        val dateState = rememberDatePickerState(initialSelectedDateMillis = form.date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton(onClick = {
                    dateState.selectedDateMillis?.let { ms -> vm.updateForm { it.copy(date = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()) } }
                    pickDate = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text("Cancel") } },
        ) { DatePicker(dateState) }
    }
    if (pickTime) {
        val timeState = rememberTimePickerState(initialHour = form.time.hour, initialMinute = form.time.minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { pickTime = false },
            text = { TimePicker(timeState) },
            confirmButton = {
                TextButton(onClick = {
                    vm.updateForm { it.copy(time = LocalTime.of(timeState.hour, timeState.minute)) }
                    pickTime = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickTime = false }) { Text("Cancel") } },
        )
    }
}

@Composable
fun QueueLabSection(state: SimulatorState, vm: SimulatorViewModel) {
    LazyColumn(contentPadding = PaddingValues(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        item {
            Notice(
                "These run the real queue on a private, in-memory copy with a virtual clock. They show how ReplyPilot retries, waits, hands off and avoids double-sending.",
                tone = NoticeTone.SIMULATED,
                title = "Simulation only",
            )
        }
        items(QueueScenario.entries, key = { it.name }) { scenario ->
            val result = state.queueResults[scenario]
            ListCard(onClick = { vm.runQueueScenario(scenario) }) {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(scenario.title, style = MaterialTheme.typography.titleSmall)
                            Text(scenario.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (state.runningScenario == scenario) {
                            CircularProgressIndicator(Modifier.padding(start = Spacing.sm).width(24.dp).height(24.dp), strokeWidth = 2.dp)
                        } else {
                            TextButton(onClick = { vm.runQueueScenario(scenario) }) { Text(if (result == null) "Run" else "Run again") }
                        }
                    }
                    if (result != null) {
                        result.timeline.forEach { entry ->
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                                Text("+${entry.minute} min", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(64.dp))
                                StatusPill(entry.status)
                                Text(entry.detail, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            }
                        }
                        Text(
                            "Send attempts: ${result.sendAttempts} · Final: ${result.finalStatus.label}",
                            style = MaterialTheme.typography.labelLarge,
                            color = LocalStatusColors.current.scheduled,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun DemoPipelineSection(state: SimulatorState, vm: SimulatorViewModel, nav: AppNavigator) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Notice(
            "Demo — no real message is sent. This sends a pretend incoming message on the Demo channel through the real pipeline: " +
                "rules, safeguards, the queue and your history. Every record is labelled as simulated.",
            tone = NoticeTone.SIMULATED,
            title = "Live demo pipeline",
        )
        if (state.rules.none { it.enabled && (it.channels.isEmpty() || com.replypilot.domain.model.ChannelId.DEMO in it.channels) }) {
            Notice("None of your enabled rules applies to the Demo channel, so nothing will match. Add the Demo channel to a rule, or load demo data in Settings › Data & privacy.", tone = NoticeTone.WARNING)
        }
        OutlinedTextField(state.demoText, { vm.updateDemo(text = it) }, label = { Text("Incoming message") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(state.demoSender, { vm.updateDemo(sender = it) }, label = { Text("Sender name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        DropdownSelector("What the Demo channel does with the next send", state.demoOutcome, DemoOutcome.entries, { it.label }, vm::setDemoOutcome)
        Button(onClick = vm::runDemoPipeline, enabled = !state.demoRunning && state.demoText.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
            Text(if (state.demoRunning) "Running…" else "Send demo message")
        }
        state.demoRun?.let { run ->
            run.report?.let { report ->
                Notice(
                    "Queue run: ${report.submitted} submitted, ${report.failed} failed or retrying, ${report.needsAttention} need you, ${report.deferred} waiting.",
                    tone = NoticeTone.SIMULATED,
                )
            }
            EvaluationView(run.evaluation, bannerText = "Demo — no real message is sent")
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                OutlinedButton(onClick = { nav.navigate(Routes.insights(InsightsTab.ACTIVITY)) }) { Text("Activity log") }
                OutlinedButton(onClick = { nav.navigate(Routes.messages(MessagesTab.OUTBOX)) }) { Text("Outbox") }
            }
        }
        Spacer(Modifier.height(Spacing.xl))
    }
}
