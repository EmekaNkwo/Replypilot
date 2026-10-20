package com.replypilot.domain.scheduling

import com.replypilot.domain.model.ChannelId
import com.replypilot.domain.model.Recipient

/** Lightweight per-channel address checks. They catch typos; they do not prove an account exists. */
object AddressRules {
    private val PHONE = Regex("""^\+?[0-9]{7,15}$""")
    private val EMAIL = Regex("""^[^@\s]+@[^@\s]+\.[^@\s]+$""")
    private val HANDLE = Regex("""^@?[A-Za-z0-9_.]{3,64}$""")

    fun normalize(channel: ChannelId, address: String): String {
        val trimmed = address.trim()
        return when (channel) {
            ChannelId.SMS, ChannelId.WHATSAPP, ChannelId.WHATSAPP_BUSINESS ->
                trimmed.filter { it.isDigit() || it == '+' }
            ChannelId.EMAIL -> trimmed.lowercase()
            else -> trimmed
        }
    }

    /**
     * Identity of a recipient for duplicate checks. Chats and groups picked by name compare by name — normalising them
     * like a phone number would reduce every name to "" and make all of them look like one recipient.
     */
    fun recipientKey(recipient: Recipient, channel: ChannelId): String =
        if (recipient.isNamedChat) "name:" + recipient.address.trim().lowercase()
        else "address:" + normalize(channel, recipient.address).lowercase()

    const val MAX_GROUP_NAME = 100

    fun isValid(recipient: Recipient, channel: ChannelId): Boolean =
        if (recipient.isNamedChat) recipient.address.isNotBlank() && recipient.address.trim().length <= MAX_GROUP_NAME
        else isValid(channel, recipient.address)

    fun isValid(channel: ChannelId, address: String): Boolean {
        val value = normalize(channel, address)
        return when (channel) {
            ChannelId.SMS, ChannelId.WHATSAPP, ChannelId.WHATSAPP_BUSINESS -> PHONE.matches(value)
            ChannelId.EMAIL -> EMAIL.matches(value)
            ChannelId.TELEGRAM, ChannelId.INSTAGRAM -> HANDLE.matches(value) || PHONE.matches(value)
            ChannelId.MESSENGER, ChannelId.DEMO -> value.isNotBlank() && value.length <= 128
        }
    }

    fun hint(channel: ChannelId): String = when (channel) {
        ChannelId.SMS, ChannelId.WHATSAPP, ChannelId.WHATSAPP_BUSINESS -> "Phone number with country code, e.g. +2348012345678"
        ChannelId.EMAIL -> "Email address"
        ChannelId.TELEGRAM, ChannelId.INSTAGRAM -> "Username or phone number"
        ChannelId.MESSENGER -> "Profile name or ID"
        ChannelId.DEMO -> "Any name"
    }
}
