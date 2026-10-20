package com.replypilot.core.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.webkit.MimeTypeMap
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.replypilot.BuildConfig
import com.replypilot.core.attachments.AttachmentStore
import com.replypilot.core.attachments.ShareIntents
import com.replypilot.core.scheduling.AndroidWakeScheduler
import com.replypilot.domain.model.Attachment
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * User-enabled accessibility service for WhatsApp, Telegram, Instagram and Messenger. It never acts on its own:
 * it sends a scheduled WhatsApp message when the queue asks, reads a chat's title when the user picks a chat,
 * and fills in a message for a chat the user opens during an assisted send.
 */
@AndroidEntryPoint
class AutoSendService : AccessibilityService() {
    @Inject lateinit var bridge: AutoSendBridge
    @Inject lateinit var wake: AndroidWakeScheduler
    @Inject lateinit var attachments: AttachmentStore

    /** Sends that waited for an unlock run as soon as the user unlocks the phone. */
    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = wake.runNow()
    }

    /** Debug builds only: logs the structure (never the text) of the current screen, for supporting new app versions. */
    private val debugDumpReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // "share": comma-separated paths of ReplyPilot's own attachment copies, opened in WhatsApp's share flow
            // (nothing is pressed), to study new WhatsApp share screens.
            intent.getStringExtra("share")?.let { paths ->
                val files = paths.split(',').map { path ->
                    val ext = path.substringAfterLast('.', "").lowercase()
                    val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
                    Attachment(path, path.substringAfterLast('/'), mime, 0, path)
                }
                screen(returnToApp = false).open(OpenTarget.Share("com.whatsapp.w4b", intent.getStringExtra("jid"), intent.getStringExtra("caption").orEmpty(), files))
                return
            }
            val root = rootInActiveWindow
            if (root == null) Log.i(DEBUG_TAG, "no window") else dumpStructure(root, 0)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        bridge.attach(this)
        ContextCompat.registerReceiver(this, unlockReceiver, IntentFilter(Intent.ACTION_USER_PRESENT), ContextCompat.RECEIVER_NOT_EXPORTED)
        if (BuildConfig.DEBUG) {
            ContextCompat.registerReceiver(this, debugDumpReceiver, IntentFilter(DEBUG_DUMP_ACTION), ContextCompat.RECEIVER_EXPORTED)
        }
        wake.runNow()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val packageName = event?.packageName?.toString() ?: return
        if (ChatApp.forPackage(packageName) == null) return
        bridge.onScreenChanged(this, packageName)
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        hidePickOverlay()
        bridge.detach(this)
        runCatching { unregisterReceiver(unlockReceiver) }
        if (BuildConfig.DEBUG) runCatching { unregisterReceiver(debugDumpReceiver) }
        super.onDestroy()
    }

    internal fun currentChat(packageName: String): ChatScreen? {
        val root = rootInActiveWindow ?: return null
        if (root.packageName?.toString() != packageName) return null
        return ChatScreenReader.read(NodeAdapter(root), packageName, resources.displayMetrics.heightPixels)
    }

    internal fun readOnlyChatTitle(packageName: String): String? {
        val root = rootInActiveWindow ?: return null
        if (root.packageName?.toString() != packageName) return null
        return ChatScreenReader.readOnlyTitle(NodeAdapter(root), packageName)
    }

    internal fun openApp(app: ChatApp, freshStart: Boolean): Boolean {
        val packageName = app.packages.firstOrNull { packageManager.getLaunchIntentForPackage(it) != null } ?: return false
        val intent = packageManager.getLaunchIntentForPackage(packageName)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) ?: return false
        if (freshStart) intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        return runCatching { startActivity(intent) }.isSuccess
    }

    /**
     * Brings back the ReplyPilot screen the user left (e.g. the composer), not a new one: a launch intent only reuses the
     * open screen when it matches how the app was first opened, so after a notification or deep link it would start
     * over on Home.
     */
    internal fun returnToReplyPilot() {
        val task = getSystemService(ActivityManager::class.java)?.appTasks?.firstOrNull()
        if (task != null && runCatching { task.moveToFront() }.isSuccess) return
        val intent = packageManager.getLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        if (intent == null || runCatching { startActivity(intent) }.isFailure) performGlobalAction(GLOBAL_ACTION_HOME)
    }

    private var pickOverlay: TextView? = null

    /**
     * A small "Done" button floating over the messaging app during a multiple chat pick. It's an accessibility overlay
     * (no extra permission), never takes focus, and is removed as soon as the pick ends.
     */
    internal fun showPickOverlay(label: String, onDone: () -> Unit) {
        hidePickOverlay()
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val button = TextView(this).apply {
            text = label
            contentDescription = label
            setTextColor(Color.WHITE)
            textSize = OVERLAY_TEXT_SP
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(22), dp(12), dp(22), dp(12))
            background = GradientDrawable().apply { cornerRadius = dp(28).toFloat(); setColor(OVERLAY_COLOR) }
            elevation = dp(6).toFloat()
            setOnClickListener { onDone() }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            // Right edge, a fifth of the way down: clear of the keyboard, the message box and the app's top bar.
            gravity = Gravity.TOP or Gravity.END
            x = dp(OVERLAY_EDGE_DP)
            y = (resources.displayMetrics.heightPixels * OVERLAY_TOP_FRACTION).toInt()
        }
        runCatching { getSystemService(WindowManager::class.java).addView(button, params) }
            .onSuccess { pickOverlay = button }
            .onFailure { Log.w(TAG, "Couldn't show the Done button", it) }
    }

    internal fun updatePickOverlay(label: String) {
        pickOverlay?.apply { text = label; contentDescription = label }
    }

    internal fun hidePickOverlay() {
        val view = pickOverlay ?: return
        pickOverlay = null
        runCatching { getSystemService(WindowManager::class.java).removeView(view) }
    }

    internal fun hint(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    /** [returnToApp]: the send was started while ReplyPilot was open, so bring the user back to it afterwards. */
    internal fun screen(returnToApp: Boolean): UiScreen = object : UiScreen {
        override fun root(): UiNode? = rootInActiveWindow?.let(::NodeAdapter)

        override fun open(target: OpenTarget): Boolean {
            val intent = when (target) {
                is OpenTarget.Chat -> Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/${target.phoneDigits}?text=${Uri.encode(target.prefill)}"))
                    .setPackage(target.packageName)
                // Clear WhatsApp's task so it opens on the chat list (with search), not on whatever chat was last open.
                is OpenTarget.App -> (packageManager.getLaunchIntentForPackage(target.packageName) ?: return false)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
                is OpenTarget.Share -> {
                    val uris = target.attachments.map { attachments.uriFor(it) ?: return false }
                    ShareIntents.build(
                        uris, target.attachments.map { it.mimeType }, target.caption,
                        ShareIntents.Target(packageName = target.packageName, whatsAppJid = target.jid),
                    ).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
            }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            return runCatching { startActivity(intent) }.isSuccess
        }

        override fun tap(bounds: NodeBounds): Boolean {
            val path = Path().apply { moveTo((bounds.left + bounds.right) / 2f, (bounds.top + bounds.bottom) / 2f) }
            return dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, TAP_MS)).build(), null, null)
        }

        /** Back to ReplyPilot if the user started the send from it; otherwise to the home screen, never left in WhatsApp. */
        override fun leave() {
            if (returnToApp) returnToReplyPilot() else performGlobalAction(GLOBAL_ACTION_HOME)
        }
    }

    private fun dumpStructure(node: AccessibilityNodeInfo, depth: Int) {
        val rect = Rect().also(node::getBoundsInScreen)
        Log.i(
            DEBUG_TAG,
            "${"  ".repeat(depth)}${node.className?.toString()?.substringAfterLast('.')} id=${node.viewIdResourceName?.substringAfter('/')} " +
                "edit=${node.isEditable} click=${node.isClickable} textLen=${node.text?.length ?: 0} descLen=${node.contentDescription?.length ?: 0} $rect",
        )
        for (i in 0 until node.childCount) node.getChild(i)?.let { dumpStructure(it, depth + 1) }
    }

    private class NodeAdapter(private val node: AccessibilityNodeInfo) : UiNode {
        override val viewId: String? get() = node.viewIdResourceName
        override val text: String? get() = node.text?.toString()
        override val description: String? get() = node.contentDescription?.toString()
        override val isClickable: Boolean get() = node.isClickable
        override val isCheckable: Boolean get() = node.isCheckable
        override val isEditable: Boolean get() = node.isEditable
        override val isShowingHint: Boolean get() = node.isShowingHintText
        override val isText: Boolean get() = node.className?.toString()?.endsWith("TextView") == true
        override val bounds: NodeBounds get() = Rect().also(node::getBoundsInScreen).let { NodeBounds(it.left, it.top, it.right, it.bottom) }
        override val parent: UiNode? get() = node.parent?.let(::NodeAdapter)
        override val children: List<UiNode> get() = (0 until node.childCount).mapNotNull { node.getChild(it)?.let(::NodeAdapter) }
        override fun click(): Boolean = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        override fun setText(value: String): Boolean = node.performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            android.os.Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) },
        )
    }

    private companion object {
        const val DEBUG_TAG = "ReplyPilotScreen"
        const val DEBUG_DUMP_ACTION = "com.replypilot.DEBUG_DUMP_SCREEN"
        const val TAG = "ReplyPilotAutoSend"
        const val OVERLAY_TEXT_SP = 16f
        const val TAP_MS = 60L
        const val OVERLAY_EDGE_DP = 12
        const val OVERLAY_TOP_FRACTION = 0.2f
        val OVERLAY_COLOR = 0xFF1B6B50.toInt()
    }
}
