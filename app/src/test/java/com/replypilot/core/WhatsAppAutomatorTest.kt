package com.replypilot.core

import com.replypilot.core.accessibility.AutoSendResult
import com.replypilot.core.accessibility.NodeBounds
import com.replypilot.domain.model.Attachment
import com.replypilot.core.accessibility.OpenTarget
import com.replypilot.core.accessibility.UiNode
import com.replypilot.core.accessibility.UiScreen
import com.replypilot.core.accessibility.WhatsAppAutomator
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A tiny scripted WhatsApp: screens are rebuilt from mutable state so clicks and typing change what's "on screen". */
private class FakeWhatsApp(
    private val pkg: String = "com.whatsapp.w4b",
    private val numberOnWhatsApp: Boolean = true,
    private val groups: List<String> = emptyList(),
    private val sendWorks: Boolean = true,
    /** "Send to…" list ticks this chat whatever row is tapped (to prove ReplyPilot checks the footer). */
    private val pickerTicks: String? = null,
    /** WhatsApp ignores the number and shows the "Send to…" list instead of the person's preview. */
    private val ignoresJid: Boolean = false,
    /** How many shares WhatsApp opens on the person's chat before falling back to its "Send to…" list. */
    private var jidShares: Int = Int.MAX_VALUE,
) : UiScreen {
    var screen = "home"
    var chatTitle = ""
    var entry = ""
    var searchQuery = ""
    val sent = mutableListOf<Pair<String, String>>()
    var left = false
    var caption = ""
    var sharing: List<Attachment> = emptyList()
    var docCaption = ""
    var blankFrames = 0
    var shareSearching = false
    var selected: String? = null
    val sentFiles = mutableListOf<Pair<String, String>>()
    /** "Send to…" rows by top edge: like the real list, they only react to a real tap. */
    private val rowTargets = mutableMapOf<Int, () -> Unit>()

    inner class Node(
        override val viewId: String? = null,
        override val text: String? = null,
        override val description: String? = null,
        override val isClickable: Boolean = false,
        override val isCheckable: Boolean = false,
        override val bounds: NodeBounds? = null,
        kids: List<Node> = emptyList(),
        val onClick: () -> Unit = {},
        val onText: (String) -> Unit = {},
    ) : UiNode {
        override var parent: UiNode? = null
        override val children: List<UiNode> = kids.onEach { it.parent = this }
        override fun click(): Boolean = if (isClickable) { onClick(); true } else false
        override fun setText(value: String): Boolean { onText(value); return true }
    }

    private fun id(name: String) = "$pkg:id/$name"

    override fun root(): UiNode = if (blankFrames > 0) {
        blankFrames--
        // Like the real app: an empty frame, then a preview that has drawn only its caption box so far.
        if (blankFrames == 0 && screen == "docpreview") Node(kids = listOf(Node(id("caption"), text = ""))) else Node()
    } else when (screen) {
        "home" -> Node(kids = listOf(
            // Real layout: the outer bar isn't clickable; its inner layout is.
            Node(id("my_search_bar"), kids = listOf(Node(id("search_bar_inner_layout"), description = "Search…", isClickable = true, onClick = { screen = "search" }))),
        ))
        // Like the real app: the old chat list (same row IDs, but dead rows) sits behind the results list.
        "search" -> Node(kids = listOf(
            Node(id("search_input"), text = searchQuery, onText = { searchQuery = it }),
            Node(kids = groups.map { name -> Node(isClickable = true, onClick = {}, kids = listOf(Node(id("conversations_row_contact_name"), text = name))) }),
            Node(id("result_list"), kids = groups.filter { searchQuery.isNotBlank() && it.contains(searchQuery, ignoreCase = true) }.map { name ->
                Node(isClickable = true, onClick = { screen = "chat"; chatTitle = name }, kids = listOf(Node(id("conversations_row_contact_name"), text = name)))
            }),
        ))
        "dialog" -> Node(kids = listOf(Node(text = "The phone number +234 isn't on WhatsApp.")))
        // Media preview opened for one person: caption box, their chip, Send.
        "composer" -> Node(kids = listOf(
            Node(id("caption"), text = caption),
            Node(id("media_recipients"), kids = listOf(Node(text = chatTitle, isClickable = true))),
            Node(id("send"), description = "Send", isClickable = true, onClick = { sentFiles += chatTitle to caption; screen = "chat"; entry = "" }),
        ))
        "docpreview" -> Node(kids = listOf(
            Node(id("document_file_name"), text = sharing.first().fileName),
            Node(id("caption"), text = docCaption, onText = { docCaption = it }),
            Node(id("media_recipients_layout"), kids = listOf(Node(text = chatTitle, isClickable = true))),
            Node(id("send"), description = "Send", isClickable = true, onClick = { sentFiles += chatTitle to docCaption; screen = "chat"; entry = "" }),
        ))
        "picker" -> Node(kids = buildList {
            add(Node(id("menuitem_search"), description = "Search", isClickable = true, onClick = { shareSearching = true }))
            if (shareSearching) add(Node(id("search_src_text"), text = searchQuery, onText = { searchQuery = it }))
            rowTargets.clear()
            groups.filter { !shareSearching || it.contains(searchQuery, ignoreCase = true) }.forEachIndexed { index, name ->
                // Real layout: a checkable row that isn't marked clickable, inside a list that is only long-clickable.
                val top = 300 + 200 * index
                add(Node(isCheckable = true, bounds = NodeBounds(0, top, 1080, top + 180), kids = listOf(Node(id("contactpicker_row_name"), text = name))))
                rowTargets[top] = { selected = pickerTicks ?: name }
            }
            selected?.let { chosen ->
                add(Node(id("appended_message"), text = caption))
                add(Node(id("recipients"), text = chosen))
                add(Node(id("send"), description = "Send", isClickable = true, onClick = {
                    // Like WhatsApp: a document goes through its own preview (caption dropped); media is sent from here.
                    // The real app shows a blank frame while the next screen starts; it must never count as "sent".
                    blankFrames = 2
                    if (sharing.any { !it.isMedia }) { screen = "docpreview"; chatTitle = chosen; docCaption = "" }
                    else { sentFiles += chosen to caption; screen = "chat"; chatTitle = chosen; entry = "" }
                }))
            }
        })
        else -> Node(kids = listOf(
            Node(id("conversation_contact_name"), text = chatTitle),
            Node(id("entry"), text = entry.ifEmpty { "Message" }, onText = { entry = it }),
            Node(id("send"), description = "Send", isClickable = true, onClick = {
                if (sendWorks) { sent += chatTitle to entry; entry = "" }
            }),
        ))
    }

    override fun open(target: OpenTarget): Boolean {
        when (target) {
            is OpenTarget.Chat -> if (numberOnWhatsApp) { screen = "chat"; chatTitle = target.phoneDigits; entry = target.prefill } else screen = "dialog"
            is OpenTarget.App -> screen = "home"
            is OpenTarget.Share -> {
                caption = target.caption
                sharing = target.attachments
                if (target.jid != null && !ignoresJid && jidShares-- > 0) { screen = "composer"; chatTitle = target.jid.substringBefore('@') }
                else { screen = "picker"; shareSearching = false; selected = null; searchQuery = "" }
            }
        }
        return true
    }

    override fun leave() { left = true }

    override fun tap(bounds: NodeBounds): Boolean {
        rowTargets[bounds.top]?.invoke() ?: return false
        return true
    }
}

class WhatsAppAutomatorTest {
    private fun automator(app: FakeWhatsApp) = WhatsAppAutomator(app, pollMs = 10, openTimeoutMs = 200, stepTimeoutMs = 200, confirmTimeoutMs = 200)

    @Test
    fun `sends to a number and confirms the box cleared`() = runTest {
        val app = FakeWhatsApp()
        assertEquals(AutoSendResult.Sent, automator(app).sendToNumber("com.whatsapp.w4b", "2347037123201", "Hello"))
        assertEquals(listOf("2347037123201" to "Hello"), app.sent)
        assertTrue("always returns the user to the home screen", app.left)
    }

    @Test
    fun `number not on WhatsApp is reported, nothing is sent`() = runTest {
        val app = FakeWhatsApp(numberOnWhatsApp = false)
        assertEquals(AutoSendResult.NotOnWhatsApp, automator(app).sendToNumber("com.whatsapp.w4b", "2340000000000", "Hello"))
        assertTrue(app.sent.isEmpty())
    }

    @Test
    fun `finds a group through the inner search layout and sends once`() = runTest {
        val app = FakeWhatsApp(groups = listOf("Family", "Family Business"))
        assertEquals(AutoSendResult.Sent, automator(app).sendToGroup("com.whatsapp.w4b", "family business", "Meeting at 6"))
        assertEquals(listOf("Family Business" to "Meeting at 6"), app.sent)
    }

    @Test
    fun `a chat picked with a shortened header name is still found, but never guessed`() = runTest {
        val app = FakeWhatsApp(groups = listOf("Ezenwanyi & Her Billionaires 💸", "Family"))
        assertEquals(AutoSendResult.Sent, automator(app).sendToGroup("com.whatsapp.w4b", "Ezenwanyi & Her Billionaires…", "Hi"))
        val twins = FakeWhatsApp(groups = listOf("Family Business North", "Family Business South"))
        assertTrue(automator(twins).sendToGroup("com.whatsapp.w4b", "Family Business…", "Hi") is AutoSendResult.GroupNotFound)
        assertTrue(twins.sent.isEmpty())
    }

    @Test
    fun `a missing group fails without sending anywhere`() = runTest {
        val app = FakeWhatsApp(groups = listOf("Family"))
        val result = automator(app).sendToGroup("com.whatsapp.w4b", "No such group", "Hi")
        assertTrue(result is AutoSendResult.GroupNotFound)
        assertTrue(app.sent.isEmpty())
    }

    private val photo = listOf(Attachment("a", "flyer.jpg", "image/jpeg", 10, "/x/flyer.jpg"))

    @Test
    fun `shares files to a number through the addressed preview`() = runTest {
        val app = FakeWhatsApp()
        assertEquals(AutoSendResult.Sent, automator(app).sendFilesToNumber("com.whatsapp.w4b", "2347037123201", "See flyer", photo))
        assertEquals(listOf("2347037123201" to "See flyer"), app.sentFiles)
        assertTrue(app.left)
    }

    @Test
    fun `if WhatsApp falls back to its Send-to list for a number, nobody is picked`() = runTest {
        val app = FakeWhatsApp(groups = listOf("Family"), ignoresJid = true)
        assertTrue(automator(app).sendFilesToNumber("com.whatsapp.w4b", "2347037123201", "See flyer", photo) is AutoSendResult.NotReady)
        assertTrue(app.sentFiles.isEmpty())
    }

    @Test
    fun `shares files to a chat by name after checking the selection`() = runTest {
        val app = FakeWhatsApp(groups = listOf("Family", "Family Business"))
        assertEquals(AutoSendResult.Sent, automator(app).sendFilesToChat("com.whatsapp.w4b", "Family Business", "Agenda", photo))
        assertEquals(listOf("Family Business" to "Agenda"), app.sentFiles)
    }

    @Test
    fun `each file goes in its own share, documents through their preview with the caption kept`() = runTest {
        val app = FakeWhatsApp(groups = listOf("Family Business"))
        val files = listOf(Attachment("d", "menu.pdf", "application/pdf", 10, "/x/menu.pdf")) + photo
        assertEquals(AutoSendResult.Sent, automator(app).sendFilesToChat("com.whatsapp.w4b", "Family Business", "Agenda", files))
        assertEquals("caption on the first file only", listOf("Family Business" to "Agenda", "Family Business" to ""), app.sentFiles)
    }

    @Test
    fun `a failure after some files went out reports how many were sent and stops`() = runTest {
        val app = FakeWhatsApp(groups = listOf("Family"), jidShares = 1)
        val files = photo + Attachment("b", "menu.jpg", "image/jpeg", 10, "/x/menu.jpg")
        val result = automator(app).sendFilesToNumber("com.whatsapp.w4b", "2347037123201", "See flyer", files)
        assertEquals(1, (result as AutoSendResult.Unconfirmed).filesSent)
        assertEquals("nobody is picked from the fallback list", 1, app.sentFiles.size)
    }

    @Test
    fun `a selection that doesn't match the chat name is never sent`() = runTest {
        val app = FakeWhatsApp(groups = listOf("Family Business"), pickerTicks = "Someone Else")
        val result = automator(app).sendFilesToChat("com.whatsapp.w4b", "Family Business", "Agenda", photo)
        assertTrue("$result", result is AutoSendResult.NotReady)
        assertTrue(app.sentFiles.isEmpty())
    }

    @Test
    fun `a chat missing from the Send-to list is reported`() = runTest {
        val app = FakeWhatsApp(groups = listOf("Family"))
        assertTrue(automator(app).sendFilesToChat("com.whatsapp.w4b", "Work", "Agenda", photo) is AutoSendResult.GroupNotFound)
        assertTrue(app.sentFiles.isEmpty())
    }

    @Test
    fun `a press that doesn't clear the box is reported as unconfirmed, not sent`() = runTest {
        val app = FakeWhatsApp(sendWorks = false)
        assertTrue(automator(app).sendToNumber("com.whatsapp.w4b", "2347037123201", "Hello") is AutoSendResult.Unconfirmed)
    }
}
