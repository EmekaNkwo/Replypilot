package com.replypilot.domain.messaging

import com.replypilot.domain.model.Attachment
import com.replypilot.domain.model.ChannelDescriptor
import com.replypilot.domain.model.ChannelId
import com.replypilot.domain.model.ChannelStatus
import com.replypilot.domain.model.FailureCode
import com.replypilot.domain.model.IncomingMessage
import com.replypilot.domain.model.ManualHandoff
import com.replypilot.domain.model.Recipient

data class OutboundMessage(
    val executionId: String,
    val idempotencyKey: String,
    val recipient: Recipient,
    val body: String,
    /** Set for auto-replies: lets device adapters reply in place to the original notification. */
    val replyTarget: String? = null,
    val attachments: List<Attachment> = emptyList(),
)

/** What the channel actually reported. Acceptance is never reported as delivery. */
sealed interface SendOutcome {
    /** The channel accepted the message. [simulated] is true for demo adapters. */
    data class Submitted(val providerRef: String?, val simulated: Boolean = false, val detail: String? = null) : SendOutcome

    /** The channel cannot send unattended; the user must finish the send. */
    data class NeedsManualAction(val reason: String, val handoff: ManualHandoff) : SendOutcome

    data class Failed(val code: FailureCode, val detail: String) : SendOutcome
}

enum class DeliveryState { PENDING, DELIVERED, FAILED, UNKNOWN }

data class DeliveryReport(val providerRef: String, val state: DeliveryState, val detail: String? = null)

/**
 * Contract every messaging integration implements. Adapters must report what they can do via
 * [descriptor]; callers check capabilities before invoking optional operations.
 */
interface ChannelAdapter {
    val descriptor: ChannelDescriptor
    val id: ChannelId get() = descriptor.id

    /** Live readiness check (permissions, configuration, installed apps). */
    suspend fun status(): ChannelStatus

    suspend fun send(message: OutboundMessage): SendOutcome

    /** Builds the manual fallback for [message], or null when the channel has none. */
    fun handoffFor(message: OutboundMessage): ManualHandoff?

    suspend fun deliveryStatus(providerRef: String): DeliveryReport? = null
}

/** Looks up adapters by channel. Lets the scheduler and rule engine stay unaware of concrete integrations. */
interface AdapterRegistry {
    fun all(): List<ChannelAdapter>
    fun get(channel: ChannelId): ChannelAdapter?
}

class StaticAdapterRegistry(adapters: List<ChannelAdapter>) : AdapterRegistry {
    private val byId = adapters.associateBy { it.id }
    override fun all(): List<ChannelAdapter> = byId.values.toList()
    override fun get(channel: ChannelId): ChannelAdapter? = byId[channel]
}

/** Source of incoming messages that the rule engine can observe (implemented by platform services). */
fun interface IncomingMessageSink {
    suspend fun onIncoming(message: IncomingMessage)
}
