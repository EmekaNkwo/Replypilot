package com.replypilot.integrations.email

import android.net.Uri
import com.replypilot.domain.messaging.ChannelAdapter
import com.replypilot.domain.messaging.OutboundMessage
import com.replypilot.domain.messaging.SendOutcome
import com.replypilot.domain.model.AvailabilityState
import com.replypilot.domain.model.ChannelCapability
import com.replypilot.domain.model.ChannelDescriptor
import com.replypilot.domain.model.ChannelId
import com.replypilot.domain.model.ChannelStatus
import com.replypilot.domain.model.IntegrationMode
import com.replypilot.domain.model.ManualHandoff
import javax.inject.Inject
import javax.inject.Singleton

/** Email is manual-only: ReplyPilot prepares the message and opens your mail app at the scheduled time. */
@Singleton
class EmailAdapter @Inject constructor() : ChannelAdapter {
    override val descriptor = ChannelDescriptor(
        id = ChannelId.EMAIL,
        mode = IntegrationMode.MANUAL_HANDOFF,
        capabilities = setOf(ChannelCapability.MANUAL_HANDOFF, ChannelCapability.ATTACHMENTS),
        limitations = listOf(
            "Manual only: ReplyPilot opens your email app with the message ready; you press Send.",
            "Incoming email isn't read, so auto-replies aren't available.",
        ),
    )

    override suspend fun status() = ChannelStatus(descriptor, AvailabilityState.MANUAL_ONLY, "Opens your email app with the message ready to send")

    override suspend fun send(message: OutboundMessage) =
        SendOutcome.NeedsManualAction("Email is sent from your mail app. Tap to open it with this message.", handoffFor(message))

    override fun handoffFor(message: OutboundMessage): ManualHandoff {
        if (message.attachments.isNotEmpty()) {
            return ManualHandoff(
                ManualHandoff.Kind.SHARE_FILES, message.body, attachments = message.attachments, recipientAddress = message.recipient.address.trim(),
            )
        }
        val subject = message.body.lineSequence().firstOrNull().orEmpty().take(60)
        val uri = "mailto:${Uri.encode(message.recipient.address)}?subject=${Uri.encode(subject)}&body=${Uri.encode(message.body)}"
        return ManualHandoff(ManualHandoff.Kind.COMPOSE_EMAIL, message.body, uri)
    }
}
