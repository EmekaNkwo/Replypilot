package com.replypilot.integrations.whatsapp

import com.replypilot.core.datastore.AppPreferencesRepository
import com.replypilot.core.security.CredentialKey
import com.replypilot.core.security.CredentialStore
import com.replypilot.domain.messaging.ChannelAdapter
import com.replypilot.domain.messaging.DeliveryReport
import com.replypilot.domain.messaging.DeliveryState
import com.replypilot.domain.messaging.OutboundMessage
import com.replypilot.domain.messaging.SendOutcome
import com.replypilot.domain.model.AvailabilityState
import com.replypilot.domain.model.ChannelCapability
import com.replypilot.domain.model.ChannelDescriptor
import com.replypilot.domain.model.ChannelId
import com.replypilot.domain.model.ChannelStatus
import com.replypilot.domain.model.FailureCode
import com.replypilot.domain.model.IntegrationMode
import com.replypilot.domain.model.ManualHandoff
import com.replypilot.domain.scheduling.AddressRules
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Official WhatsApp Business Platform access through the user's own relay server.
 * Unlike the on-device WhatsApp channel it can start conversations unattended and report delivery,
 * subject to Meta's rules (approved templates outside the 24-hour customer-service window).
 */
@Singleton
class WhatsAppBusinessAdapter @Inject constructor(
    private val preferences: AppPreferencesRepository,
    private val credentials: CredentialStore,
) : ChannelAdapter {
    override val descriptor = ChannelDescriptor(
        id = ChannelId.WHATSAPP_BUSINESS,
        mode = IntegrationMode.OFFICIAL_API,
        capabilities = setOf(ChannelCapability.SEND_MESSAGE, ChannelCapability.DELIVERY_STATUS, ChannelCapability.MANUAL_HANDOFF),
        limitations = listOf(
            "Requires a WhatsApp Business Platform account and your own relay server (see the integration guide).",
            "Free-form text only reaches people who messaged you in the last 24 hours; otherwise Meta requires an approved template.",
            "Incoming messages arrive at the relay's webhook; on-device auto-replies for this channel aren't available yet.",
            "A standard WhatsApp account can't be used here — that's the on-device WhatsApp channel.",
        ),
        maxMessageLength = 4096,
    )

    private suspend fun client(): BusinessRelayClient? {
        val url = preferences.current().businessRelayUrl
        val token = credentials.read(CredentialKey.BUSINESS_RELAY_TOKEN)
        return if (url.isBlank() || token.isNullOrBlank()) null else BusinessRelayClient(url, token)
    }

    override suspend fun status(): ChannelStatus = if (client() == null) {
        ChannelStatus(descriptor, AvailabilityState.NOT_CONFIGURED, "Add your relay URL and device token in Connected channels.")
    } else {
        ChannelStatus(descriptor, AvailabilityState.READY, "Relay configured. Use Test connection to verify it.")
    }

    suspend fun testConnection(): String = when (val result = client()?.health()) {
        null -> "Not configured"
        is BusinessRelayClient.Result.Ok -> if (result.value.ok) "Connected${result.value.phoneNumber?.let { " as $it" }.orEmpty()}" else "Relay reported a problem"
        is BusinessRelayClient.Result.Http -> "Relay returned ${result.code}: ${result.message}"
        is BusinessRelayClient.Result.Network -> "Couldn't reach the relay: ${result.message}"
        is BusinessRelayClient.Result.AcceptedUnreadable -> "Relay answered (${result.code}) but its response wasn't in the expected format"
    }

    override suspend fun send(message: OutboundMessage): SendOutcome {
        val client = client() ?: return SendOutcome.Failed(FailureCode.NOT_CONFIGURED, "WhatsApp Business relay isn't configured")
        if (!AddressRules.isValid(ChannelId.WHATSAPP_BUSINESS, message.recipient.address)) {
            return SendOutcome.Failed(FailureCode.INVALID_RECIPIENT, "Not a valid WhatsApp number")
        }
        val request = BusinessRelayClient.SendRequest(
            to = AddressRules.normalize(ChannelId.WHATSAPP_BUSINESS, message.recipient.address),
            text = message.body,
            idempotencyKey = message.idempotencyKey,
        )
        return when (val result = client.send(request)) {
            is BusinessRelayClient.Result.Ok -> SendOutcome.Submitted(result.value.id)
            // Never retry something the relay probably accepted; report it as submitted without a reference.
            is BusinessRelayClient.Result.AcceptedUnreadable -> SendOutcome.Submitted(providerRef = null)
            is BusinessRelayClient.Result.Network -> SendOutcome.Failed(FailureCode.NETWORK, result.message)
            is BusinessRelayClient.Result.Http -> SendOutcome.Failed(codeFor(result.code), result.message)
        }
    }

    override suspend fun deliveryStatus(providerRef: String): DeliveryReport? {
        val client = client() ?: return null
        val result = client.status(providerRef) as? BusinessRelayClient.Result.Ok ?: return null
        val state = when (result.value.status.lowercase()) {
            "delivered", "read" -> DeliveryState.DELIVERED
            "failed" -> DeliveryState.FAILED
            "sent", "accepted", "queued" -> DeliveryState.PENDING
            else -> DeliveryState.UNKNOWN
        }
        return DeliveryReport(providerRef, state, result.value.detail)
    }

    override fun handoffFor(message: OutboundMessage): ManualHandoff {
        val digits = AddressRules.normalize(ChannelId.WHATSAPP_BUSINESS, message.recipient.address).removePrefix("+")
        return ManualHandoff(ManualHandoff.Kind.OPEN_APP_PREFILLED, message.body, "https://wa.me/$digits?text=${android.net.Uri.encode(message.body)}")
    }

    private fun codeFor(status: Int): FailureCode = when (status) {
        400, 422 -> FailureCode.PROVIDER_REJECTED
        401, 403 -> FailureCode.NOT_CONFIGURED
        404 -> FailureCode.INVALID_RECIPIENT
        429 -> FailureCode.RATE_LIMITED
        in 500..599 -> FailureCode.PROVIDER_ERROR
        else -> FailureCode.UNKNOWN
    }
}
