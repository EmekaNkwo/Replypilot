package com.replypilot.core.accessibility

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred

/** Outcome of an assisted send, where the user taps Send themselves in the messaging app. */
enum class AssistOutcome { SENT, TIMED_OUT, CANCELLED, UNAVAILABLE }

/** A user-started interaction with a messaging app that the accessibility service is helping with. */
sealed interface ChatSession {
    val app: ChatApp
    val startedAt: Long

    /**
     * "Pick a chat": collects the titles of the conversations the user opens. A single pick completes with the first;
     * a [multiple] pick keeps collecting until the user taps Done or returns to ReplyPilot.
     */
    class Pick(override val app: ChatApp, override val startedAt: Long, val multiple: Boolean = false) : ChatSession {
        val result = CompletableDeferred<List<String>>()
        val picked: MutableList<String> = CopyOnWriteArrayList()
        /** Read-only chats the user was already told can't be added (so the warning shows once). */
        val warned: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    }

    /** "Send manually" for a named chat: fills the message in once that chat is open, then watches for the send. */
    class Assist(override val app: ChatApp, override val startedAt: Long, val chatTitle: String, val text: String) : ChatSession {
        val result = CompletableDeferred<AssistOutcome>()
        @Volatile var filled = false
        /** How many times the message already appeared in the chat when it was filled in. */
        @Volatile var shownBefore = 0
        /** What the empty box showed before filling: some apps (Telegram) put a "Message" placeholder in as real text. */
        @Volatile var emptyText: String? = null
    }
}

/** Decides what a session should do with the conversation currently on screen. Pure, so it can be unit-tested. */
object ChatSessionLogic {
    sealed interface Step {
        data object Wait : Step
        data class PickDone(val title: String) : Step
        /** The user opened a chat nobody can send to from here (no message box); tell them why it isn't added. */
        data class CantAdd(val title: String) : Step
        data object Fill : Step
        data object Sent : Step
    }

    fun next(session: ChatSession, screen: ChatScreen?, now: Long, readOnlyTitle: String? = null): Step {
        if (now - session.startedAt < SETTLE_MS) return Step.Wait
        if (screen == null) {
            val pick = session as? ChatSession.Pick ?: return Step.Wait
            val title = readOnlyTitle ?: return Step.Wait
            return if (pick.picked.any { titlesMatch(it, title) } || pick.warned.any { titlesMatch(it, title) }) Step.Wait else Step.CantAdd(title)
        }
        return when (session) {
            // A chat already picked stays on screen for many events; only a new one counts.
            is ChatSession.Pick -> if (session.picked.any { titlesMatch(it, screen.title) }) Step.Wait else Step.PickDone(screen.title)
            is ChatSession.Assist -> when {
                !titlesMatch(screen.title, session.chatTitle) -> Step.Wait
                !session.filled -> Step.Fill
                // Sent only when the box emptied AND the message now shows once more in the chat: clearing the box
                // (or sending something else) must not count as sending this message.
                screen.input.isEmptyInput(session.emptyText) && timesShown(screen, session.text) > session.shownBefore -> Step.Sent
                else -> Step.Wait
            }
        }
    }

    fun titlesMatch(onScreen: String, wanted: String): Boolean = ChatNames.matches(onScreen, wanted)

    /** Message bubbles (not the input box) whose text contains the start of [text]. */
    fun timesShown(screen: ChatScreen, text: String): Int {
        val snippet = text.normalized().take(SNIPPET_CHARS)
        if (snippet.isEmpty()) return 0
        return screen.root.walk().count { node ->
            !node.isEditable && ((node.text ?: node.description)?.normalized()?.contains(snippet, ignoreCase = true) == true)
        }
    }

    private fun String.normalized() = trim().replace(WHITESPACE, " ")
    private val WHITESPACE = Regex("\\s+")
    private const val SNIPPET_CHARS = 40

    private fun UiNode.isEmptyInput(emptyText: String? = null): Boolean =
        text.isNullOrBlank() || isShowingHint || (emptyText != null && text?.trim() == emptyText.trim())

    /** Ignores the app's first frames, which may still show whatever chat was open before. */
    const val SETTLE_MS = 800L
}
