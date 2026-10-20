package com.replypilot.core.handoff

import android.content.Context
import com.replypilot.core.accessibility.AssistOutcome
import com.replypilot.core.accessibility.AutoSendBridge
import com.replypilot.core.accessibility.ChatApp
import com.replypilot.core.attachments.AttachmentStore
import com.replypilot.domain.model.ChannelId
import com.replypilot.domain.model.ExecutionItem
import com.replypilot.domain.scheduling.ActionResult
import com.replypilot.domain.scheduling.QueueActions
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Send manually". For a chat picked by name in WhatsApp, Telegram, Instagram or Messenger (with the accessibility
 * service on), ReplyPilot opens the app, fills in the message once the user opens that chat, and marks it Sent itself
 * when the user taps Send. Otherwise it falls back to the classic handoff (open the app / copy the text).
 */
@Singleton
class ManualSend @Inject constructor(
    private val bridge: AutoSendBridge,
    private val actions: QueueActions,
    private val attachments: AttachmentStore,
) {
    suspend fun send(context: Context, item: ExecutionItem): String {
        val app = ChatApp.forChannel(item.channel)
        // Files go through the app's share screen instead, so the assisted fill-in is only for text.
        if (app != null && item.recipient.isNamedChat && item.attachments.isEmpty() && bridge.isRunning) {
            return when (bridge.assistSend(app, item.recipient.address, item.body)) {
                AssistOutcome.SENT -> when (val result = actions.markSentManually(item.id, "Sent by you in ${item.channel.displayName} — confirmed by ReplyPilot")) {
                    ActionResult.Done -> "Sent to ${item.recipient.displayName} — marked as sent"
                    is ActionResult.Rejected -> result.reason
                }
                AssistOutcome.CANCELLED -> "Not sent. Tap Send manually to try again."
                AssistOutcome.TIMED_OUT -> "Didn't see the message go out. Check the chat, then mark it as sent if you sent it."
                AssistOutcome.UNAVAILABLE -> classic(context, item)
            }
        }
        return classic(context, item)
    }

    private fun classic(context: Context, item: ExecutionItem): String {
        val handoff = item.handoff ?: return "This message has no manual send option"
        return when (val outcome = HandoffLauncher.launch(context, handoff, attachments::uriFor)) {
            HandoffLauncher.Outcome.Shared -> sharedHint(item)
            is HandoffLauncher.Outcome.FilesMissing ->
                "Can't send: ${outcome.names.joinToString()} ${if (outcome.names.size == 1) "is" else "are"} no longer on this phone. Edit the schedule to attach them again."
            HandoffLauncher.Outcome.Opened -> "Opened ${item.channel.displayName}. Tap \"Mark as sent\" once you've sent it."
            HandoffLauncher.Outcome.CopiedAndOpened -> "Message copied. Paste it in ${item.channel.displayName}, then mark it as sent."
            HandoffLauncher.Outcome.Copied -> "Message copied to the clipboard. Paste it in ${item.channel.displayName}."
        }
    }

    private fun sharedHint(item: ExecutionItem): String {
        val files = "${item.attachments.size} file${if (item.attachments.size == 1) "" else "s"}"
        // WhatsApp (by number), SMS and email open already addressed; the other apps ask who to share with.
        val addressed = item.channel == ChannelId.SMS || item.channel == ChannelId.EMAIL ||
            (item.channel == ChannelId.WHATSAPP && !item.recipient.isNamedChat)
        return if (addressed) {
            "Opened ${item.channel.displayName} with $files for ${item.recipient.displayName}. Tap Send there, then \"Mark as sent\"."
        } else {
            "Sharing $files to ${item.channel.displayName}: choose “${item.recipient.displayName}” and send. The text is also copied. Then tap \"Mark as sent\"."
        }
    }
}
