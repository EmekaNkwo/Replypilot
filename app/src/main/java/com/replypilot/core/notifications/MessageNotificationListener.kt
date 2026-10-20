package com.replypilot.core.notifications

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.replypilot.core.coroutines.ApplicationScope
import com.replypilot.core.datastore.AppPreferencesRepository
import com.replypilot.domain.automation.IncomingMessageHandler
import com.replypilot.domain.model.IncomingMessage
import com.replypilot.integrations.device.DeviceChannels
import com.replypilot.integrations.device.DeviceNotificationAdapter
import com.replypilot.domain.messaging.AdapterRegistry
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Turns notifications from enabled messaging apps into [IncomingMessage] events for the rule engine.
 * It does no matching itself: parsing here, decisions in the domain layer. Text is not persisted.
 */
@AndroidEntryPoint
class MessageNotificationListener : NotificationListenerService() {
    @Inject lateinit var handler: IncomingMessageHandler
    @Inject lateinit var preferences: AppPreferencesRepository
    @Inject lateinit var registry: AdapterRegistry
    @Inject lateinit var replies: ReplyActionRegistry
    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        scope.launch {
            try {
                process(sbn)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to process notification from ${sbn.packageName}", e)
            }
        }
    }

    private suspend fun process(sbn: StatusBarNotification) {
        val enabled = preferences.current().enabledChannels
        val spec = DeviceChannels.all.firstOrNull { spec ->
            spec.channel in enabled && (registry.get(spec.channel) as? DeviceNotificationAdapter)?.packages()?.contains(sbn.packageName) == true
        } ?: return
        val parsed = NotificationParser.parse(sbn.notification, sbn.postTime) ?: return

        val target = ReplyActionRegistry.target(sbn.packageName, parsed.conversation)
        val action = parsed.replyAction
        if (action != null && parsed.replyInputs.isNotEmpty()) {
            replies.register(target, action, parsed.replyInputs, System.currentTimeMillis())
        }
        handler.handle(
            IncomingMessage(
                id = "${sbn.key}:${parsed.messageTime}",
                channel = spec.channel,
                conversationKey = parsed.conversation,
                senderName = parsed.sender,
                text = parsed.text,
                isGroup = parsed.isGroup,
                receivedAt = parsed.messageTime,
                isFromSelf = parsed.isFromSelf,
                replyTarget = target.takeIf { action != null },
            ),
        )
    }

    private companion object {
        const val TAG = "ReplyPilotListener"
    }
}
