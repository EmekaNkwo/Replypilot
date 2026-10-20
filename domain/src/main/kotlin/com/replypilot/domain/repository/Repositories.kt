package com.replypilot.domain.repository

import com.replypilot.domain.model.ActivityEvent
import com.replypilot.domain.model.AutoReplyRule
import com.replypilot.domain.model.AutomationSettings
import com.replypilot.domain.model.ChannelId
import com.replypilot.domain.model.Contact
import com.replypilot.domain.model.ConversationState
import com.replypilot.domain.model.EnrollmentStatus
import com.replypilot.domain.model.ExecutionItem
import com.replypilot.domain.model.ExecutionSource
import com.replypilot.domain.model.MessageSequence
import com.replypilot.domain.model.MessageStatus
import com.replypilot.domain.model.MessageTemplate
import com.replypilot.domain.model.ReplyRecord
import com.replypilot.domain.model.ScheduledMessage
import com.replypilot.domain.model.SequenceEnrollment
import kotlinx.coroutines.flow.Flow

interface ScheduleRepository {
    fun observeAll(): Flow<List<ScheduledMessage>>
    suspend fun all(): List<ScheduledMessage>
    suspend fun get(id: String): ScheduledMessage?
    suspend fun upsert(message: ScheduledMessage)
    suspend fun delete(id: String)
}

interface ExecutionRepository {
    fun observeAll(): Flow<List<ExecutionItem>>
    fun observeBySource(sourceId: String): Flow<List<ExecutionItem>>
    suspend fun get(id: String): ExecutionItem?
    /** Inserts items whose idempotency key is new; returns how many were inserted. */
    suspend fun insertIfAbsent(items: List<ExecutionItem>): Int
    suspend fun update(item: ExecutionItem)
    /**
     * Atomically moves [id] from [expected] to [to]. Returns false when the row is no longer in [expected]
     * (for example, another worker already claimed it). This is the guard against double execution.
     */
    suspend fun compareAndSetStatus(id: String, expected: MessageStatus, to: MessageStatus, at: Long): Boolean
    /**
     * Atomically replaces the whole row with [item] only if its stored status is still [expected].
     * Used wherever a status change and other fields must be written together without racing the queue.
     */
    suspend fun replaceIfStatus(item: ExecutionItem, expected: MessageStatus): Boolean
    suspend fun due(now: Long, statuses: Set<MessageStatus>, limit: Int): List<ExecutionItem>
    suspend fun withStatus(statuses: Set<MessageStatus>): List<ExecutionItem>
    suspend fun bySource(source: ExecutionSource, sourceId: String): List<ExecutionItem>
    suspend fun countSubmittedSince(since: Long, channel: ChannelId? = null): Int
    suspend fun nextWakeAt(statuses: Set<MessageStatus>): Long?
    /** Removes rows in [statuses] from the queue; their activity history is kept. Returns how many were removed. */
    suspend fun deleteWithStatus(statuses: Set<MessageStatus>): Int
    suspend fun deleteAll()
}

interface RuleRepository {
    fun observeAll(): Flow<List<AutoReplyRule>>
    suspend fun all(): List<AutoReplyRule>
    suspend fun get(id: String): AutoReplyRule?
    suspend fun upsert(rule: AutoReplyRule)
    suspend fun delete(id: String)
}

interface TemplateRepository {
    fun observeAll(): Flow<List<MessageTemplate>>
    suspend fun all(): List<MessageTemplate>
    suspend fun get(id: String): MessageTemplate?
    suspend fun upsert(template: MessageTemplate)
    suspend fun delete(id: String)
}

interface ContactRepository {
    fun observeAll(): Flow<List<Contact>>
    suspend fun all(): List<Contact>
    suspend fun get(id: String): Contact?
    suspend fun findByAddress(channel: ChannelId, address: String): Contact?
    suspend fun findByName(name: String): Contact?
    suspend fun upsert(contact: Contact)
    suspend fun upsertAll(contacts: List<Contact>)
    suspend fun delete(id: String)
}

interface SequenceRepository {
    fun observeSequences(): Flow<List<MessageSequence>>
    fun observeEnrollments(): Flow<List<SequenceEnrollment>>
    suspend fun allSequences(): List<MessageSequence>
    suspend fun getSequence(id: String): MessageSequence?
    suspend fun upsertSequence(sequence: MessageSequence)
    suspend fun deleteSequence(id: String)
    suspend fun getEnrollment(id: String): SequenceEnrollment?
    suspend fun enrollments(status: EnrollmentStatus? = null): List<SequenceEnrollment>
    suspend fun upsertEnrollment(enrollment: SequenceEnrollment)
}

interface ActivityRepository {
    fun observeRecent(limit: Int): Flow<List<ActivityEvent>>
    suspend fun append(event: ActivityEvent)
    suspend fun since(at: Long): List<ActivityEvent>
    suspend fun prune(olderThan: Long)
    suspend fun deleteAll()
}

/** Short-lived automation memory: reply records, seen-message fingerprints and takeover state. */
interface ConversationRepository {
    suspend fun repliesSince(since: Long): List<ReplyRecord>
    suspend fun recordReply(record: ReplyRecord)
    suspend fun hasFingerprint(fingerprint: String, since: Long): Boolean
    suspend fun recordFingerprint(fingerprint: String, at: Long)
    suspend fun state(channel: ChannelId, conversationKey: String): ConversationState?
    suspend fun setState(state: ConversationState)
    fun observeTakeovers(): Flow<List<ConversationState>>
    suspend fun prune(olderThan: Long)
    suspend fun deleteAll()
}

interface SettingsRepository {
    val settings: Flow<AutomationSettings>
    suspend fun current(): AutomationSettings
    suspend fun update(transform: (AutomationSettings) -> AutomationSettings)
}

/** Asks the platform to wake the queue no later than [at]. Implemented with WorkManager/AlarmManager. */
fun interface WakeScheduler {
    fun scheduleWake(at: Long?)
}
