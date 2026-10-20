package com.replypilot.integrations.device

import android.content.Context
import android.provider.Telephony
import com.replypilot.core.accessibility.AutoSendBridge
import com.replypilot.core.accessibility.AutoSendResult
import com.replypilot.core.accessibility.AutoSendTarget
import com.replypilot.core.datastore.AppPreferencesRepository
import com.replypilot.core.notifications.ReplyActionRegistry
import com.replypilot.core.permissions.PlatformStatus
import com.replypilot.domain.messaging.ChannelAdapter
import com.replypilot.domain.messaging.OutboundMessage
import com.replypilot.domain.messaging.SendOutcome
import com.replypilot.domain.model.Attachment
import com.replypilot.domain.model.AvailabilityState
import com.replypilot.domain.model.ChannelStatus
import com.replypilot.domain.model.FailureCode
import com.replypilot.domain.model.ManualHandoff
import com.replypilot.domain.scheduling.AddressRules
import java.io.File

/** Replies through a messaging app's own notification Reply action, with a manual handoff otherwise. */
class DeviceNotificationAdapter(
    private val spec: DeviceChannelSpec,
    private val context: Context,
    private val platform: PlatformStatus,
    private val preferences: AppPreferencesRepository,
    private val replies: ReplyActionRegistry,
    private val autoSend: AutoSendBridge? = null,
    /** Whether an attached file is still in ReplyPilot's storage. */
    private val fileExists: (Attachment) -> Boolean = { File(it.path).isFile },
) : ChannelAdapter {
    override val descriptor = spec.descriptor

    /**
     * SMS has no fixed package: use the default SMS app, plus well-known SMS apps that are installed —
     * some devices (e.g. Xiaomi) report no default even when Google Messages handles texts.
     */
    fun packages(): List<String> = spec.packages.ifEmpty {
        (listOfNotNull(Telephony.Sms.getDefaultSmsPackage(context)) + KNOWN_SMS_APPS.filter(platform::isInstalled)).distinct()
    }

    private companion object {
        val KNOWN_SMS_APPS = listOf(
            "com.google.android.apps.messaging", "com.android.mms", "com.samsung.android.messaging", "com.android.messaging",
        )
    }

    override suspend fun status(): ChannelStatus {
        val name = spec.channel.displayName
        val installed = packages().any(platform::isInstalled)
        val enabled = spec.channel in preferences.current().enabledChannels
        return when {
            !installed -> ChannelStatus(descriptor, AvailabilityState.APP_NOT_INSTALLED, "$name isn't installed on this device.")
            !enabled -> ChannelStatus(descriptor, AvailabilityState.DISABLED, "Auto-replies are off for $name. Switch on “Use for auto-replies” in Settings → Connected channels.")
            !platform.notificationAccessGranted() -> ChannelStatus(
                descriptor, AvailabilityState.NEEDS_PERMISSION,
                "Notification access is off, so auto-replies can't run. Scheduled messages will be handed to you.",
            )
            spec.autoSend && autoSend?.isEnabled() == true ->
                ChannelStatus(descriptor, AvailabilityState.READY, "Auto-replies run from notifications. Scheduled messages are sent automatically while the phone is unlocked.")
            else -> ChannelStatus(descriptor, AvailabilityState.READY, "Auto-replies run from notifications. Scheduled messages need one tap from you${if (spec.autoSend) " until you turn on Auto-send" else ""}.")
        }
    }

    override suspend fun send(message: OutboundMessage): SendOutcome {
        val handoff = handoffFor(message)
        val target = message.replyTarget ?: return sendNew(message, handoff)
        if (!platform.notificationAccessGranted()) {
            return SendOutcome.Failed(FailureCode.PERMISSION_MISSING, "Notification access is off")
        }
        return when (val result = replies.reply(target, message.body)) {
            ReplyActionRegistry.ReplyResult.Sent -> SendOutcome.Submitted(providerRef = null)
            ReplyActionRegistry.ReplyResult.NoTarget -> SendOutcome.Failed(
                FailureCode.NO_REPLY_TARGET, "The ${spec.channel.displayName} notification was dismissed or replaced",
            )
            is ReplyActionRegistry.ReplyResult.Failed -> SendOutcome.Failed(FailureCode.PROVIDER_ERROR, result.reason)
        }
    }

    override fun handoffFor(message: OutboundMessage): ManualHandoff =
        if (message.attachments.isNotEmpty()) {
            ManualHandoff(
                ManualHandoff.Kind.SHARE_FILES, message.body,
                packageName = packages().firstOrNull(platform::isInstalled),
                attachments = message.attachments,
                recipientAddress = if (message.recipient.isNamedChat) null else AddressRules.normalize(spec.channel, message.recipient.address),
            )
        } else if (message.recipient.isNamedChat) {
            ManualHandoff(ManualHandoff.Kind.COPY_TEXT, message.body, packageName = packages().firstOrNull(platform::isInstalled))
        } else {
            spec.handoff(message.recipient.address, message.body)
        }

    /** A new message (not a reply): Auto-send when it's on, otherwise hand it to the user. */
    private suspend fun sendNew(message: OutboundMessage, handoff: ManualHandoff): SendOutcome {
        val missing = message.attachments.filterNot(fileExists)
        if (missing.isNotEmpty()) {
            return SendOutcome.Failed(
                FailureCode.ATTACHMENT_MISSING,
                "${missing.joinToString { it.fileName }} ${if (missing.size == 1) "is" else "are"} no longer in ReplyPilot's storage. Edit the schedule and attach again",
            )
        }
        val bridge = autoSend
        val packageName = packages().firstOrNull(platform::isInstalled)
        if (!spec.autoSend || bridge == null || packageName == null || !bridge.isEnabled()) {
            val reason = if (message.attachments.isNotEmpty()) {
                "Tap Send manually: ReplyPilot shares the ${message.attachments.size} file(s) to ${spec.channel.displayName} with your message, then you send it"
            } else if (message.recipient.isNamedChat && bridge?.isEnabled() == true) {
                "Tap Send manually: ReplyPilot opens ${spec.channel.displayName}; open your chat with ${message.recipient.displayName} " +
                    "and the message is filled in — you just tap Send"
            } else {
                "${spec.channel.displayName} can't send a new message unattended. Tap to finish sending."
            }
            return SendOutcome.NeedsManualAction(reason, handoff)
        }
        val target = if (message.recipient.isNamedChat) {
            AutoSendTarget.Group(message.recipient.address.trim())
        } else {
            AutoSendTarget.Person(AddressRules.normalize(spec.channel, message.recipient.address).removePrefix("+"))
        }
        val result = if (message.attachments.isEmpty()) bridge.send(packageName, target, message.body)
        else bridge.sendFiles(packageName, target, message.body, message.attachments)
        return when (result) {
            AutoSendResult.Sent -> SendOutcome.Submitted(providerRef = null, detail = "Sent automatically — confirmed in ${spec.channel.displayName}")
            AutoSendResult.Disabled -> SendOutcome.NeedsManualAction("Auto-send is off. Tap to finish sending.", handoff)
            AutoSendResult.Locked -> SendOutcome.Failed(FailureCode.DEVICE_LOCKED, "Phone is locked")
            AutoSendResult.BlockedByPhone -> SendOutcome.Failed(
                FailureCode.PERMISSION_MISSING,
                "Your phone blocked ReplyPilot from opening ${spec.channel.displayName} in the background. " +
                    "Allow “Display pop-up windows while running in the background” in Settings → Permissions",
            )
            AutoSendResult.NotOnWhatsApp -> SendOutcome.Failed(FailureCode.INVALID_RECIPIENT, "This number isn't on ${spec.channel.displayName}")
            is AutoSendResult.GroupNotFound -> SendOutcome.Failed(FailureCode.INVALID_RECIPIENT, "No ${spec.channel.displayName} group named \"${result.name}\" was found")
            is AutoSendResult.NotReady -> SendOutcome.Failed(FailureCode.PROVIDER_ERROR, result.reason)
            // Files already confirmed are left out of the manual share, so "Send manually" never duplicates them.
            is AutoSendResult.Unconfirmed -> SendOutcome.NeedsManualAction(
                "${result.reason}. Check the chat before sending it again.",
                if (result.filesSent > 0) handoff.copy(text = "", attachments = handoff.attachments.drop(result.filesSent)) else handoff,
            )
        }
    }
}
