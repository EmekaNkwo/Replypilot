package com.replypilot.features.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.replypilot.core.design.ChannelAvatar
import com.replypilot.core.design.ListCard
import com.replypilot.core.design.Notice
import com.replypilot.core.design.NoticeTone
import com.replypilot.core.design.SimulatedPill
import com.replypilot.core.design.Spacing
import com.replypilot.core.permissions.Capability
import com.replypilot.domain.model.ChannelId
import com.replypilot.features.settings.CapabilityCard
import com.replypilot.features.settings.SwitchRow

@Composable
private fun Bullet(text: String) = Text("• $text", style = MaterialTheme.typography.bodyMedium)

@Composable
fun WelcomeStep() {
    Text(
        "ReplyPilot helps you send messages on time and answer routine questions automatically — under rules you write, and only where your channels allow it.",
        style = MaterialTheme.typography.bodyLarge,
    )
    Text("Two things, one system", style = MaterialTheme.typography.titleMedium)
    Bullet("Scheduled messages: write now, send at a set time or on a repeating schedule.")
    Bullet("Auto-replies: rules decide when and how to answer incoming messages, with cooldowns, quiet hours and opt-outs.")
    Text("Honest limits", style = MaterialTheme.typography.titleMedium)
    Bullet("Auto-replies go through the notification's Reply button. Scheduled WhatsApp messages can be sent for you with the optional Auto-send; on Telegram, Instagram, Messenger, SMS and email ReplyPilot gets the message ready and you tap Send.")
    Bullet("Android can delay background work to save battery. Sends may be a few minutes late unless you enable precise timing.")
    Bullet("“Submitted” means the app accepted a message; it is only “Delivered” when a channel confirms it.")
    Bullet("You can try rules in the test lab without sending real messages, and pause all automation at any time.")
}

@Composable
fun ChannelsStep(state: OnboardingUiState, onToggle: (ChannelId, Boolean) -> Unit) {
    Text(
        "Pick the apps ReplyPilot may automate replies for. You can change this later in Settings → Connected channels.",
        style = MaterialTheme.typography.bodyMedium,
    )
    state.channels.forEach { choice ->
        ListCard(onClick = null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                ChannelAvatar(choice.channel)
                Column(Modifier.weight(1f)) {
                    if (choice.installed) {
                        SwitchRow(
                            title = choice.channel.displayName,
                            subtitle = "Replies via notifications; scheduled sends need one tap",
                            checked = choice.enabled,
                            onChange = { onToggle(choice.channel, it) },
                        )
                    } else {
                        Text(choice.channel.displayName, style = MaterialTheme.typography.bodyLarge)
                        Text("Not installed on this device", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
    ListCard(onClick = null) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            ChannelAvatar(ChannelId.DEMO)
            Column(Modifier.weight(1f)) {
                Text("Demo channel", style = MaterialTheme.typography.bodyLarge)
                Text("Always available. Simulates sending so you can try things safely.", style = MaterialTheme.typography.bodySmall)
            }
            SimulatedPill()
        }
    }
    Notice(
        "Email is manual-only (ReplyPilot opens your mail app). The WhatsApp Business Platform is an advanced, optional integration that needs your own server — set it up later in Connected channels.",
    )
}

@Composable
fun PermissionsStep(state: OnboardingUiState, onRefresh: () -> Unit) {
    Text(
        "Nothing was requested when you installed ReplyPilot. Grant only what the features you chose need.",
        style = MaterialTheme.typography.bodyMedium,
    )
    val relevant = state.capabilities.filter { cap ->
        cap.applicable && (cap.capability != Capability.NOTIFICATION_ACCESS || state.anyDeviceChannel)
    }
    if (!state.anyDeviceChannel) {
        Notice("You didn't enable any messaging app, so notification access isn't needed. You can enable apps later.")
    }
    relevant.filter { it.capability != Capability.EXACT_ALARMS && it.capability != Capability.BATTERY_UNRESTRICTED }
        .forEach { CapabilityCard(it, onRequested = onRefresh) }
}

@Composable
fun BackgroundStep() {
    Text(
        "Scheduled messages wait in a queue stored on your phone. Android wakes ReplyPilot to process it, and it re-arms itself after a restart.",
        style = MaterialTheme.typography.bodyMedium,
    )
    Bullet("By default Android may run work a few minutes late to save battery.")
    Bullet("Precise timing (optional) uses exact alarms when Android allows it.")
    Bullet("Some phones stop background apps aggressively; exempting ReplyPilot from battery optimisation (optional) helps.")
    Bullet("If the phone is off or automation is paused, overdue messages are sent late only within their expiry window — never twice.")
    // Optional capabilities, shown separately so they're never mistaken for requirements.
}

@Composable
fun TryItStep(state: OnboardingUiState, onLoadDemo: () -> Unit, onScheduleTest: () -> Unit) {
    Notice(
        "Both options use the simulated Demo channel. Everything is labelled as demo and no real message is sent.",
        tone = NoticeTone.SIMULATED,
    )
    Text("Explore with demo data", style = MaterialTheme.typography.titleMedium)
    Text("Adds sample contacts, rules, templates, a sequence and a weekly schedule.", style = MaterialTheme.typography.bodySmall)
    if (state.demoLoaded) Text("Demo data loaded ✓", style = MaterialTheme.typography.bodyMedium)
    else Button(onClick = onLoadDemo, enabled = !state.busy) { Text("Load demo data") }

    Text("Schedule a test message", style = MaterialTheme.typography.titleMedium)
    Text("Queues a one-off demo message two minutes from now so you can watch it run.", style = MaterialTheme.typography.bodySmall)
    OutlinedButton(onClick = onScheduleTest, enabled = !state.busy && state.testResult == null) { Text("Schedule test message") }
    state.testResult?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
}

@Composable
fun FirstRuleStep(state: OnboardingUiState, onEnabledChange: (Boolean) -> Unit, onCreate: () -> Unit) {
    Text(
        "A common first rule: when someone messages outside working hours (Mon–Fri 9:00–17:00 by default), reply once with an away message.",
        style = MaterialTheme.typography.bodyMedium,
    )
    ListCard(onClick = null) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text("WHEN a message arrives", style = MaterialTheme.typography.labelLarge)
            Text("IF it's outside working hours", style = MaterialTheme.typography.labelLarge)
            Text("THEN reply: “Thanks for your message. We're away right now and will reply during business hours.”", style = MaterialTheme.typography.bodyMedium)
            Text("At most once every 12 hours per chat", style = MaterialTheme.typography.bodySmall)
        }
    }
    SwitchRow(
        title = "Turn it on now",
        subtitle = "Leave off to review it in Automation → Rules first. You can test it in the simulator either way.",
        checked = state.ruleEnabled,
        onChange = onEnabledChange,
        enabled = !state.ruleCreated,
    )
    if (state.ruleCreated) {
        Text(if (state.ruleEnabled) "Rule created and active ✓" else "Rule created (off) ✓", style = MaterialTheme.typography.bodyMedium)
    } else {
        Button(onClick = onCreate, enabled = !state.busy) { Text("Create this rule") }
        Text("Or skip — you can build rules any time.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun PrivacyStep() {
    Text("Your data stays on this phone", style = MaterialTheme.typography.titleMedium)
    Bullet("Rules, schedules, contacts and history are stored locally and excluded from cloud backup.")
    Bullet("Incoming message text is checked against your rules and not stored.")
    Bullet("AI is off unless you turn it on, and never answers messages on its own unless you allow it.")
    Text("Safeguards are on by default", style = MaterialTheme.typography.titleMedium)
    Bullet("Quiet hours, per-contact limits, a daily cap, loop and duplicate detection.")
    Bullet("People who send STOP or UNSUBSCRIBE are opted out automatically.")
    Bullet("One switch pauses all automation instantly — on Home and in Safeguards.")
}
