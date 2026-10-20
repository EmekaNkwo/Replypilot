package com.replypilot.features.settings

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.replypilot.core.design.LocalStatusColors
import com.replypilot.core.design.Pill
import com.replypilot.core.design.Spacing

/**
 * Auto-send setup with the prominent disclosure Google Play requires before sending people to
 * Accessibility settings. The switch itself lives in system settings; ReplyPilot never toggles it.
 */
@Composable
fun AutoSendSetup(enabled: Boolean) {
    val context = LocalContext.current
    var disclosing by remember { mutableStateOf(false) }
    val colors = LocalStatusColors.current
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text("Auto-send scheduled messages", style = MaterialTheme.typography.titleSmall)
        Pill(if (enabled) "On" else "Off", if (enabled) colors.success else colors.paused)
        Text(
            if (enabled) {
                "At the scheduled time ReplyPilot opens the chat, presses Send and confirms it. If the phone is locked it waits for you to unlock; " +
                    "if it can't confirm, the message is marked Failed or Needs attention with the reason."
            } else {
                "Off: at the scheduled time you get a one-tap handoff. Turn on to have ReplyPilot press Send for you and mark messages Sent or Failed automatically."
            },
            style = MaterialTheme.typography.bodySmall,
        )
        if (enabled) {
            OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) {
                Text("Manage in Accessibility settings")
            }
        } else {
            Button(onClick = { disclosing = true }) { Text("Turn on Auto-send") }
        }
    }
    if (disclosing) {
        AlertDialog(
            onDismissRequest = { disclosing = false },
            title = { Text("Allow ReplyPilot to work inside your chat apps?") },
            text = {
                Text(
                    "ReplyPilot uses Android's Accessibility service to:\n" +
                        "• send WhatsApp messages you scheduled (open the chat, press Send, confirm it went out);\n" +
                        "• read the name of the chat you open when you choose a recipient in WhatsApp, Telegram, Instagram or Messenger;\n" +
                        "• fill in your message when you tap Send manually, and mark it sent once you send it.\n\n" +
                        "• It only works inside those apps, and only when you started it or a scheduled WhatsApp send is due.\n" +
                        "• It never reads, stores or shares your other chats or apps.\n" +
                        "• Scheduled sends happen only while the phone is unlocked.\n\n" +
                        "On the next screen, choose “ReplyPilot auto-send” and turn it on. You can turn it off there at any time.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    disclosing = false
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }) { Text("Continue") }
            },
            dismissButton = { TextButton(onClick = { disclosing = false }) { Text("Not now") } },
        )
    }
}
