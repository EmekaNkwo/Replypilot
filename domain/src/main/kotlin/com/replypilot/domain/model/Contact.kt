package com.replypilot.domain.model

import kotlinx.serialization.Serializable

/**
 * An address on one specific channel. A phone number on WhatsApp is not assumed to be
 * the same person as that number on SMS or Telegram; each identity is stored per channel.
 */
@Serializable
data class ChannelIdentity(
    val channel: ChannelId,
    val address: String,
)

@Serializable
data class Contact(
    val id: String,
    val firstName: String,
    val lastName: String = "",
    val identities: List<ChannelIdentity> = emptyList(),
    val preferredChannel: ChannelId? = null,
    val tags: Set<String> = emptySet(),
    val groups: Set<String> = emptySet(),
    val timeZone: String? = null,
    val optedOut: Boolean = false,
    val optedOutAt: Long? = null,
    val notes: String = "",
    val customFields: Map<String, String> = emptyMap(),
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
) {
    val displayName: String get() = listOf(firstName, lastName).filter { it.isNotBlank() }.joinToString(" ")

    fun addressOn(channel: ChannelId): String? = identities.firstOrNull { it.channel == channel }?.address

    val channels: Set<ChannelId> get() = identities.map { it.channel }.toSet()

    fun templateVariables(): Map<String, String> = buildMap {
        putAll(customFields)
        if (firstName.isNotBlank()) put("firstName", firstName)
        if (lastName.isNotBlank()) put("lastName", lastName)
        if (displayName.isNotBlank()) put("fullName", displayName)
    }
}
