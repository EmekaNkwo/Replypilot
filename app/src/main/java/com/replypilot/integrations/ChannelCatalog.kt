package com.replypilot.integrations

import com.replypilot.domain.model.ChannelId

/** An integration route that is designed but not built. Shown honestly as planned, never as connected. */
data class PlannedIntegration(val channel: ChannelId, val title: String, val detail: String)

object ChannelCatalog {
    val planned: List<PlannedIntegration> = listOf(
        PlannedIntegration(ChannelId.TELEGRAM, "Telegram Bot API", "Unattended sending as a bot. Needs a server to hold the bot token and receive updates."),
        PlannedIntegration(ChannelId.INSTAGRAM, "Instagram Messaging API", "Business accounts via Meta's Graph API. Needs a server and app review."),
        PlannedIntegration(ChannelId.MESSENGER, "Messenger Platform", "Page messaging via Meta's Graph API. Needs a server and app review."),
        PlannedIntegration(ChannelId.SMS, "SMS gateway", "Unattended SMS through a provider such as an SMS API, via a server. ReplyPilot doesn't request SMS permissions on the phone."),
        PlannedIntegration(ChannelId.EMAIL, "Email sending service", "Unattended email through a transactional email provider, via a server."),
    )

    fun plannedFor(channel: ChannelId) = planned.filter { it.channel == channel }
}
