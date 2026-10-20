package com.replypilot.core

import android.app.Notification
import android.app.PendingIntent
import android.app.Person
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.replypilot.core.notifications.NotificationParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotificationParserTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val me = Person.Builder().setName("Me").build()

    private fun replyAction(): Notification.Action {
        val pending = PendingIntent.getBroadcast(context, 0, Intent("reply"), PendingIntent.FLAG_MUTABLE)
        val input = RemoteInput.Builder("reply_text").setLabel("Reply").build()
        return Notification.Action.Builder(Icon.createWithResource(context, android.R.drawable.ic_menu_send), "Reply", pending)
            .addRemoteInput(input).build()
    }

    private fun messaging(style: Notification.MessagingStyle, withReply: Boolean = true) =
        Notification.Builder(context, "test").setSmallIcon(android.R.drawable.ic_dialog_info).setStyle(style)
            .apply { if (withReply) addAction(replyAction()) }.build()

    @Test
    fun `extracts the latest message, sender and reply action`() {
        val ada = Person.Builder().setName("Ada Okafor").build()
        val style = Notification.MessagingStyle(me)
            .addMessage("Hello", 1_000, ada)
            .addMessage("How much is it?", 2_000, ada)
        val parsed = requireNotNull(NotificationParser.parse(messaging(style), 5_000))
        assertEquals("Ada Okafor", parsed.sender)
        assertEquals("How much is it?", parsed.text)
        assertEquals(2_000L, parsed.messageTime)
        assertFalse(parsed.isGroup)
        assertFalse(parsed.isFromSelf)
        assertNotNull(parsed.replyAction)
    }

    @Test
    fun `group conversations are flagged`() {
        val ben = Person.Builder().setName("Ben").build()
        val style = Notification.MessagingStyle(me).setConversationTitle("Book club").setGroupConversation(true)
            .addMessage("Are we meeting?", 1_000, ben)
        val parsed = NotificationParser.parse(messaging(style), 5_000)
        assertEquals("Book club", parsed?.conversation)
        assertTrue(parsed?.isGroup == true)
    }

    @Test
    fun `messages written by the device owner are marked as self`() {
        val style = Notification.MessagingStyle(me).addMessage("On my way", 1_000, null as Person?)
        assertTrue(NotificationParser.parse(messaging(style), 5_000)?.isFromSelf == true)
    }

    @Test
    fun `group summaries are ignored`() {
        val summary = Notification.Builder(context, "test").setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("3 new messages").setContentText("…").setGroup("g").setGroupSummary(true).build()
        assertNull(NotificationParser.parse(summary, 0))
    }

    @Test
    fun `notifications without a reply action still parse but expose no action`() {
        val ada = Person.Builder().setName("Ada").build()
        val parsed = NotificationParser.parse(messaging(Notification.MessagingStyle(me).addMessage("Hi", 1, ada), withReply = false), 0)
        assertNull(parsed?.replyAction)
    }
}
