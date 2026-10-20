package com.replypilot.core

import com.replypilot.core.accessibility.ChatApp
import com.replypilot.core.accessibility.ChatScreen
import com.replypilot.core.accessibility.ChatScreenReader
import com.replypilot.core.accessibility.ChatSession
import com.replypilot.core.accessibility.ChatSessionLogic
import com.replypilot.core.accessibility.NodeBounds
import com.replypilot.core.accessibility.UiNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private class N(
    override val text: String? = null,
    override val viewId: String? = null,
    override val description: String? = null,
    override val isEditable: Boolean = false,
    override val isShowingHint: Boolean = false,
    override val isText: Boolean = text != null && !isEditable,
    override val bounds: NodeBounds? = null,
    kids: List<N> = emptyList(),
) : UiNode {
    override val isClickable = false
    override var parent: UiNode? = null
    override val children: List<UiNode> = kids.onEach { it.parent = this }
    override fun click() = false
    override fun setText(value: String) = true
}

private const val H = 2400

class ChatSessionTest {
    /** Telegram-like chat: back button, title + subtitle in the top bar, a message box at the bottom. */
    private fun telegramChat(title: String, input: String = "", hint: Boolean = true, bubbles: List<String> = emptyList()) = N(kids = listOf(
        N(description = "Go back", bounds = NodeBounds(6, 76, 155, 230)),
        N(text = title, bounds = NodeBounds(302, 83, 903, 173)),
        N(text = "last seen recently", bounds = NodeBounds(302, 161, 903, 212)),
        N(text = "an older message mentioning someone", bounds = NodeBounds(40, 900, 1000, 980)),
        N(text = input, isEditable = true, isShowingHint = hint, bounds = NodeBounds(150, 2250, 900, 2380)),
    ) + bubbles.map { N(text = it, bounds = NodeBounds(300, 1800, 1000, 1900)) })

    @Test
    fun `reads a generic chat title and composer`() {
        val chat = ChatScreenReader.read(telegramChat("Ada Okafor"), "org.telegram.messenger", H)
        assertEquals("Ada Okafor", chat?.title)
    }

    @Test
    fun `a screen without a message box is not a chat, such as a channel or the chat list`() {
        val channel = N(kids = listOf(N(text = "News channel", bounds = NodeBounds(302, 83, 903, 173))))
        assertNull(ChatScreenReader.read(channel, "org.telegram.messenger", H))
        val listWithTopSearch = N(kids = listOf(N(text = "Chats", bounds = NodeBounds(60, 100, 900, 180)), N(isEditable = true, bounds = NodeBounds(20, 260, 1060, 360))))
        assertNull(ChatScreenReader.read(listWithTopSearch, "com.instagram.android", H))
    }

    @Test
    fun `whatsapp uses its stable view ids`() {
        val pkg = "com.whatsapp.w4b"
        val root = N(kids = listOf(N(text = "Eze EZE", viewId = "$pkg:id/conversation_contact_name"), N(text = "Message", viewId = "$pkg:id/entry", isEditable = true)))
        assertEquals("Eze EZE", ChatScreenReader.read(root, pkg, H)?.title)
    }

    @Test
    fun `instagram reads the header title, not usernames inside shared posts`() {
        val pkg = "com.instagram.android"
        val root = N(kids = listOf(
            N(viewId = "$pkg:id/header_title", description = "Chidumebi", text = "Chidumebi", isText = false, bounds = NodeBounds(319, 106, 518, 156)),
            N(text = "jasminesaaka", bounds = NodeBounds(150, 60, 600, 100)),
            N(viewId = "$pkg:id/row_thread_composer_edittext", text = "Message...", isEditable = true, isShowingHint = true, bounds = NodeBounds(166, 2248, 599, 2386)),
        ))
        assertEquals("Chidumebi", ChatScreenReader.read(root, pkg, H)?.title)
    }

    @Test
    fun `messenger reads the name from its labelled header`() {
        val root = N(kids = listOf(
            N(description = "Back", bounds = NodeBounds(11, 87, 143, 219)),
            N(description = "Ogene Felicia, Thread Details", bounds = NodeBounds(154, 87, 512, 219)),
            N(description = "Thread Details", bounds = NodeBounds(937, 87, 1069, 219)),
            N(text = "Message", isEditable = true, isShowingHint = true, bounds = NodeBounds(440, 2291, 859, 2391)),
        ))
        assertEquals("Ogene Felicia", ChatScreenReader.read(root, "com.facebook.orca", H)?.title)
    }

    @Test
    fun `a placeholder kept as real text still counts as an empty box`() {
        val assist = ChatSession.Assist(ChatApp.TELEGRAM, startedAt = 0, chatTitle = "Saved Messages", text = "Hi there")
        fun screen(input: String, bubbles: List<String>) =
            ChatScreenReader.read(telegramChat("Saved Messages", input, hint = false, bubbles = bubbles), "org.telegram.messenger", H)!!
        assist.filled = true
        assist.emptyText = "Message"
        assertEquals(ChatSessionLogic.Step.Sent, ChatSessionLogic.next(assist, screen("Message", listOf("Hi there\nSent at 23:29, Seen")), 2_000))
    }

    @Test
    fun `a multiple pick ignores the chat already chosen and takes the next one`() {
        val pick = ChatSession.Pick(ChatApp.TELEGRAM, startedAt = 0, multiple = true)
        pick.picked += "Ada Okafor"
        fun read(title: String) = ChatScreenReader.read(telegramChat(title), "org.telegram.messenger", H)
        assertEquals(ChatSessionLogic.Step.Wait, ChatSessionLogic.next(pick, read("ada okafor"), now = 5_000))
        assertEquals(ChatSessionLogic.Step.PickDone("Ben Eze"), ChatSessionLogic.next(pick, read("Ben Eze"), now = 6_000))
    }

    @Test
    fun `an admin-only group is reported once instead of being skipped silently`() {
        val pkg = "com.whatsapp.w4b"
        val announcements = N(kids = listOf(N(text = "GRM Members", viewId = "$pkg:id/conversation_contact_name"), N(text = "Only admins can send messages")))
        assertEquals("GRM Members", ChatScreenReader.readOnlyTitle(announcements, pkg))
        assertNull(ChatScreenReader.read(announcements, pkg, H))
        val pick = ChatSession.Pick(ChatApp.WHATSAPP, startedAt = 0, multiple = true)
        assertEquals(ChatSessionLogic.Step.CantAdd("GRM Members"), ChatSessionLogic.next(pick, null, 5_000, "GRM Members"))
        pick.warned += "GRM Members"
        assertEquals(ChatSessionLogic.Step.Wait, ChatSessionLogic.next(pick, null, 6_000, "GRM Members"))
        val writable = N(kids = listOf(N(text = "Eze EZE", viewId = "$pkg:id/conversation_contact_name"), N(viewId = "$pkg:id/entry", isEditable = true)))
        assertNull("a normal chat is not read-only", ChatScreenReader.readOnlyTitle(writable, pkg))
    }

    @Test
    fun `pick waits for the app to settle, then completes with the open chat`() {
        val pick = ChatSession.Pick(ChatApp.TELEGRAM, startedAt = 0)
        val screen = ChatScreenReader.read(telegramChat("Ada Okafor"), "org.telegram.messenger", H)
        assertEquals(ChatSessionLogic.Step.Wait, ChatSessionLogic.next(pick, screen, now = 100))
        assertEquals(ChatSessionLogic.Step.PickDone("Ada Okafor"), ChatSessionLogic.next(pick, screen, now = 2_000))
    }

    @Test
    fun `assist fills only the right chat and reports sent once the box empties`() {
        val assist = ChatSession.Assist(ChatApp.TELEGRAM, startedAt = 0, chatTitle = "ada.okafor", text = "Hello")
        fun screen(title: String, input: String, hint: Boolean, bubbles: List<String> = listOf("Hello")): ChatScreen? =
            ChatScreenReader.read(telegramChat(title, input, hint, bubbles), "org.telegram.messenger", H)
        assertEquals(ChatSessionLogic.Step.Wait, ChatSessionLogic.next(assist, screen("someone.else", "", true), 2_000))
        val before = screen("ada.okafor", "", true)!!
        assertEquals(ChatSessionLogic.Step.Fill, ChatSessionLogic.next(assist, before, 2_000))
        assist.filled = true
        assist.shownBefore = ChatSessionLogic.timesShown(before, assist.text)
        assertEquals("an earlier identical message counts once", 1, assist.shownBefore)
        assertEquals("waiting while the user reviews the text", ChatSessionLogic.Step.Wait, ChatSessionLogic.next(assist, screen("ada.okafor", "Hello", false), 3_000))
        assertEquals("clearing the box is not sending", ChatSessionLogic.Step.Wait, ChatSessionLogic.next(assist, screen("ada.okafor", "Message…", true), 3_500))
        assertEquals(ChatSessionLogic.Step.Sent, ChatSessionLogic.next(assist, screen("ada.okafor", "Message…", true, listOf("Hello", "Hello  23:31")), 4_000))
    }
}
