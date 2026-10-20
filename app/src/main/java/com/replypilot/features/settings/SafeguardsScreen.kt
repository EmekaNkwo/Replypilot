package com.replypilot.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.replypilot.core.design.BackTopBar
import com.replypilot.core.design.Notice
import com.replypilot.core.design.NoticeTone
import com.replypilot.core.design.SectionHeader
import com.replypilot.core.design.Spacing
import com.replypilot.domain.model.AutomationSettings
import com.replypilot.domain.model.ChannelId
import com.replypilot.domain.model.QuietHoursAutoReply
import com.replypilot.navigation.AppNavigator
import kotlin.math.roundToInt

@Composable
fun SafeguardsScreen(nav: AppNavigator) {
    val viewModel: SafeguardsViewModel = hiltViewModel()
    val settings by viewModel.settingsState.collectAsStateWithLifecycle()
    val realAi by viewModel.realAiConfigured.collectAsStateWithLifecycle()
    OnResume { viewModel.refresh() }

    Scaffold(topBar = { BackTopBar("Quiet hours & safeguards", nav::back) }) { padding ->
        val s = settings
        if (s == null) {
            CircularProgressIndicator(Modifier.padding(padding).padding(Spacing.xl))
            return@Scaffold
        }
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            StopSwitch(s, viewModel)
            WorkingAndQuietHours(s, viewModel::update)
            Limits(s, viewModel::update)
            LoopAndDuplicates(s, viewModel::update)
            Lists(s, viewModel::update)
            AiAutoSend(s, realAi, viewModel::update)
            Spacer(Modifier.height(Spacing.xl))
        }
    }
}

private typealias Update = ((AutomationSettings) -> AutomationSettings) -> Unit

@Composable
private fun StopSwitch(s: AutomationSettings, viewModel: SafeguardsViewModel) {
    SectionHeader("Emergency stop")
    SwitchRow(
        title = "Pause all automation",
        subtitle = "Stops scheduled messages, sequences and auto-replies immediately. Nothing is lost; resume any time.",
        checked = s.automationPaused,
        onChange = viewModel::setPaused,
    )
    if (s.automationPaused) Notice("Automation is paused. Nothing will be sent until you resume.", tone = NoticeTone.WARNING)
}

@Composable
private fun WorkingAndQuietHours(s: AutomationSettings, update: Update) {
    SectionHeader("Working hours")
    Text("Used by “within / outside working hours” rule conditions.", style = MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        TimeButton("From", s.workingHours.start, { v -> update { it.copy(workingHours = it.workingHours.copy(start = v)) } })
        TimeButton("To", s.workingHours.end, { v -> update { it.copy(workingHours = it.workingHours.copy(end = v)) } })
    }
    WeekdayChips(s.workingHours.days) { days -> if (days.isNotEmpty()) update { it.copy(workingHours = it.workingHours.copy(days = days)) } }

    SectionHeader("Quiet hours")
    SwitchRow(
        title = "Respect quiet hours",
        subtitle = "Scheduled messages follow each schedule's quiet-hours choice (wait, skip or send anyway).",
        checked = s.quietHoursEnabled,
        onChange = { v -> update { it.copy(quietHoursEnabled = v) } },
    )
    if (s.quietHoursEnabled) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            TimeButton("From", s.quietHours.start, { v -> update { it.copy(quietHours = it.quietHours.copy(start = v)) } })
            TimeButton("To", s.quietHours.end, { v -> update { it.copy(quietHours = it.quietHours.copy(end = v)) } })
        }
        Text("Auto-replies during quiet hours", style = MaterialTheme.typography.bodyLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            QuietHoursAutoReply.entries.forEach { option ->
                FilterChip(
                    selected = s.quietHoursAutoReply == option,
                    onClick = { update { it.copy(quietHoursAutoReply = option) } },
                    label = { Text(if (option == QuietHoursAutoReply.SUPPRESS) "Don't reply" else "Allow replies") },
                )
            }
        }
    }
}

@Composable
private fun Limits(s: AutomationSettings, update: Update) {
    SectionHeader("Limits")
    NumberField("Max auto-replies per contact per day", s.maxRepliesPerContactPerDay, 1..100, { v -> v?.let { n -> update { it.copy(maxRepliesPerContactPerDay = n) } } })
    NumberField("Max messages per day (all channels)", s.maxMessagesPerDay, 1..10_000, { v -> v?.let { n -> update { it.copy(maxMessagesPerDay = n) } } })
    Text("Per-channel daily limits", style = MaterialTheme.typography.bodyLarge)
    ChannelId.entries.forEach { channel ->
        NumberField(
            label = channel.displayName,
            value = s.perChannelDailyLimit[channel],
            range = 1..10_000,
            allowBlank = true,
            onCommit = { v -> update { it.copy(perChannelDailyLimit = if (v == null) it.perChannelDailyLimit - channel else it.perChannelDailyLimit + (channel to v)) } },
        )
    }
}

@Composable
private fun LoopAndDuplicates(s: AutomationSettings, update: Update) {
    SectionHeader("Loop & duplicate protection")
    Text(
        "Stops two auto-responders from replying to each other, and ignores the same notification posted twice.",
        style = MaterialTheme.typography.bodySmall,
    )
    NumberField("Stop after this many auto-replies in one chat…", s.loopThreshold, 1..20, { v -> v?.let { n -> update { it.copy(loopThreshold = n) } } })
    NumberField("…within (minutes)", s.loopWindowMinutes, 1..240, { v -> v?.let { n -> update { it.copy(loopWindowMinutes = n) } } }, suffix = "min")
    NumberField("Treat identical messages as duplicates for", s.duplicateWindowMinutes, 1..240, { v -> v?.let { n -> update { it.copy(duplicateWindowMinutes = n) } } }, suffix = "min")
    NumberField("Human takeover lasts", s.humanTakeoverMinutes, 5..7 * 24 * 60, { v -> v?.let { n -> update { it.copy(humanTakeoverMinutes = n) } } }, suffix = "min")
    SwitchRow(
        title = "Reply in group chats",
        subtitle = "Off by default. Group replies reach everyone in the chat.",
        checked = s.replyToGroups,
        onChange = { v -> update { it.copy(replyToGroups = v) } },
    )
}

@Composable
private fun Lists(s: AutomationSettings, update: Update) {
    SectionHeader("Opt-outs & blocks")
    ChipListEditor(
        "Opt-out keywords (a message that is exactly one of these opts the sender out)",
        s.optOutKeywords, { v -> update { it.copy(optOutKeywords = v) } }, "e.g. stop",
    )
    ChipListEditor("Blocked phrases (never auto-reply to messages containing these)", s.blockedPhrases, { v -> update { it.copy(blockedPhrases = v) } }, "e.g. urgent")
    ChipListEditor("Blocked senders (names or numbers)", s.blockedSenders, { v -> update { it.copy(blockedSenders = v) } }, "e.g. +2348000000000")
}

@Composable
private fun AiAutoSend(s: AutomationSettings, realAi: Boolean, update: Update) {
    SectionHeader("AI-assisted replies")
    SwitchRow(
        title = "Let confident AI drafts send automatically",
        subtitle = if (realAi) "Only for rules set to AI-assisted. Drafts below the threshold always wait for you." else
            "Available only with a real AI provider and key (Writing assistant). Mock drafts never auto-send.",
        checked = s.aiAutoSend && realAi,
        enabled = realAi,
        onChange = { v -> update { it.copy(aiAutoSend = v) } },
    )
    if (s.aiAutoSend && realAi) {
        Notice(
            "AI can be confidently wrong. Auto-sent drafts go to real people without review. Keep the threshold high and check the activity log.",
            tone = NoticeTone.WARNING,
        )
        Text("Minimum confidence: ${(s.aiMinConfidence * 100).roundToInt()}%", style = MaterialTheme.typography.bodyLarge)
        Slider(
            value = s.aiMinConfidence.toFloat(),
            onValueChange = { v -> update { it.copy(aiMinConfidence = (v * 20).roundToInt() / 20.0) } },
            valueRange = 0.5f..0.99f,
        )
    }
}
