package com.replypilot.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ScheduleDao {
    @Query("SELECT * FROM schedules ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<ScheduleEntity>>

    @Query("SELECT * FROM schedules")
    suspend fun all(): List<ScheduleEntity>

    @Query("SELECT * FROM schedules WHERE id = :id")
    suspend fun get(id: String): ScheduleEntity?

    @Upsert
    suspend fun upsert(entity: ScheduleEntity)

    @Query("DELETE FROM schedules WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM schedules")
    suspend fun deleteAll()
}

@Dao
interface ExecutionDao {
    @Query("SELECT * FROM executions ORDER BY nextAttemptAt DESC")
    fun observeAll(): Flow<List<ExecutionEntity>>

    @Query("SELECT * FROM executions WHERE sourceId = :sourceId ORDER BY plannedAt")
    fun observeBySource(sourceId: String): Flow<List<ExecutionEntity>>

    @Query("SELECT * FROM executions WHERE id = :id")
    suspend fun get(id: String): ExecutionEntity?

    /** IGNORE on the unique idempotency key is what makes materialization repeatable. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(items: List<ExecutionEntity>): List<Long>

    @Upsert
    suspend fun upsert(item: ExecutionEntity)

    @Query("UPDATE executions SET status = :to, updatedAt = :at, lastAttemptAt = CASE WHEN :to = 'EXECUTING' THEN :at ELSE lastAttemptAt END WHERE id = :id AND status = :expected")
    suspend fun compareAndSet(id: String, expected: String, to: String, at: Long): Int

    /** Room serialises write transactions, so the read-check-write below is atomic with respect to other writers. */
    @Transaction
    suspend fun replaceIfStatus(item: ExecutionEntity, expected: String): Boolean {
        if (get(item.id)?.status != expected) return false
        upsert(item)
        return true
    }

    @Query("SELECT * FROM executions WHERE status IN (:statuses) AND nextAttemptAt <= :now ORDER BY nextAttemptAt LIMIT :limit")
    suspend fun due(now: Long, statuses: List<String>, limit: Int): List<ExecutionEntity>

    @Query("SELECT * FROM executions WHERE status IN (:statuses)")
    suspend fun withStatus(statuses: List<String>): List<ExecutionEntity>

    @Query("SELECT * FROM executions WHERE source = :source AND sourceId = :sourceId ORDER BY plannedAt")
    suspend fun bySource(source: String, sourceId: String): List<ExecutionEntity>

    @Query("SELECT COUNT(*) FROM executions WHERE status IN ('SUBMITTED','DELIVERED') AND lastAttemptAt >= :since AND (:channel IS NULL OR channel = :channel)")
    suspend fun countSubmittedSince(since: Long, channel: String?): Int

    @Query("SELECT MIN(nextAttemptAt) FROM executions WHERE status IN (:statuses)")
    suspend fun nextWakeAt(statuses: List<String>): Long?

    @Query("DELETE FROM executions")
    suspend fun deleteAll()

    @Query("DELETE FROM executions WHERE status IN (:statuses)")
    suspend fun deleteWithStatus(statuses: List<String>): Int

    @Query("DELETE FROM executions WHERE simulated = 1")
    suspend fun deleteSimulated()
}

@Dao
interface RuleDao {
    @Query("SELECT * FROM rules ORDER BY priority DESC, name")
    fun observeAll(): Flow<List<RuleEntity>>

    @Query("SELECT * FROM rules")
    suspend fun all(): List<RuleEntity>

    @Query("SELECT * FROM rules WHERE id = :id")
    suspend fun get(id: String): RuleEntity?

    @Upsert
    suspend fun upsert(entity: RuleEntity)

    @Query("DELETE FROM rules WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM rules")
    suspend fun deleteAll()
}

@Dao
interface TemplateDao {
    @Query("SELECT * FROM templates ORDER BY category, name")
    fun observeAll(): Flow<List<TemplateEntity>>

    @Query("SELECT * FROM templates")
    suspend fun all(): List<TemplateEntity>

    @Query("SELECT * FROM templates WHERE id = :id")
    suspend fun get(id: String): TemplateEntity?

    @Upsert
    suspend fun upsert(entity: TemplateEntity)

    @Query("DELETE FROM templates WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM templates")
    suspend fun deleteAll()
}

@Dao
interface ContactDao {
    @Query("SELECT * FROM contacts ORDER BY displayName COLLATE NOCASE")
    fun observeAll(): Flow<List<ContactEntity>>

    @Query("SELECT * FROM contacts")
    suspend fun all(): List<ContactEntity>

    @Query("SELECT * FROM contacts WHERE id = :id")
    suspend fun get(id: String): ContactEntity?

    @Query("SELECT c.* FROM contacts c JOIN contact_identities i ON i.contactId = c.id WHERE i.channel = :channel AND i.address = :address LIMIT 1")
    suspend fun findByAddress(channel: String, address: String): ContactEntity?

    @Query("SELECT * FROM contacts WHERE displayName = :name COLLATE NOCASE LIMIT 1")
    suspend fun findByName(name: String): ContactEntity?

    @Upsert
    suspend fun upsert(entity: ContactEntity)

    @Query("DELETE FROM contact_identities WHERE contactId = :contactId")
    suspend fun clearIdentities(contactId: String)

    @Upsert
    suspend fun upsertIdentities(identities: List<ContactIdentityEntity>)

    @Transaction
    suspend fun save(entity: ContactEntity, identities: List<ContactIdentityEntity>) {
        upsert(entity)
        clearIdentities(entity.id)
        upsertIdentities(identities)
    }

    @Query("DELETE FROM contacts WHERE id = :id")
    suspend fun deleteContact(id: String)

    @Transaction
    suspend fun delete(id: String) {
        clearIdentities(id)
        deleteContact(id)
    }

    @Query("DELETE FROM contacts")
    suspend fun deleteAllContacts()

    @Query("DELETE FROM contact_identities")
    suspend fun deleteAllIdentities()
}

@Dao
interface SequenceDao {
    @Query("SELECT * FROM sequences ORDER BY name")
    fun observeSequences(): Flow<List<SequenceEntity>>

    @Query("SELECT * FROM enrollments ORDER BY updatedAt DESC")
    fun observeEnrollments(): Flow<List<EnrollmentEntity>>

    @Query("SELECT * FROM sequences")
    suspend fun allSequences(): List<SequenceEntity>

    @Query("SELECT * FROM sequences WHERE id = :id")
    suspend fun getSequence(id: String): SequenceEntity?

    @Upsert
    suspend fun upsertSequence(entity: SequenceEntity)

    @Query("DELETE FROM sequences WHERE id = :id")
    suspend fun deleteSequence(id: String)

    @Query("SELECT * FROM enrollments WHERE id = :id")
    suspend fun getEnrollment(id: String): EnrollmentEntity?

    @Query("SELECT * FROM enrollments WHERE (:status IS NULL OR status = :status)")
    suspend fun enrollments(status: String?): List<EnrollmentEntity>

    @Upsert
    suspend fun upsertEnrollment(entity: EnrollmentEntity)

    @Query("DELETE FROM sequences")
    suspend fun deleteAllSequences()

    @Query("DELETE FROM enrollments")
    suspend fun deleteAllEnrollments()
}

@Dao
interface ActivityDao {
    @Query("SELECT * FROM activity ORDER BY at DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<ActivityEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: ActivityEntity)

    @Query("SELECT * FROM activity WHERE at >= :at ORDER BY at DESC")
    suspend fun since(at: Long): List<ActivityEntity>

    @Query("DELETE FROM activity WHERE at < :olderThan")
    suspend fun prune(olderThan: Long)

    @Query("DELETE FROM activity")
    suspend fun deleteAll()

    @Query("DELETE FROM activity WHERE simulated = 1")
    suspend fun deleteSimulated()
}

@Dao
interface ConversationDao {
    @Query("SELECT * FROM reply_records WHERE at >= :since")
    suspend fun repliesSince(since: Long): List<ReplyRecordEntity>

    @Insert
    suspend fun insertReply(entity: ReplyRecordEntity)

    @Query("SELECT COUNT(*) FROM fingerprints WHERE fingerprint = :fingerprint AND at >= :since")
    suspend fun countFingerprint(fingerprint: String, since: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFingerprint(entity: FingerprintEntity)

    @Query("SELECT * FROM conversation_states WHERE channel = :channel AND conversationKey = :key")
    suspend fun state(channel: String, key: String): ConversationStateEntity?

    @Upsert
    suspend fun upsertState(entity: ConversationStateEntity)

    @Query("SELECT * FROM conversation_states WHERE humanTakeoverUntil IS NOT NULL")
    fun observeTakeovers(): Flow<List<ConversationStateEntity>>

    @Query("DELETE FROM reply_records WHERE at < :olderThan")
    suspend fun pruneReplies(olderThan: Long)

    @Query("DELETE FROM fingerprints WHERE at < :olderThan")
    suspend fun pruneFingerprints(olderThan: Long)

    @Query("DELETE FROM reply_records")
    suspend fun deleteAllReplies()

    @Query("DELETE FROM fingerprints")
    suspend fun deleteAllFingerprints()

    @Query("DELETE FROM conversation_states")
    suspend fun deleteAllStates()
}
