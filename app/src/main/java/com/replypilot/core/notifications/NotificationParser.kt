package com.replypilot.core.notifications

import android.app.Notification
import android.app.RemoteInput
import android.os.Build
import android.os.Bundle
import android.os.Parcelable

/** What ReplyPilot extracts from a messaging notification. Nothing else is read or kept. */
data class ParsedNotification(
    val conversation: String,
    val sender: String,
    val text: String,
    val isGroup: Boolean,
    val isFromSelf: Boolean,
    val messageTime: Long,
    val replyAction: Notification.Action?,
    val replyInputs: Array<RemoteInput>,
)

object NotificationParser {
    private val SELF_NAMES = setOf("you", "me")

    fun parse(notification: Notification, postTime: Long): ParsedNotification? {
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
        if (notification.flags and Notification.FLAG_ONGOING_EVENT != 0) return null
        val extras = notification.extras ?: return null

        val messaging = lastMessagingStyleMessage(extras)
        val title = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?: return null
        val isGroup = extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false) ||
            extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE) != null
        val selfName = extras.getCharSequence(Notification.EXTRA_SELF_DISPLAY_NAME)?.toString()

        val sender = messaging?.sender ?: title
        val text = messaging?.text ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: return null
        if (text.isBlank()) return null
        val fromSelf = messaging?.senderIsNull == true ||
            sender.equals(selfName, ignoreCase = true) || sender.lowercase() in SELF_NAMES

        val action = notification.actions?.firstOrNull { a -> a.remoteInputs?.any { it.allowFreeFormInput } == true }
        return ParsedNotification(
            conversation = title,
            sender = sender,
            text = text,
            isGroup = isGroup,
            isFromSelf = fromSelf,
            messageTime = messaging?.time?.takeIf { it > 0 } ?: postTime,
            replyAction = action,
            replyInputs = action?.remoteInputs ?: emptyArray(),
        )
    }

    private data class StyleMessage(val text: String?, val sender: String?, val senderIsNull: Boolean, val time: Long)

    @Suppress("DEPRECATION")
    private fun lastMessagingStyleMessage(extras: Bundle): StyleMessage? {
        val messages: Array<Parcelable> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            extras.getParcelableArray(Notification.EXTRA_MESSAGES, Parcelable::class.java)
        } else {
            extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        } ?: return null
        val last = messages.lastOrNull() as? Bundle ?: return null
        val person = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                last.getParcelable("sender_person", android.app.Person::class.java)?.name?.toString()
            } else {
                last.getParcelable<android.app.Person>("sender_person")?.name?.toString()
            }
        } else {
            null
        }
        val sender = person ?: last.getCharSequence("sender")?.toString()
        return StyleMessage(
            text = last.getCharSequence("text")?.toString(),
            sender = sender,
            // In MessagingStyle a null sender means the message was written by the device owner.
            senderIsNull = sender == null,
            time = last.getLong("time", 0L).takeIf { it > 0 } ?: 0L,
        )
    }
}
