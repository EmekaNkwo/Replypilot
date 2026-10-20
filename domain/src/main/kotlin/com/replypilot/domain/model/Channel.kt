package com.replypilot.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class ChannelId(val displayName: String) {
    WHATSAPP("WhatsApp"),
    WHATSAPP_BUSINESS("WhatsApp Business Platform"),
    TELEGRAM("Telegram"),
    INSTAGRAM("Instagram"),
    MESSENGER("Messenger"),
    SMS("SMS"),
    EMAIL("Email"),
    DEMO("Demo channel"),
}

/** Operations a channel adapter may support. Adapters declare these explicitly; nothing is assumed. */
@Serializable
enum class ChannelCapability(val label: String) {
    SEND_MESSAGE("Send messages unattended"),
    RECEIVE_MESSAGE("Receive incoming messages"),
    AUTO_REPLY("Reply automatically"),
    ATTACHMENTS("Send attachments"),
    DELIVERY_STATUS("Report delivery confirmation"),
    GROUP_CONVERSATIONS("Group conversations"),
    CANCEL_PENDING("Cancel a submitted send"),
    MANUAL_HANDOFF("Manual handoff to the messaging app"),
}

@Serializable
enum class IntegrationMode(val label: String) {
    OFFICIAL_API("Official business API"),
    DEVICE_NOTIFICATION("On-device notification access"),
    MANUAL_HANDOFF("Manual handoff"),
    SIMULATED("Simulated (demo)"),
}

enum class AvailabilityState(val label: String, val isUsable: Boolean) {
    READY("Ready", true),
    NEEDS_PERMISSION("Needs permission", false),
    NOT_CONFIGURED("Not configured", false),
    APP_NOT_INSTALLED("App not installed", false),
    MANUAL_ONLY("Manual only", true),
    PLANNED("Planned", false),
    DISABLED("Disabled", false),
}

/** A static, honest statement of what an adapter can do. */
data class ChannelDescriptor(
    val id: ChannelId,
    val mode: IntegrationMode,
    val capabilities: Set<ChannelCapability>,
    val limitations: List<String> = emptyList(),
    val maxMessageLength: Int? = null,
) {
    fun supports(capability: ChannelCapability): Boolean = capability in capabilities
    val isSimulated: Boolean get() = mode == IntegrationMode.SIMULATED
}

data class ChannelStatus(
    val descriptor: ChannelDescriptor,
    val availability: AvailabilityState,
    val detail: String,
) {
    val id: ChannelId get() = descriptor.id
}
