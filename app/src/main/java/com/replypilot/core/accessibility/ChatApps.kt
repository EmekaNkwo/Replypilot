package com.replypilot.core.accessibility

import com.replypilot.domain.model.ChannelId

/** Messaging apps whose chats ReplyPilot can pick from and assist with, via the user-enabled accessibility service. */
enum class ChatApp(val channel: ChannelId, val packages: List<String>, val autoSend: Boolean) {
    WHATSAPP(ChannelId.WHATSAPP, listOf("com.whatsapp", "com.whatsapp.w4b"), autoSend = true),
    TELEGRAM(ChannelId.TELEGRAM, listOf("org.telegram.messenger"), autoSend = false),
    INSTAGRAM(ChannelId.INSTAGRAM, listOf("com.instagram.android"), autoSend = false),
    MESSENGER(ChannelId.MESSENGER, listOf("com.facebook.orca"), autoSend = false),
    ;

    companion object {
        fun forChannel(channel: ChannelId): ChatApp? = entries.firstOrNull { it.channel == channel }
        fun forPackage(packageName: String?): ChatApp? = entries.firstOrNull { packageName in it.packages }
        val allPackages: Set<String> = entries.flatMap { it.packages }.toSet()
    }
}

/** An open conversation: its title as shown in the app, the message input box, and the whole screen. */
data class ChatScreen(val title: String, val input: UiNode, val root: UiNode)

/**
 * Recognises "a conversation is open" across apps. WhatsApp and Instagram have stable view IDs and Messenger a
 * labelled header; Telegram has neither, so the generic rule is: a text input in the lower part of the screen (the composer) plus a
 * title in the top bar. The user confirms the title before it is used, so a misread is caught, not acted on.
 */
object ChatScreenReader {
    /** Apps with stable view IDs: package prefix → (title id, message box id). These must not fall back to guessing. */
    private val KNOWN_IDS = mapOf(
        "com.whatsapp" to ("conversation_contact_name" to "entry"),
        "com.instagram.android" to ("header_title" to "row_thread_composer_edittext"),
    )
    private val IGNORED_TITLES = setOf("back", "search", "message", "messages", "chats", "more options", "go back")

    fun read(root: UiNode, packageName: String, screenHeight: Int): ChatScreen? {
        KNOWN_IDS.entries.firstOrNull { packageName.startsWith(it.key) }?.let { (_, ids) ->
            val titleNode = root.findId("$packageName:id/${ids.first}")
            val title = (titleNode?.text ?: titleNode?.description)?.trim()
            val input = root.findId("$packageName:id/${ids.second}")
            return if (!title.isNullOrEmpty() && input != null) ChatScreen(title, input, root) else null
        }
        val nodes = root.walk().toList()
        val input = nodes.lastOrNull { it.isEditable && (it.bounds?.top ?: 0) > screenHeight * INPUT_MIN_Y } ?: return null
        if (packageName == MESSENGER) {
            // Messenger has no view IDs, but its header button is labelled "<name>, Thread Details".
            val title = nodes.firstNotNullOfOrNull { it.description?.removeSuffix(MESSENGER_HEADER_SUFFIX)?.takeIf { d -> d != it.description } }
                ?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return ChatScreen(title, input, root)
        }
        val title = nodes.asSequence()
            .filter { it.isText && !it.isEditable }
            .mapNotNull { node -> node.bounds?.let { b -> node to b } }
            .filter { (_, b) -> b.bottom < screenHeight * TITLE_MAX_Y && b.left > TITLE_MIN_X }
            .mapNotNull { (node, b) -> node.text?.trim()?.takeIf { it.isNotEmpty() && it.lowercase() !in IGNORED_TITLES }?.let { it to b } }
            .sortedWith(compareBy({ it.second.top }, { it.second.left }))
            .firstOrNull()?.first
            ?: return null
        return ChatScreen(title, input, root)
    }

    /**
     * The title of a conversation that is open but can't be written to (no message box): admin-only groups, channels,
     * groups you've left. Only for apps with reliable IDs; elsewhere such screens just aren't recognised.
     */
    fun readOnlyTitle(root: UiNode, packageName: String): String? {
        val ids = KNOWN_IDS.entries.firstOrNull { packageName.startsWith(it.key) }?.value ?: return null
        if (root.findId("$packageName:id/${ids.second}") != null) return null
        val titleNode = root.findId("$packageName:id/${ids.first}") ?: return null
        return (titleNode.text ?: titleNode.description)?.trim()?.takeIf { it.isNotEmpty() }
    }

    private const val MESSENGER = "com.facebook.orca"
    private const val MESSENGER_HEADER_SUFFIX = ", Thread Details"
    private const val INPUT_MIN_Y = 0.55
    private const val TITLE_MAX_Y = 0.14
    private const val TITLE_MIN_X = 90
}
