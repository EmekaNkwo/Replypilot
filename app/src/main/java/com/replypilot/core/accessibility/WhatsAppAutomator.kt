package com.replypilot.core.accessibility

import com.replypilot.domain.model.Attachment
import kotlinx.coroutines.delay

/** The slice of an on-screen element the automator needs. Backed by AccessibilityNodeInfo, faked in tests. */
data class NodeBounds(val left: Int, val top: Int, val right: Int, val bottom: Int)

interface UiNode {
    val viewId: String?
    val text: String?
    val description: String?
    val isClickable: Boolean
    /** A selectable row (e.g. in WhatsApp's "Send to…" list); it takes a click even though it isn't marked clickable. */
    val isCheckable: Boolean get() = false
    val isEditable: Boolean get() = false
    /** The input is showing its placeholder (e.g. "Message"), i.e. it's empty. */
    val isShowingHint: Boolean get() = false
    /** A plain text view (as opposed to a container or image). */
    val isText: Boolean get() = text != null
    val bounds: NodeBounds? get() = null
    val parent: UiNode?
    val children: List<UiNode>
    fun click(): Boolean
    fun setText(value: String): Boolean
}

/** The current screen plus the few actions the automator may take. */
interface UiScreen {
    /** Root of the active window, or null while it's changing. */
    fun root(): UiNode?
    fun open(target: OpenTarget): Boolean
    fun leave()
    /** Taps the middle of [bounds] like a finger would, for rows that ignore accessibility clicks. */
    fun tap(bounds: NodeBounds): Boolean = false
}

sealed interface OpenTarget {
    data class Chat(val packageName: String, val phoneDigits: String, val prefill: String) : OpenTarget
    data class App(val packageName: String) : OpenTarget
    /** Hands [attachments] to WhatsApp's share flow: straight to one person's chat with [jid], otherwise "Send to…". */
    data class Share(val packageName: String, val jid: String?, val caption: String, val attachments: List<Attachment>) : OpenTarget
}

sealed interface AutoSendResult {
    data object Sent : AutoSendResult
    data object Disabled : AutoSendResult
    data object Locked : AutoSendResult
    data object NotOnWhatsApp : AutoSendResult
    /** The phone's own settings stop ReplyPilot opening WhatsApp while it's in the background (e.g. Xiaomi). */
    data object BlockedByPhone : AutoSendResult
    data class GroupNotFound(val name: String) : AutoSendResult
    /** WhatsApp didn't reach a state ReplyPilot recognises; nothing was sent. */
    data class NotReady(val reason: String) : AutoSendResult
    /**
     * Send was pressed but the result couldn't be confirmed; the message may have gone out.
     * [filesSent]: for a multi-file share, how many files were confirmed before this one.
     */
    data class Unconfirmed(val reason: String, val filesSent: Int = 0) : AutoSendResult
}

/**
 * Drives WhatsApp's own UI to send one message, then checks that it left the input box.
 * Every step waits for a recognisable screen and gives up with a clear reason instead of guessing,
 * so a WhatsApp redesign produces a visible failure rather than a message sent to the wrong chat.
 */
class WhatsAppAutomator(
    private val screen: UiScreen,
    private val pollMs: Long = 250,
    private val openTimeoutMs: Long = 15_000,
    private val stepTimeoutMs: Long = 8_000,
    private val confirmTimeoutMs: Long = 6_000,
    /** Debug builds: notes about what the automator saw (view IDs only, never text). */
    private val trace: (String) -> Unit = {},
) {
    suspend fun sendToNumber(packageName: String, phoneDigits: String, text: String): AutoSendResult = withLeave {
        if (!screen.open(OpenTarget.Chat(packageName, phoneDigits, text))) return@withLeave AutoSendResult.NotReady("WhatsApp couldn't be opened")
        val ready = waitFor(openTimeoutMs) { root ->
            when {
                root.findId(id(packageName, "entry")) != null -> Opened.Chat
                root.findText(NOT_ON_WHATSAPP_HINTS) != null -> Opened.NotOnWhatsApp
                else -> null
            }
        }
        when (ready) {
            Opened.NotOnWhatsApp -> AutoSendResult.NotOnWhatsApp
            Opened.Chat -> typeAndSend(packageName, text)
            Opened.Picker, null -> AutoSendResult.NotReady("The WhatsApp chat didn't open in time")
        }
    }

    /** Sends into a chat found by its exact title (a group, or a person picked from the app's chat list). */
    suspend fun sendToGroup(packageName: String, groupName: String, text: String): AutoSendResult = withLeave {
        if (!screen.open(OpenTarget.App(packageName))) return@withLeave AutoSendResult.NotReady("WhatsApp couldn't be opened")
        val search = waitFor(openTimeoutMs) {
            it.findId(id(packageName, "search_bar_inner_layout")) ?: it.findId(id(packageName, "my_search_bar")) ?: it.findDescription("Search")
        } ?: return@withLeave AutoSendResult.NotReady("WhatsApp's search wasn't found")
        search.clickNearest()
        val input = waitFor(stepTimeoutMs) { it.findId(id(packageName, "search_input")) }
            ?: return@withLeave AutoSendResult.NotReady("WhatsApp's search box didn't open")
        input.setText(ChatNames.searchable(groupName))
        // Only trust rows inside the search results: the chat list behind it uses the same view IDs, and a row
        // found there a moment before the results render is about to disappear (clicking it does nothing).
        fun findRow(root: UiNode): UiNode? = root.findId(id(packageName, "result_list"))
            ?.findAllId(id(packageName, "conversations_row_contact_name"))
            ?.filter { ChatNames.matches(it.text, groupName) }
            // A shortened name that fits several chats must not be guessed.
            ?.takeIf { rows -> rows.mapNotNull { it.text?.trim() }.distinct().size == 1 }
            ?.firstOrNull()
        fun inChat(root: UiNode): Boolean? {
            val title = root.findId(id(packageName, "conversation_contact_name"))?.text
            return if (root.findId(id(packageName, "entry")) != null && ChatNames.matches(title, groupName)) true else null
        }
        var opened = false
        repeat(OPEN_ATTEMPTS) {
            if (opened) return@repeat
            val row = waitFor(stepTimeoutMs) { findRow(it) } ?: return@withLeave AutoSendResult.GroupNotFound(groupName)
            row.clickNearest()
            opened = waitFor(stepTimeoutMs / 2) { inChat(it) } == true
        }
        if (!opened) return@withLeave AutoSendResult.NotReady("Couldn't confirm the \"$groupName\" chat opened")
        typeAndSend(packageName, text)
    }

    /**
     * Shares files to one person (by number). WhatsApp drops files from a share that mixes photos and documents, so each
     * file goes in its own share; the caption travels with the first.
     */
    suspend fun sendFilesToNumber(packageName: String, phoneDigits: String, caption: String, attachments: List<Attachment>): AutoSendResult =
        eachFile(caption, attachments) { text, file -> fileToNumber(packageName, phoneDigits, text, file) }

    /** Shares files to a chat by its exact title, one share per file (see [sendFilesToNumber]). */
    suspend fun sendFilesToChat(packageName: String, chatName: String, caption: String, attachments: List<Attachment>): AutoSendResult =
        eachFile(caption, attachments) { text, file -> fileToChat(packageName, chatName, text, file) }

    /** Runs [send] per file. A failure after some files went out is reported as unconfirmed, never retried blindly. */
    private suspend fun eachFile(caption: String, files: List<Attachment>, send: suspend (String, Attachment) -> AutoSendResult): AutoSendResult {
        files.forEachIndexed { index, file ->
            val result = send(if (index == 0) caption else "", file)
            if (result != AutoSendResult.Sent) {
                return if (index == 0) result
                else AutoSendResult.Unconfirmed("Sent $index of ${files.size} files; \"${file.fileName}\" failed (${describe(result)})", filesSent = index)
            }
        }
        return AutoSendResult.Sent
    }

    private fun describe(result: AutoSendResult): String = when (result) {
        is AutoSendResult.NotReady -> result.reason
        is AutoSendResult.Unconfirmed -> result.reason
        is AutoSendResult.GroupNotFound -> "chat not found"
        else -> result::class.simpleName.orEmpty()
    }

    private suspend fun fileToNumber(packageName: String, phoneDigits: String, caption: String, file: Attachment): AutoSendResult = withLeave {
        if (!screen.open(OpenTarget.Share(packageName, "$phoneDigits@s.whatsapp.net", caption, listOf(file)))) {
            return@withLeave AutoSendResult.NotReady("WhatsApp couldn't be opened with the files")
        }
        val ready = waitFor(openTimeoutMs) { root ->
            when {
                root.findId(id(packageName, "send")) != null && root.findId(id(packageName, "entry")) == null -> Opened.Chat
                root.findText(NOT_ON_WHATSAPP_HINTS) != null -> Opened.NotOnWhatsApp
                // WhatsApp fell back to its "Send to…" list: it didn't accept the number, so don't pick anyone.
                root.findId(id(packageName, "contactpicker_row_name")) != null -> Opened.Picker
                else -> null
            }
        }
        when (ready) {
            Opened.Chat -> pressShareSend(packageName, caption, fromPicker = false, chatTitle = null)
            Opened.NotOnWhatsApp -> AutoSendResult.NotOnWhatsApp
            Opened.Picker -> AutoSendResult.NotReady("WhatsApp didn't open this number's chat for the files")
            null -> AutoSendResult.NotReady("WhatsApp's file preview didn't open in time")
        }
    }

    /** One file to a chat by its exact title, choosing it in WhatsApp's "Send to…" list and checking the choice. */
    private suspend fun fileToChat(packageName: String, chatName: String, caption: String, file: Attachment): AutoSendResult = withLeave {
        if (!screen.open(OpenTarget.Share(packageName, null, caption, listOf(file)))) {
            return@withLeave AutoSendResult.NotReady("WhatsApp couldn't be opened with the files")
        }
        val search = waitFor(openTimeoutMs) { it.findId(id(packageName, "menuitem_search")) }
            ?: return@withLeave AutoSendResult.NotReady("WhatsApp's \"Send to…\" list didn't open")
        search.clickNearest()
        val input = waitFor(stepTimeoutMs) { it.findId(id(packageName, "search_src_text")) }
            ?: return@withLeave AutoSendResult.NotReady("WhatsApp's search box didn't open")
        input.setText(ChatNames.searchable(chatName))
        val wanted = chatName.trim()
        fun findRow(root: UiNode) = root.findAllId(id(packageName, "contactpicker_row_name"))
            .filter { ChatNames.matches(it.text, wanted) }
            .takeIf { rows -> rows.mapNotNull { it.text?.trim() }.distinct().size == 1 }
            ?.firstOrNull()
        // The list re-renders after the search text changes; only tap once the row has stopped moving.
        var lastBounds: NodeBounds? = null
        val row = waitFor(stepTimeoutMs) { root ->
            findRow(root)?.takeIf { found ->
                val settled = found.bounds != null && found.bounds == lastBounds
                lastBounds = found.bounds
                settled || found.bounds == null
            }
        } ?: return@withLeave AutoSendResult.GroupNotFound(chatName)
        // "Send to…" rows are checkable list items that ignore accessibility clicks on some versions: tap the row like a
        // finger would. Which chat got ticked is checked below before anything is sent.
        val rowNode = generateSequence(row) { it.parent }.firstOrNull { it.isCheckable } ?: row
        if (rowNode.bounds?.let(screen::tap) != true) rowNode.click() || row.clickNearest()
        // The footer names who will receive it. Anything other than exactly this chat: stop without sending.
        val chosen = waitFor(stepTimeoutMs) { root -> root.findId(id(packageName, "recipients"))?.text?.trim() }
            ?: return@withLeave AutoSendResult.NotReady("Couldn't select \"$chatName\" in WhatsApp")
        if (!ChatNames.matches(chosen, wanted)) {
            return@withLeave AutoSendResult.NotReady("WhatsApp selected \"$chosen\" instead of \"$chatName\"; nothing was sent")
        }
        pressShareSend(packageName, caption, fromPicker = true, chatTitle = wanted)
    }

    /**
     * Presses Send on WhatsApp's share screens and waits for them to close. After the "Send to…" list ([fromPicker]) WhatsApp
     * shows a preview for documents (its button there is "next"); that preview's Send is pressed once too. Documents arrive
     * without the caption, so it's typed into the preview's own caption box.
     *
     * Sent only on positive proof: WhatsApp has moved on to the conversation (its message box is showing, no share screen
     * is left) and, when [chatTitle] is known, that conversation is the right chat. Blank in-between frames prove nothing.
     */
    private suspend fun pressShareSend(packageName: String, caption: String, fromPicker: Boolean, chatTitle: String?): AutoSendResult {
        fillEmptyCaption(packageName, caption)
        val send = waitFor(stepTimeoutMs) { it.findId(id(packageName, "send")) }
            ?: return AutoSendResult.NotReady("WhatsApp's Send button wasn't found")
        if (!send.clickNearest()) return AutoSendResult.NotReady("WhatsApp's Send button couldn't be pressed")
        var pressedAgain = false
        var lastSeen = ""
        val closed = waitFor(confirmTimeoutMs * 2) { root ->
            val seen = (SHARE_SCREEN_IDS + listOf("entry", "caption", "send")).filter { root.findId(id(packageName, it)) != null }.joinToString()
            if (seen != lastSeen) { trace("after Send: [$seen]"); lastSeen = seen }
            val inChat = root.findId(id(packageName, "entry")) != null
            val title = root.findId(id(packageName, "conversation_contact_name"))?.text?.trim()
            when {
                // The conversation shows sent photos with a "caption" view too, so only share-only views count here.
                inChat && SHARE_SCREEN_IDS.none { root.findId(id(packageName, it)) != null } &&
                    !title.isNullOrEmpty() && (chatTitle == null || ChatNames.matches(title, chatTitle)) -> true
                // Only a fully drawn new preview (the list's footer is gone, its own Send and recipient chips are there),
                // never the screen whose Send was just pressed, and never a half-drawn frame.
                fromPicker && !pressedAgain && !inChat && root.findId(id(packageName, "appended_message")) == null &&
                    root.findId(id(packageName, "recipients")) == null && root.findId(id(packageName, "send")) != null &&
                    PREVIEW_IDS.any { root.findId(id(packageName, it)) != null } -> {
                    fillEmptyCaption(packageName, caption)
                    pressedAgain = screen.root()?.findId(id(packageName, "send"))?.clickNearest() == true
                    null
                }
                else -> null
            }
        }
        if (closed != true) {
            val root = screen.root()
            val seen = (SHARE_SCREEN_IDS + listOf("entry", "caption", "send")).filter { root?.findId(id(packageName, it)) != null }
            trace("share screen still open? root=${root != null} ids=$seen")
        }
        return if (closed == true) AutoSendResult.Sent
        else AutoSendResult.Unconfirmed("Send was pressed but WhatsApp's file preview stayed open")
    }

    /** On a preview with an empty caption box (documents), types the caption so it isn't lost. */
    private fun fillEmptyCaption(packageName: String, caption: String) {
        if (caption.isBlank()) return
        val box = screen.root()?.findId(id(packageName, "caption"))?.takeIf { it.isEditable || it.isShowingHint || it.text.isNullOrBlank() } ?: return
        if (box.text.isNullOrBlank() || box.isShowingHint) box.setText(caption)
    }

    private suspend fun typeAndSend(packageName: String, text: String): AutoSendResult {
        val entryId = id(packageName, "entry")
        val entry = screen.root()?.findId(entryId) ?: return AutoSendResult.NotReady("WhatsApp's message box wasn't found")
        if (entry.text?.trim() != text.trim()) {
            entry.setText(text)
            waitFor(stepTimeoutMs) { root -> root.findId(entryId)?.takeIf { it.text?.trim() == text.trim() } }
                ?: return AutoSendResult.NotReady("Couldn't place the message in WhatsApp's message box")
        }
        val send = waitFor(stepTimeoutMs) { it.findId(id(packageName, "send")) ?: it.findDescription("Send") }
            ?: return AutoSendResult.NotReady("WhatsApp's Send button wasn't found")
        if (!send.clickNearest()) return AutoSendResult.NotReady("WhatsApp's Send button couldn't be pressed")
        // Sent once the text has left the input box. Anything else is reported, never retried blindly.
        val cleared = waitFor(confirmTimeoutMs) { root -> if (root.findId(entryId)?.text?.trim() != text.trim()) true else null }
        return if (cleared == true) AutoSendResult.Sent
        else AutoSendResult.Unconfirmed("Send was pressed but the message stayed in the box")
    }

    private suspend fun <T : Any> waitFor(timeoutMs: Long, probe: (UiNode) -> T?): T? {
        var waited = 0L
        while (waited <= timeoutMs) {
            screen.root()?.let(probe)?.let { return it }
            delay(pollMs)
            waited += pollMs
        }
        return null
    }

    private suspend fun withLeave(block: suspend () -> AutoSendResult): AutoSendResult =
        try { block() } finally { screen.leave() }

    private enum class Opened { Chat, NotOnWhatsApp, Picker }

    companion object {
        fun id(packageName: String, name: String) = "$packageName:id/$name"
        private const val OPEN_ATTEMPTS = 2
        /** A file preview reached from the "Send to…" list (documents). */
        private val PREVIEW_IDS = listOf("media_recipients", "media_recipients_layout", "document_file_name")

        /** Views that exist only on WhatsApp's share screens (preview, caption, "Send to…" list). */
        private val SHARE_SCREEN_IDS = listOf(
            "media_recipients", "media_recipients_layout", "recipients", "appended_message", "contactpicker_row_name", "search_src_text",
            "document_file_name",
        )
        val NOT_ON_WHATSAPP_HINTS = listOf("isn't on WhatsApp", "is not on WhatsApp", "not on WhatsApp", "shared via url is invalid")
    }
}

fun UiNode.walk(): Sequence<UiNode> = sequence {
    yield(this@walk)
    children.forEach { yieldAll(it.walk()) }
}

fun UiNode.findId(viewId: String): UiNode? = walk().firstOrNull { it.viewId == viewId }
fun UiNode.findAllId(viewId: String): List<UiNode> = walk().filter { it.viewId == viewId }.toList()
/** Matches "Search" as well as decorated labels such as "Search…". */
fun UiNode.findDescription(description: String): UiNode? =
    walk().firstOrNull { it.description?.trim()?.startsWith(description, ignoreCase = true) == true }
fun UiNode.findText(fragments: List<String>): UiNode? =
    walk().firstOrNull { node -> fragments.any { node.text?.contains(it, ignoreCase = true) == true } }

/** Clicks this node if it's clickable, else its first clickable descendant, else its nearest clickable ancestor. */
fun UiNode.clickNearest(): Boolean {
    if (isClickable) return click()
    walk().drop(1).firstOrNull { it.isClickable }?.let { return it.click() }
    var node: UiNode? = parent
    while (node != null) {
        if (node.isClickable) return node.click()
        node = node.parent
    }
    return click()
}
