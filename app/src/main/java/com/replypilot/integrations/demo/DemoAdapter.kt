package com.replypilot.integrations.demo

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
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class DemoOutcome(val label: String) {
    SUCCEED("Succeed"),
    FAIL_TEMPORARY("Fail once (temporary)"),
    FAIL_PERMANENT("Fail (permanent)"),
    REQUIRE_MANUAL("Require manual send"),
}

/** Lets the reviewer choose what the demo channel does with the next send. */
@Singleton
class DemoControls @Inject constructor() {
    private val _nextOutcome = MutableStateFlow(DemoOutcome.SUCCEED)
    val nextOutcome: StateFlow<DemoOutcome> = _nextOutcome.asStateFlow()

    fun set(outcome: DemoOutcome) {
        _nextOutcome.value = outcome
    }

    /** One-shot failures reset to success after they are used. */
    fun consume(): DemoOutcome {
        val outcome = _nextOutcome.value
        if (outcome != DemoOutcome.SUCCEED) _nextOutcome.value = DemoOutcome.SUCCEED
        return outcome
    }
}

/** A fully simulated channel. Every result it reports is marked simulated; it never contacts anyone. */
@Singleton
class DemoAdapter @Inject constructor(private val controls: DemoControls) : ChannelAdapter {
    private val submittedAt = ConcurrentHashMap<String, Long>()

    override val descriptor = ChannelDescriptor(
        id = ChannelId.DEMO,
        mode = IntegrationMode.SIMULATED,
        capabilities = setOf(
            ChannelCapability.SEND_MESSAGE, ChannelCapability.RECEIVE_MESSAGE, ChannelCapability.AUTO_REPLY,
            ChannelCapability.DELIVERY_STATUS, ChannelCapability.MANUAL_HANDOFF, ChannelCapability.ATTACHMENTS,
        ),
        limitations = listOf("Simulated: nothing is sent to anyone. Results are labelled as demo activity."),
        maxMessageLength = 4096,
    )

    override suspend fun status() = ChannelStatus(descriptor, AvailabilityState.READY, "Simulated channel for trying ReplyPilot safely")

    override suspend fun send(message: OutboundMessage): SendOutcome = when (controls.consume()) {
        DemoOutcome.SUCCEED -> {
            val ref = "demo-${UUID.randomUUID().toString().take(8)}"
            submittedAt[ref] = System.currentTimeMillis()
            SendOutcome.Submitted(ref, simulated = true)
        }
        DemoOutcome.FAIL_TEMPORARY -> SendOutcome.Failed(FailureCode.NETWORK, "Simulated temporary failure")
        DemoOutcome.FAIL_PERMANENT -> SendOutcome.Failed(FailureCode.PROVIDER_REJECTED, "Simulated permanent failure")
        DemoOutcome.REQUIRE_MANUAL -> SendOutcome.NeedsManualAction("Simulated: this channel needs you to send manually", handoffFor(message))
    }

    override fun handoffFor(message: OutboundMessage) = ManualHandoff(ManualHandoff.Kind.COPY_TEXT, message.body)

    /** Simulated delivery confirmation arrives about 30 seconds after submission. */
    override suspend fun deliveryStatus(providerRef: String): DeliveryReport {
        val at = submittedAt[providerRef] ?: return DeliveryReport(providerRef, DeliveryState.UNKNOWN)
        val delivered = System.currentTimeMillis() - at >= 30_000
        return DeliveryReport(
            providerRef,
            if (delivered) DeliveryState.DELIVERED else DeliveryState.PENDING,
            if (delivered) "Simulated delivery confirmation" else null,
        )
    }
}
