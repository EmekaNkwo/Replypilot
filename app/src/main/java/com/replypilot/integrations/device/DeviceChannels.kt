package com.replypilot.integrations.device

import android.net.Uri
import com.replypilot.domain.model.ChannelCapability
import com.replypilot.domain.model.ChannelDescriptor
import com.replypilot.domain.model.ChannelId
import com.replypilot.domain.model.IntegrationMode
import com.replypilot.domain.model.ManualHandoff
import com.replypilot.domain.scheduling.AddressRules

/**
 * How a messaging app is reached on this device. Notification access only allows replying to a
 * conversation that has just notified us; starting a new message unattended is not possible,
 * so scheduled sends on these channels become manual handoffs.
 */
data class DeviceChannelSpec(
    val channel: ChannelId,
    val packages: List<String>,
    val supportsGroups: Boolean,
    /** WhatsApp only: can send scheduled messages unattended through the optional Auto-send service. */
    val autoSend: Boolean = false,
    val handoff: (address: String, text: String) -> ManualHandoff,
) {
    val descriptor: ChannelDescriptor = ChannelDescriptor(
        id = channel,
        mode = IntegrationMode.DEVICE_NOTIFICATION,
        capabilities = buildSet {
            if (autoSend) add(ChannelCapability.SEND_MESSAGE)
            add(ChannelCapability.RECEIVE_MESSAGE)
            add(ChannelCapability.AUTO_REPLY)
            add(ChannelCapability.MANUAL_HANDOFF)
            add(ChannelCapability.ATTACHMENTS)
            if (supportsGroups) add(ChannelCapability.GROUP_CONVERSATIONS)
        },
        limitations = listOfNotNull(
            "Replies only to conversations with a visible notification that offers a Reply button.",
            if (autoSend) "Sends scheduled messages unattended only when Auto-send is on and the phone is unlocked; otherwise you get a one-tap handoff."
            else "Can't start a new conversation or send scheduled messages unattended — you'll get a one-tap handoff instead.",
            "No delivery confirmation; a reply counts as submitted once ${channel.displayName} accepts it.",
            "Contacts are recognised by the name shown in the notification.",
        ),
    )
}

object DeviceChannels {
    private fun encode(text: String) = Uri.encode(text)

    val WHATSAPP = DeviceChannelSpec(ChannelId.WHATSAPP, listOf("com.whatsapp", "com.whatsapp.w4b"), supportsGroups = true, autoSend = true) { address, text ->
        val digits = AddressRules.normalize(ChannelId.WHATSAPP, address).removePrefix("+")
        if (digits.isNotEmpty() && AddressRules.isValid(ChannelId.WHATSAPP, address)) {
            ManualHandoff(ManualHandoff.Kind.OPEN_APP_PREFILLED, text, "https://wa.me/$digits?text=${encode(text)}")
        } else {
            ManualHandoff(ManualHandoff.Kind.COPY_TEXT, text, packageName = "com.whatsapp")
        }
    }

    val TELEGRAM = DeviceChannelSpec(ChannelId.TELEGRAM, listOf("org.telegram.messenger"), supportsGroups = true) { address, text ->
        val handle = address.trim().removePrefix("@")
        if (handle.isNotEmpty() && !handle.first().isDigit() && handle.first() != '+') {
            ManualHandoff(ManualHandoff.Kind.OPEN_APP_PREFILLED, text, "https://t.me/$handle")
        } else {
            ManualHandoff(ManualHandoff.Kind.COPY_TEXT, text, packageName = "org.telegram.messenger")
        }
    }

    val INSTAGRAM = DeviceChannelSpec(ChannelId.INSTAGRAM, listOf("com.instagram.android"), supportsGroups = false) { address, text ->
        val handle = address.trim().removePrefix("@")
        ManualHandoff(ManualHandoff.Kind.COPY_TEXT, text, uri = "https://ig.me/m/$handle".takeIf { handle.isNotEmpty() }, packageName = "com.instagram.android")
    }

    val MESSENGER = DeviceChannelSpec(ChannelId.MESSENGER, listOf("com.facebook.orca"), supportsGroups = true) { address, text ->
        val handle = address.trim()
        ManualHandoff(ManualHandoff.Kind.COPY_TEXT, text, uri = "https://m.me/$handle".takeIf { handle.isNotEmpty() && !handle.contains(' ') }, packageName = "com.facebook.orca")
    }

    /** SMS packages are resolved at runtime from the default SMS app. */
    val SMS = DeviceChannelSpec(ChannelId.SMS, emptyList(), supportsGroups = false) { address, text ->
        ManualHandoff(ManualHandoff.Kind.COMPOSE_SMS, text, "smsto:${AddressRules.normalize(ChannelId.SMS, address)}")
    }

    val all = listOf(WHATSAPP, TELEGRAM, INSTAGRAM, MESSENGER, SMS)
}
