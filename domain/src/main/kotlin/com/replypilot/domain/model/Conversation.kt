package com.replypilot.domain.model

/** An inbound message observed on a channel (or fabricated by the simulator). */
data class IncomingMessage(
    val id: String,
    val channel: ChannelId,
    /** Stable per-channel conversation identifier (chat title or thread id). */
    val conversationKey: String,
    val senderName: String,
    val senderAddress: String? = null,
    val text: String,
    val isGroup: Boolean = false,
    val receivedAt: Long,
    /** True when the platform says this message was written by the device owner. */
    val isFromSelf: Boolean = false,
    /** Opaque handle the adapter uses to reply in place (e.g. a notification key). */
    val replyTarget: String? = null,
    val simulated: Boolean = false,
)

/** A record that ReplyPilot sent an automatic reply. Used for cooldowns, limits and loop detection. */
data class ReplyRecord(
    val ruleId: String,
    val channel: ChannelId,
    val conversationKey: String,
    /** Hash of the reply text (see Fingerprints), used to recognise our own message echoed back. */
    val textHash: String,
    val at: Long,
)

data class ConversationState(
    val channel: ChannelId,
    val conversationKey: String,
    val humanTakeoverUntil: Long? = null,
)
