package com.replypilot.data.repositories

import com.replypilot.core.database.ConversationDao
import com.replypilot.core.database.FingerprintEntity
import com.replypilot.core.database.ReplyPilotDatabase
import com.replypilot.data.local.identityEntities
import com.replypilot.data.local.toDomain
import com.replypilot.data.local.toEntity
import com.replypilot.domain.model.ActivityEvent
import com.replypilot.domain.model.AutoReplyRule
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
import com.replypilot.domain.repository.TemplateRepository
import com.replypilot.domain.scheduling.AddressRules
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Singleton
class RoomScheduleRepository @Inject constructor(db: ReplyPilotDatabase) : ScheduleRepository {
    private val dao = db.schedules()
    override fun observeAll(): Flow<List<ScheduledMessage>> = dao.observeAll().map { list -> list.map { it.toDomain() } }
    override suspend fun all() = dao.all().map { it.toDomain() }
    override suspend fun get(id: String) = dao.get(id)?.toDomain()
    override suspend fun upsert(message: ScheduledMessage) = dao.upsert(message.toEntity())
    override suspend fun delete(id: String) = dao.delete(id)
}

@Singleton
class RoomExecutionRepository @Inject constructor(db: ReplyPilotDatabase) : ExecutionRepository {
    private val dao = db.executions()
    override fun observeAll(): Flow<List<ExecutionItem>> = dao.observeAll().map { list -> list.map { it.toDomain() } }
    override fun observeBySource(sourceId: String) = dao.observeBySource(sourceId).map { list -> list.map { it.toDomain() } }
    override suspend fun get(id: String) = dao.get(id)?.toDomain()
    override suspend fun insertIfAbsent(items: List<ExecutionItem>): Int =
        if (items.isEmpty()) 0 else dao.insertIgnore(items.map { it.toEntity() }).count { it != -1L }
    override suspend fun update(item: ExecutionItem) = dao.upsert(item.toEntity())
    override suspend fun compareAndSetStatus(id: String, expected: MessageStatus, to: MessageStatus, at: Long) =
        dao.compareAndSet(id, expected.name, to.name, at) == 1
    override suspend fun replaceIfStatus(item: ExecutionItem, expected: MessageStatus) =
        dao.replaceIfStatus(item.toEntity(), expected.name)
    override suspend fun due(now: Long, statuses: Set<MessageStatus>, limit: Int) =
        dao.due(now, statuses.map { it.name }, limit).map { it.toDomain() }
    override suspend fun withStatus(statuses: Set<MessageStatus>) = dao.withStatus(statuses.map { it.name }).map { it.toDomain() }
    override suspend fun bySource(source: ExecutionSource, sourceId: String) = dao.bySource(source.name, sourceId).map { it.toDomain() }
    override suspend fun countSubmittedSince(since: Long, channel: ChannelId?) = dao.countSubmittedSince(since, channel?.name)
    override suspend fun nextWakeAt(statuses: Set<MessageStatus>) = dao.nextWakeAt(statuses.map { it.name })
    override suspend fun deleteWithStatus(statuses: Set<MessageStatus>) = dao.deleteWithStatus(statuses.map { it.name })
    override suspend fun deleteAll() = dao.deleteAll()
}

@Singleton
class RoomRuleRepository @Inject constructor(db: ReplyPilotDatabase) : RuleRepository {
    private val dao = db.rules()
    override fun observeAll(): Flow<List<AutoReplyRule>> = dao.observeAll().map { list -> list.map { it.toDomain() } }
    override suspend fun all() = dao.all().map { it.toDomain() }
    override suspend fun get(id: String) = dao.get(id)?.toDomain()
    override suspend fun upsert(rule: AutoReplyRule) = dao.upsert(rule.toEntity())
    override suspend fun delete(id: String) = dao.delete(id)
}

@Singleton
class RoomTemplateRepository @Inject constructor(db: ReplyPilotDatabase) : TemplateRepository {
    private val dao = db.templates()
    override fun observeAll(): Flow<List<MessageTemplate>> = dao.observeAll().map { list -> list.map { it.toDomain() } }
    override suspend fun all() = dao.all().map { it.toDomain() }
    override suspend fun get(id: String) = dao.get(id)?.toDomain()
    override suspend fun upsert(template: MessageTemplate) = dao.upsert(template.toEntity())
    override suspend fun delete(id: String) = dao.delete(id)
}

@Singleton
class RoomContactRepository @Inject constructor(db: ReplyPilotDatabase) : ContactRepository {
    private val dao = db.contacts()
    override fun observeAll(): Flow<List<Contact>> = dao.observeAll().map { list -> list.map { it.toDomain() } }
    override suspend fun all() = dao.all().map { it.toDomain() }
    override suspend fun get(id: String) = dao.get(id)?.toDomain()
    override suspend fun findByAddress(channel: ChannelId, address: String) =
        dao.findByAddress(channel.name, AddressRules.normalize(channel, address))?.toDomain()
    override suspend fun findByName(name: String) = dao.findByName(name.trim())?.toDomain()
    override suspend fun upsert(contact: Contact) = dao.save(contact.toEntity(), contact.identityEntities())
    override suspend fun upsertAll(contacts: List<Contact>) = contacts.forEach { upsert(it) }
    override suspend fun delete(id: String) = dao.delete(id)
}

@Singleton
class RoomSequenceRepository @Inject constructor(db: ReplyPilotDatabase) : SequenceRepository {
    private val dao = db.sequences()
    override fun observeSequences(): Flow<List<MessageSequence>> = dao.observeSequences().map { list -> list.map { it.toDomain() } }
    override fun observeEnrollments(): Flow<List<SequenceEnrollment>> = dao.observeEnrollments().map { list -> list.map { it.toDomain() } }
    override suspend fun allSequences() = dao.allSequences().map { it.toDomain() }
    override suspend fun getSequence(id: String) = dao.getSequence(id)?.toDomain()
    override suspend fun upsertSequence(sequence: MessageSequence) = dao.upsertSequence(sequence.toEntity())
    override suspend fun deleteSequence(id: String) = dao.deleteSequence(id)
    override suspend fun getEnrollment(id: String) = dao.getEnrollment(id)?.toDomain()
    override suspend fun enrollments(status: EnrollmentStatus?) = dao.enrollments(status?.name).map { it.toDomain() }
    override suspend fun upsertEnrollment(enrollment: SequenceEnrollment) = dao.upsertEnrollment(enrollment.toEntity())
}

@Singleton
class RoomActivityRepository @Inject constructor(db: ReplyPilotDatabase) : ActivityRepository {
    private val dao = db.activity()
    override fun observeRecent(limit: Int): Flow<List<ActivityEvent>> = dao.observeRecent(limit).map { list -> list.map { it.toDomain() } }
    override suspend fun append(event: ActivityEvent) = dao.insert(event.toEntity())
    override suspend fun since(at: Long) = dao.since(at).map { it.toDomain() }
    override suspend fun prune(olderThan: Long) = dao.prune(olderThan)
    override suspend fun deleteAll() = dao.deleteAll()
}

@Singleton
class RoomConversationRepository @Inject constructor(db: ReplyPilotDatabase) : ConversationRepository {
    private val dao: ConversationDao = db.conversations()
    override suspend fun repliesSince(since: Long): List<ReplyRecord> = dao.repliesSince(since).map { it.toDomain() }
    override suspend fun recordReply(record: ReplyRecord) = dao.insertReply(record.toEntity())
    override suspend fun hasFingerprint(fingerprint: String, since: Long) = dao.countFingerprint(fingerprint, since) > 0
    override suspend fun recordFingerprint(fingerprint: String, at: Long) = dao.insertFingerprint(FingerprintEntity(fingerprint, at))
    override suspend fun state(channel: ChannelId, conversationKey: String) = dao.state(channel.name, conversationKey)?.toDomain()
    override suspend fun setState(state: ConversationState) = dao.upsertState(state.toEntity())
    override fun observeTakeovers(): Flow<List<ConversationState>> = dao.observeTakeovers().map { list -> list.map { it.toDomain() } }
    override suspend fun prune(olderThan: Long) {
        dao.pruneReplies(olderThan)
        dao.pruneFingerprints(olderThan)
    }
    override suspend fun deleteAll() {
        dao.deleteAllReplies()
        dao.deleteAllFingerprints()
        dao.deleteAllStates()
    }
}
