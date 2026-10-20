package com.replypilot.core.accessibility

import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Context
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import com.replypilot.BuildConfig
import com.replypilot.core.coroutines.ApplicationScope
import com.replypilot.core.permissions.PlatformStatus
import com.replypilot.domain.model.Attachment
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

sealed interface AutoSendTarget {
    data class Person(val phoneDigits: String) : AutoSendTarget
    data class Group(val name: String) : AutoSendTarget
}

/** Connects channel adapters to the running accessibility service, one send at a time. */
@Singleton
class AutoSendBridge @Inject constructor(
    @ApplicationContext private val context: Context,
    private val platform: PlatformStatus,
    private val foreground: AppForeground,
    @ApplicationScope private val appScope: CoroutineScope,
) {
    /** A finished chat pick, kept until the screen that asked for it collects it (even if that screen was rebuilt). */
    data class PickResult(val owner: String, val app: ChatApp, val titles: List<String>)

    private val _pickResult = MutableStateFlow<PickResult?>(null)
    val pickResult: StateFlow<PickResult?> = _pickResult.asStateFlow()

    /**
     * Starts a chat pick that outlives the screen asking for it: Android may destroy ReplyPilot's screen while the user
     * is in the other app. The result is published to [pickResult] for [owner]. False if a pick can't start now.
     */
    fun startPick(app: ChatApp, owner: String): Boolean {
        if (service == null || session != null || mutex.isLocked) return false
        appScope.launch { _pickResult.value = PickResult(owner, app, pickChats(app)) }
        return true
    }

    /** Takes the waiting result for [owner], if any. */
    fun consumePickResult(owner: String): PickResult? {
        val result = _pickResult.value?.takeIf { it.owner == owner } ?: return null
        return result.takeIf { _pickResult.compareAndSet(it, null) }
    }

    init {
        // Coming back to ReplyPilot by hand ends a pick that's still waiting (a completed pick has already cleared it).
        foreground.onReturn = { cancelSession() }
    }

    @Volatile
    private var service: AutoSendService? = null
    private val mutex = Mutex()

    /** The pick or assisted send in progress, if any. Only one at a time. */
    @Volatile
    private var session: ChatSession? = null

    val isRunning: Boolean get() = service != null

    internal fun attach(service: AutoSendService) {
        this.service = service
        // Android can restart the service mid-pick (taking its window with it): bring the Done button back.
        (session as? ChatSession.Pick)?.takeIf { it.multiple && !it.result.isCompleted }?.let { pick ->
            service.showPickOverlay(doneLabel(pick.picked.size)) { finishPick(pick) }
        }
    }
    internal fun detach(service: AutoSendService) { if (this.service === service) this.service = null }

    /** True when the user has switched the service on in Accessibility settings. */
    fun isEnabled(): Boolean {
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        val component = ComponentName(context, AutoSendService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == component }
    }

    suspend fun send(packageName: String, target: AutoSendTarget, text: String): AutoSendResult = drive(target) { automator ->
        when (target) {
            is AutoSendTarget.Person -> automator.sendToNumber(packageName, target.phoneDigits, text)
            is AutoSendTarget.Group -> automator.sendToGroup(packageName, target.name, text)
        }
    }

    /** Sends [attachments] with [caption] through WhatsApp's share screens, pressing Send for the user. */
    suspend fun sendFiles(packageName: String, target: AutoSendTarget, caption: String, attachments: List<Attachment>): AutoSendResult =
        drive(target) { automator ->
            when (target) {
                is AutoSendTarget.Person -> automator.sendFilesToNumber(packageName, target.phoneDigits, caption, attachments)
                is AutoSendTarget.Group -> automator.sendFilesToChat(packageName, target.name, caption, attachments)
            }
        }

    /** One unattended send at a time, only on an unlocked phone and where the phone lets ReplyPilot open WhatsApp. */
    private suspend fun drive(target: AutoSendTarget, block: suspend (WhatsAppAutomator) -> AutoSendResult): AutoSendResult = mutex.withLock {
        val running = service ?: return@withLock if (isEnabled()) AutoSendResult.NotReady("Auto-send is starting; try again shortly") else AutoSendResult.Disabled
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        if (keyguard?.isKeyguardLocked == true) return@withLock AutoSendResult.Locked
        wakeScreen()
        val startedFromApp = foreground.isForeground
        if (platform.xiaomiBackgroundStartAllowed() == false && !startedFromApp) {
            return@withLock AutoSendResult.BlockedByPhone
        }
        val trace: (String) -> Unit = { if (BuildConfig.DEBUG) Log.d(TAG, it) }
        withContext(Dispatchers.Main) { block(WhatsAppAutomator(running.screen(returnToApp = startedFromApp), trace = trace)) }
            .also { Log.i(TAG, "Auto-send to ${target::class.simpleName}: ${it::class.simpleName}") }
    }

    /**
     * Opens [app] so the user can open a conversation; returns its title, or null if they didn't within the time limit
     * or came back on their own. ReplyPilot is brought back to the front as soon as a chat is opened.
     */
    /**
     * Opens [app] so the user can open one chat after another; each is collected. A floating "Done" button over the app
     * finishes the pick and brings them back (as does returning to ReplyPilot). Returns the titles in the order opened.
     */
    suspend fun pickChats(app: ChatApp, multiple: Boolean = true): List<String> {
        val running = service ?: return emptyList()
        // One interaction at a time, and never while an unattended send is driving WhatsApp.
        if (session != null || mutex.isLocked) return emptyList()
        val pick = ChatSession.Pick(app, System.currentTimeMillis(), multiple)
        session = pick
        return try {
            withContext(Dispatchers.Main) {
                if (!running.openApp(app, freshStart = true)) return@withContext emptyList()
                if (multiple) {
                    running.showPickOverlay(doneLabel(0)) { finishPick(pick) }
                    running.hint("Open each chat you want. Tap Done to go back to ReplyPilot.")
                } else {
                    running.hint("Open the chat you want to message. ReplyPilot will bring you back.")
                }
                withTimeoutOrNull(if (multiple) MULTI_PICK_TIMEOUT_MS else PICK_TIMEOUT_MS) { pick.result.await() } ?: pick.picked.toList()
            }
        } finally {
            // The live instance: Android may have restarted the service (and re-shown the button) during the pick.
            withContext(NonCancellable + Dispatchers.Main) { (service ?: running).hidePickOverlay() }
            if (session === pick) session = null
        }
    }

    private fun finishPick(pick: ChatSession.Pick) {
        // Done may be tapped the instant a chat opens, before its screen events arrive: read the screen once more.
        service?.let { live ->
            pick.app.packages.firstNotNullOfOrNull(live::currentChat)?.title
                .also { if (BuildConfig.DEBUG) Log.d(TAG, "pick done: re-read title=${it?.length}") }
                ?.takeIf { title -> pick.picked.none { ChatSessionLogic.titlesMatch(it, title) } }
                ?.let { pick.picked += it }
        }
        pick.result.complete(pick.picked.toList())
        if (session === pick) session = null
        service?.hidePickOverlay()
        service?.returnToReplyPilot()
    }

    private fun doneLabel(count: Int) = if (count == 0) "Cancel" else "Done · $count chosen"

    /**
     * Assisted send for apps ReplyPilot can't drive end to end: opens the app, fills in [text] once the user opens the
     * chat titled [chatTitle], and reports SENT when the message leaves the input box after the user taps Send.
     */
    suspend fun assistSend(app: ChatApp, chatTitle: String, text: String): AssistOutcome {
        val running = service ?: return AssistOutcome.UNAVAILABLE
        if (session != null || mutex.isLocked) return AssistOutcome.UNAVAILABLE
        val assist = ChatSession.Assist(app, System.currentTimeMillis(), chatTitle, text)
        session = assist
        return try {
            withContext(Dispatchers.Main) {
                if (!running.openApp(app, freshStart = false)) return@withContext AssistOutcome.UNAVAILABLE
                running.hint("Open your chat with $chatTitle. ReplyPilot will fill in the message — then tap Send.")
                withTimeoutOrNull(ASSIST_TIMEOUT_MS) { assist.result.await() } ?: AssistOutcome.TIMED_OUT
            }
        } finally {
            if (session === assist) session = null
        }
    }

    /** The user came back to ReplyPilot without finishing: stop waiting. */
    fun cancelSession() {
        when (val current = session) {
            // Chats already chosen in a multiple pick are kept; coming back is how many people finish.
            is ChatSession.Pick -> current.result.complete(current.picked.toList())
            is ChatSession.Assist -> current.result.complete(AssistOutcome.CANCELLED)
            null -> Unit
        }
        session = null
    }

    internal fun onScreenChanged(service: AutoSendService, packageName: String) {
        val current = session ?: return
        if (packageName !in current.app.packages) return
        val screen = service.currentChat(packageName)
        val readOnly = if (screen == null && current is ChatSession.Pick) service.readOnlyChatTitle(packageName) else null
        val step = ChatSessionLogic.next(current, screen, System.currentTimeMillis(), readOnly)
        when (step) {
            is ChatSessionLogic.Step.CantAdd -> (current as? ChatSession.Pick)?.let { pick ->
                pick.warned += step.title
                if (BuildConfig.DEBUG) Log.d(TAG, "pick: read-only chat skipped")
                service.hint("“${step.title}” can't be added: you can't send messages in that chat.")
            }
            ChatSessionLogic.Step.Wait -> Unit
            is ChatSessionLogic.Step.PickDone -> (current as? ChatSession.Pick)?.let { pick ->
                pick.picked += step.title
                if (BuildConfig.DEBUG) Log.d(TAG, "pick: chat ${pick.picked.size} added")
                if (pick.multiple) {
                    service.updatePickOverlay(doneLabel(pick.picked.size))
                    service.hint("Added “${step.title}”. Go back and open another chat, or tap Done.")
                } else {
                    finishPick(pick)
                }
            }
            ChatSessionLogic.Step.Fill -> (current as? ChatSession.Assist)?.let { assist ->
                service.currentChat(packageName)?.let { chat ->
                    assist.shownBefore = ChatSessionLogic.timesShown(chat, assist.text)
                    assist.emptyText = chat.input.text?.takeIf { it.isNotBlank() && it.trim() != assist.text.trim() }
                    chat.input.setText(assist.text)
                }
                assist.filled = true
            }
            ChatSessionLogic.Step.Sent -> {
                (current as? ChatSession.Assist)?.result?.complete(AssistOutcome.SENT)
                session = null
                service.returnToReplyPilot()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun wakeScreen() {
        val power = context.getSystemService(PowerManager::class.java) ?: return
        if (power.isInteractive) return
        // Briefly turns the screen on so WhatsApp's UI can render; released automatically.
        power.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "ReplyPilot:autosend")
            .acquire(WAKE_MS)
    }

    private companion object {
        const val TAG = "ReplyPilotAutoSend"
        const val WAKE_MS = 30_000L
        const val PICK_TIMEOUT_MS = 3 * 60_000L
        const val MULTI_PICK_TIMEOUT_MS = 10 * 60_000L
        const val ASSIST_TIMEOUT_MS = 10 * 60_000L
    }
}
