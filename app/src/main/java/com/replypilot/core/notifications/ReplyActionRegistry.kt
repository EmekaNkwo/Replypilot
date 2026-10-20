package com.replypilot.core.notifications

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds the most recent notification "Reply" action per conversation, in memory only.
 * A reply target is "package|conversation"; it stays valid only while the app keeps that notification alive.
 */
@Singleton
class ReplyActionRegistry @Inject constructor(@ApplicationContext private val context: Context) {
    private data class Entry(val action: Notification.Action, val inputs: Array<RemoteInput>, val at: Long)

    private val entries = ConcurrentHashMap<String, Entry>()

    sealed interface ReplyResult {
        data object Sent : ReplyResult
        data object NoTarget : ReplyResult
        data class Failed(val reason: String) : ReplyResult
    }

    fun register(target: String, action: Notification.Action, inputs: Array<RemoteInput>, now: Long) {
        prune(now)
        entries[target] = Entry(action, inputs, now)
    }

    fun has(target: String): Boolean = entries.containsKey(target)

    fun remove(target: String) {
        entries.remove(target)
    }

    fun reply(target: String, text: String): ReplyResult {
        val entry = entries[target] ?: return ReplyResult.NoTarget
        val intent = Intent()
        val results = Bundle().apply { entry.inputs.forEach { putCharSequence(it.resultKey, text) } }
        RemoteInput.addResultsToIntent(entry.inputs, intent, results)
        return try {
            entry.action.actionIntent.send(context, 0, intent)
            ReplyResult.Sent
        } catch (e: PendingIntent.CanceledException) {
            entries.remove(target)
            ReplyResult.NoTarget
        } catch (e: SecurityException) {
            ReplyResult.Failed(e.message ?: "Reply was blocked by the system")
        }
    }

    private fun prune(now: Long) {
        entries.entries.removeIf { now - it.value.at > MAX_AGE_MS }
    }

    companion object {
        fun target(packageName: String, conversationKey: String) = "$packageName|$conversationKey"
        private const val MAX_AGE_MS = 6 * 60 * 60 * 1000L
    }
}
