package com.replypilot.domain.simulation

import com.replypilot.domain.common.Clock
import com.replypilot.domain.common.IdGenerator
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
import com.replypilot.domain.repository.ActivityRepository
import com.replypilot.domain.repository.ContactRepository
import com.replypilot.domain.repository.ConversationRepository
import com.replypilot.domain.repository.ExecutionRepository
import com.replypilot.domain.repository.RuleRepository
import com.replypilot.domain.repository.ScheduleRepository
import com.replypilot.domain.repository.SequenceRepository
import com.replypilot.domain.repository.SettingsRepository
import com.replypilot.domain.repository.TemplateRepository
import com.replypilot.domain.repository.WakeScheduler
import com.replypilot.domain.scheduling.AddressRules
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/*
 * In-memory implementations of the repository ports. Used by the queue simulator and by unit tests,
 * so simulations exercise exactly the same domain code as production without touching real data.
 */

class MutableClock(var nowMs: Long, var zoneId: ZoneId = ZoneOffset.UTC) : Clock {
    override fun now(): Long = nowMs
    override fun zone(): ZoneId = zoneId
    fun advance(ms: Long) { nowMs += ms }
}

class SequentialIds(private val prefix: String = "id") : IdGenerator {
    private var counter = 0
    override fun next(): String = "$prefix-${++counter}"
}

class RecordingWakeScheduler : WakeScheduler {
    val requests = mutableListOf<Long?>()
    override fun scheduleWake(at: Long?) { requests += at }
}

private class Store<T>(private val key: (T) -> String) {
    val state = MutableStateFlow<Map<String, T>>(emptyMap())
    val values: List<T> get() = state.value.values.toList()
    fun get(id: String): T? = state.value[id]
    fun put(item: T) = state.update { it + (key(item) to item) }
    fun remove(id: String) = state.update { it - id }
    fun clear() { state.value = emptyMap() }
    fun observe(): Flow<List<T>> = state.map { it.values.toList() }
}

class InMemoryScheduleRepository : ScheduleRepository {
    private val store = Store<ScheduledMessage> { it.id }
    override fun observeAll() = store.observe()
    override suspend fun all() = store.values
    override suspend fun get(id: String) = store.get(id)
    override suspend fun upsert(message: ScheduledMessage) = store.put(message)
    override suspend fun delete(id: String) = store.remove(id)
}

class InMemoryExecutionRepository : ExecutionRepository {
    private val store = Store<ExecutionItem> { it.id }
    val items: List<ExecutionItem> get() = store.values
    private val lock = Any()

    override fun observeAll() = store.observe()
    override fun observeBySource(sourceId: String) = store.observe().map { list -> list.filter { it.sourceId == sourceId } }
    override suspend fun get(id: String) = store.get(id)

    override suspend fun insertIfAbsent(items: List<ExecutionItem>): Int = synchronized(lock) {
        val existingKeys = store.values.map { it.idempotencyKey }.toMutableSet()
        var inserted = 0
        items.forEach { item ->
            if (existingKeys.add(item.idempotencyKey)) {
                store.put(item)
                inserted++
            }
        }
        inserted
    }

    override suspend fun update(item: ExecutionItem) = store.put(item)

    override suspend fun compareAndSetStatus(id: String, expected: MessageStatus, to: MessageStatus, at: Long): Boolean =
        synchronized(lock) {
            val current = store.get(id)
            if (current == null || current.status != expected) {
                false
            } else {
                store.put(current.copy(status = to, updatedAt = at, lastAttemptAt = if (to == MessageStatus.EXECUTING) at else current.lastAttemptAt))
                true
            }
        }

    override suspend fun replaceIfStatus(item: ExecutionItem, expected: MessageStatus): Boolean = synchronized(lock) {
        if (store.get(item.id)?.status != expected) {
            false
        } else {
            store.put(item)
            true
        }
    }

    override suspend fun due(now: Long, statuses: Set<MessageStatus>, limit: Int) =
        store.values.filter { it.status in statuses && it.nextAttemptAt <= now }.sortedBy { it.nextAttemptAt }.take(limit)

    override suspend fun withStatus(statuses: Set<MessageStatus>) = store.values.filter { it.status in statuses }
    override suspend fun bySource(source: ExecutionSource, sourceId: String) =
        store.values.filter { it.source == source && it.sourceId == sourceId }

    override suspend fun countSubmittedSince(since: Long, channel: ChannelId?) = store.values.count {
        it.status in MessageStatus.SUCCESS && (it.lastAttemptAt ?: 0) >= since && (channel == null || it.channel == channel)
    }

    override suspend fun nextWakeAt(statuses: Set<MessageStatus>) =
        store.values.filter { it.status in statuses }.minOfOrNull { it.nextAttemptAt }

    override suspend fun deleteWithStatus(statuses: Set<MessageStatus>): Int = synchronized(lock) {
        val doomed = store.values.filter { it.status in statuses }
        doomed.forEach { store.remove(it.id) }
        doomed.size
    }

    override suspend fun deleteAll() = store.clear()
}

class InMemoryRuleRepository : RuleRepository {
    private val store = Store<AutoReplyRule> { it.id }
    override fun observeAll() = store.observe()
    override suspend fun all() = store.values
    override suspend fun get(id: String) = store.get(id)
    override suspend fun upsert(rule: AutoReplyRule) = store.put(rule)
    override suspend fun delete(id: String) = store.remove(id)
}

class InMemoryTemplateRepository : TemplateRepository {
    private val store = Store<MessageTemplate> { it.id }
    override fun observeAll() = store.observe()
    override suspend fun all() = store.values
    override suspend fun get(id: String) = store.get(id)
    override suspend fun upsert(template: MessageTemplate) = store.put(template)
    override suspend fun delete(id: String) = store.remove(id)
}

class InMemoryContactRepository : ContactRepository {
    private val store = Store<Contact> { it.id }
    override fun observeAll() = store.observe()
    override suspend fun all() = store.values
    override suspend fun get(id: String) = store.get(id)
    override suspend fun findByAddress(channel: ChannelId, address: String): Contact? {
        val wanted = AddressRules.normalize(channel, address)
        return store.values.firstOrNull { c -> c.identities.any { it.channel == channel && AddressRules.normalize(channel, it.address) == wanted } }
    }
    override suspend fun findByName(name: String) =
        store.values.firstOrNull { it.displayName.equals(name.trim(), ignoreCase = true) }
    override suspend fun upsert(contact: Contact) = store.put(contact)
    override suspend fun upsertAll(contacts: List<Contact>) = contacts.forEach(store::put)
    override suspend fun delete(id: String) = store.remove(id)
}

class InMemorySequenceRepository : SequenceRepository {
    private val sequences = Store<MessageSequence> { it.id }
    private val enrollments = Store<SequenceEnrollment> { it.id }
    override fun observeSequences() = sequences.observe()
    override fun observeEnrollments() = enrollments.observe()
    override suspend fun allSequences() = sequences.values
    override suspend fun getSequence(id: String) = sequences.get(id)
    override suspend fun upsertSequence(sequence: MessageSequence) = sequences.put(sequence)
    override suspend fun deleteSequence(id: String) = sequences.remove(id)
    override suspend fun getEnrollment(id: String) = enrollments.get(id)
    override suspend fun enrollments(status: EnrollmentStatus?) = enrollments.values.filter { status == null || it.status == status }
    override suspend fun upsertEnrollment(enrollment: SequenceEnrollment) = enrollments.put(enrollment)
}

class InMemoryActivityRepository : ActivityRepository {
    private val store = Store<ActivityEvent> { it.id }
    val events: List<ActivityEvent> get() = store.values.sortedBy { it.at }
    override fun observeRecent(limit: Int) = store.observe().map { list -> list.sortedByDescending { it.at }.take(limit) }
    override suspend fun append(event: ActivityEvent) = store.put(event)
    override suspend fun since(at: Long) = store.values.filter { it.at >= at }
    override suspend fun prune(olderThan: Long) = store.values.filter { it.at < olderThan }.forEach { store.remove(it.id) }
    override suspend fun deleteAll() = store.clear()
}

class InMemoryConversationRepository : ConversationRepository {
    private val replies = MutableStateFlow<List<ReplyRecord>>(emptyList())
    private val fingerprints = MutableStateFlow<Map<String, Long>>(emptyMap())
    private val states = Store<ConversationState> { "${it.channel}:${it.conversationKey}" }
    override suspend fun repliesSince(since: Long) = replies.value.filter { it.at >= since }
    override suspend fun recordReply(record: ReplyRecord) = replies.update { it + record }
    override suspend fun hasFingerprint(fingerprint: String, since: Long) = (fingerprints.value[fingerprint] ?: Long.MIN_VALUE) >= since
    override suspend fun recordFingerprint(fingerprint: String, at: Long) = fingerprints.update { it + (fingerprint to at) }
    override suspend fun state(channel: ChannelId, conversationKey: String) = states.get("$channel:$conversationKey")
    override suspend fun setState(state: ConversationState) = states.put(state)
    override fun observeTakeovers() = states.observe()
    override suspend fun prune(olderThan: Long) {
        replies.update { list -> list.filter { it.at >= olderThan } }
        fingerprints.update { map -> map.filterValues { it >= olderThan } }
    }
    override suspend fun deleteAll() {
        replies.value = emptyList()
        fingerprints.value = emptyMap()
        states.clear()
    }
}

class InMemorySettingsRepository(initial: AutomationSettings = AutomationSettings()) : SettingsRepository {
    private val state = MutableStateFlow(initial)
    override val settings: Flow<AutomationSettings> = state
    override suspend fun current() = state.value
    override suspend fun update(transform: (AutomationSettings) -> AutomationSettings) = state.update(transform)
}
