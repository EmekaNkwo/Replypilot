package com.replypilot.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/*
 * Query-relevant fields are real columns; nested, rarely-filtered structures are JSON payloads
 * so the domain model can evolve without a migration for every new optional field.
 */

@Entity(tableName = "schedules")
data class ScheduleEntity(
    @PrimaryKey val id: String,
    val title: String,
    val channel: String,
    val state: String,
    val isDemo: Boolean,
    val updatedAt: Long,
    /** Full [com.replypilot.domain.model.ScheduledMessage] as JSON. */
    val payload: String,
)

@Entity(
    tableName = "executions",
    indices = [
        Index(value = ["idempotencyKey"], unique = true),
        Index(value = ["status", "nextAttemptAt"]),
        Index(value = ["source", "sourceId"]),
    ],
)
data class ExecutionEntity(
    @PrimaryKey val id: String,
    val idempotencyKey: String,
    val source: String,
    val sourceId: String,
    val occurrenceIndex: Int,
    val channel: String,
    val recipientAddress: String,
    val recipientName: String,
    val recipientContactId: String?,
    /** Added in schema 2: INDIVIDUAL or GROUP. */
    @ColumnInfo(defaultValue = "INDIVIDUAL") val recipientKind: String = "INDIVIDUAL",
    val body: String,
    /** Added in schema 3: the attached files as a JSON list, or null when there are none. */
    @ColumnInfo(defaultValue = "NULL") val attachmentsJson: String? = null,
    val plannedAt: Long,
    val expiresAt: Long?,
    val status: String,
    val attempts: Int,
    val maxAttempts: Int,
    val lastAttemptAt: Long?,
    val nextAttemptAt: Long,
    val failureCode: String?,
    val statusReason: String?,
    val providerRef: String?,
    val handoffJson: String?,
    val replyTarget: String?,
    val quietHours: String,
    val simulated: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(tableName = "rules")
data class RuleEntity(
    @PrimaryKey val id: String,
    val name: String,
    val enabled: Boolean,
    val priority: Int,
    val updatedAt: Long,
    val payload: String,
)

@Entity(tableName = "templates")
data class TemplateEntity(
    @PrimaryKey val id: String,
    val name: String,
    val category: String,
    val body: String,
    val updatedAt: Long,
)

@Entity(tableName = "contacts")
data class ContactEntity(
    @PrimaryKey val id: String,
    val displayName: String,
    val optedOut: Boolean,
    val updatedAt: Long,
    val payload: String,
)

/** Lookup table so an incoming address can be matched to a contact with an index, per channel. */
@Entity(
    tableName = "contact_identities",
    primaryKeys = ["channel", "address"],
    indices = [Index("contactId")],
)
data class ContactIdentityEntity(
    val channel: String,
    val address: String,
    val contactId: String,
)

@Entity(tableName = "sequences")
data class SequenceEntity(
    @PrimaryKey val id: String,
    val name: String,
    val updatedAt: Long,
    val payload: String,
)

@Entity(tableName = "enrollments", indices = [Index("sequenceId"), Index("status")])
data class EnrollmentEntity(
    @PrimaryKey val id: String,
    val sequenceId: String,
    val status: String,
    val updatedAt: Long,
    val payload: String,
)

@Entity(tableName = "activity", indices = [Index("at"), Index("type")])
data class ActivityEntity(
    @PrimaryKey val id: String,
    val at: Long,
    val type: String,
    val result: String,
    val channel: String?,
    val source: String?,
    val sourceId: String?,
    val ruleId: String?,
    val executionId: String?,
    val recipientRef: String?,
    val attempt: Int?,
    val reason: String?,
    val simulated: Boolean,
)

@Entity(tableName = "reply_records", indices = [Index("at")])
data class ReplyRecordEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val ruleId: String,
    val channel: String,
    val conversationKey: String,
    val textHash: String,
    val at: Long,
)

@Entity(tableName = "fingerprints")
data class FingerprintEntity(
    @PrimaryKey val fingerprint: String,
    val at: Long,
)

@Entity(tableName = "conversation_states", primaryKeys = ["channel", "conversationKey"])
data class ConversationStateEntity(
    val channel: String,
    val conversationKey: String,
    val humanTakeoverUntil: Long?,
)
