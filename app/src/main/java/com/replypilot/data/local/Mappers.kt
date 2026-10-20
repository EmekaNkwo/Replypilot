package com.replypilot.data.local

import com.replypilot.domain.model.Attachment
import kotlinx.serialization.builtins.ListSerializer
import com.replypilot.core.database.ActivityEntity
import com.replypilot.core.database.AppJson
import com.replypilot.core.database.ContactEntity
import com.replypilot.core.database.ContactIdentityEntity
import com.replypilot.core.database.ConversationStateEntity
import com.replypilot.core.database.EnrollmentEntity
import com.replypilot.core.database.ExecutionEntity
import com.replypilot.core.database.ReplyRecordEntity
import com.replypilot.core.database.RuleEntity
import com.replypilot.core.database.ScheduleEntity
import com.replypilot.core.database.SequenceEntity
import com.replypilot.core.database.TemplateEntity
import com.replypilot.domain.model.ActivityEvent
import com.replypilot.domain.model.ActivityResult
import com.replypilot.domain.model.ActivityType
import com.replypilot.domain.model.AutoReplyRule
import com.replypilot.domain.model.ChannelId
import com.replypilot.domain.model.Contact
import com.replypilot.domain.model.ConversationState
import com.replypilot.domain.model.ExecutionItem
import com.replypilot.domain.model.ExecutionSource
import com.replypilot.domain.model.FailureCode
import com.replypilot.domain.model.ManualHandoff
import com.replypilot.domain.model.MessageSequence
import com.replypilot.domain.model.MessageStatus
import com.replypilot.domain.model.MessageTemplate
import com.replypilot.domain.model.QuietHoursBehavior
import com.replypilot.domain.model.Recipient
import com.replypilot.domain.model.RecipientKind
import com.replypilot.domain.model.ReplyRecord
import com.replypilot.domain.model.ScheduledMessage
import com.replypilot.domain.model.SequenceEnrollment
import com.replypilot.domain.model.TemplateCategory
import com.replypilot.domain.scheduling.AddressRules

/** Entity <-> domain conversions. Unknown enum values (e.g. after a downgrade) fall back safely. */

private inline fun <reified T : Enum<T>> enumOr(value: String?, fallback: T): T =
    value?.let { name -> enumValues<T>().firstOrNull { it.name == name } } ?: fallback

fun ScheduledMessage.toEntity() = ScheduleEntity(
    id = id, title = title, channel = channel.name, state = state.name, isDemo = isDemo, updatedAt = updatedAt,
    payload = AppJson.encodeToString(ScheduledMessage.serializer(), this),
)

fun ScheduleEntity.toDomain(): ScheduledMessage = AppJson.decodeFromString(ScheduledMessage.serializer(), payload)

fun ExecutionItem.toEntity() = ExecutionEntity(
    id = id, idempotencyKey = idempotencyKey, source = source.name, sourceId = sourceId, occurrenceIndex = occurrenceIndex,
    channel = channel.name, recipientAddress = recipient.address, recipientName = recipient.displayName,
    recipientContactId = recipient.contactId, recipientKind = recipient.kind.name, body = body,
    attachmentsJson = attachments.takeIf { it.isNotEmpty() }?.let { AppJson.encodeToString(ATTACHMENTS, it) }, plannedAt = plannedAt, expiresAt = expiresAt, status = status.name,
    attempts = attempts, maxAttempts = maxAttempts, lastAttemptAt = lastAttemptAt, nextAttemptAt = nextAttemptAt,
    failureCode = failureCode?.name, statusReason = statusReason, providerRef = providerRef,
    handoffJson = handoff?.let { AppJson.encodeToString(ManualHandoff.serializer(), it) }, replyTarget = replyTarget,
    quietHours = quietHours.name, simulated = simulated, createdAt = createdAt, updatedAt = updatedAt,
)

fun ExecutionEntity.toDomain() = ExecutionItem(
    id = id, idempotencyKey = idempotencyKey, source = enumOr(source, ExecutionSource.SCHEDULED), sourceId = sourceId,
    occurrenceIndex = occurrenceIndex, channel = enumOr(channel, ChannelId.DEMO),
    recipient = Recipient(recipientAddress, recipientName, recipientContactId, enumOr(recipientKind, RecipientKind.INDIVIDUAL)), body = body,
    attachments = attachmentsJson?.let { runCatching { AppJson.decodeFromString(ATTACHMENTS, it) }.getOrNull() }.orEmpty(), plannedAt = plannedAt,
    expiresAt = expiresAt, status = enumOr(status, MessageStatus.NEEDS_ATTENTION), attempts = attempts, maxAttempts = maxAttempts,
    lastAttemptAt = lastAttemptAt, nextAttemptAt = nextAttemptAt,
    failureCode = failureCode?.let { enumOr(it, FailureCode.UNKNOWN) }, statusReason = statusReason, providerRef = providerRef,
    handoff = handoffJson?.let { runCatching { AppJson.decodeFromString(ManualHandoff.serializer(), it) }.getOrNull() },
    replyTarget = replyTarget, quietHours = enumOr(quietHours, QuietHoursBehavior.DEFER), simulated = simulated,
    createdAt = createdAt, updatedAt = updatedAt,
)

fun AutoReplyRule.toEntity() = RuleEntity(
    id = id, name = name, enabled = enabled, priority = priority, updatedAt = updatedAt,
    payload = AppJson.encodeToString(AutoReplyRule.serializer(), this),
)

fun RuleEntity.toDomain(): AutoReplyRule = AppJson.decodeFromString(AutoReplyRule.serializer(), payload)

fun MessageTemplate.toEntity() = TemplateEntity(id, name, category.name, body, updatedAt)

fun TemplateEntity.toDomain() = MessageTemplate(id, name, enumOr(category, TemplateCategory.CUSTOM), body, updatedAt)

fun Contact.toEntity() = ContactEntity(
    id = id, displayName = displayName, optedOut = optedOut, updatedAt = updatedAt,
    payload = AppJson.encodeToString(Contact.serializer(), this),
)

fun Contact.identityEntities() = identities.map {
    ContactIdentityEntity(it.channel.name, AddressRules.normalize(it.channel, it.address), id)
}.distinctBy { it.channel to it.address }

fun ContactEntity.toDomain(): Contact = AppJson.decodeFromString(Contact.serializer(), payload)

fun MessageSequence.toEntity() = SequenceEntity(id, name, updatedAt, AppJson.encodeToString(MessageSequence.serializer(), this))

fun SequenceEntity.toDomain(): MessageSequence = AppJson.decodeFromString(MessageSequence.serializer(), payload)

fun SequenceEnrollment.toEntity() =
    EnrollmentEntity(id, sequenceId, status.name, updatedAt, AppJson.encodeToString(SequenceEnrollment.serializer(), this))

fun EnrollmentEntity.toDomain(): SequenceEnrollment = AppJson.decodeFromString(SequenceEnrollment.serializer(), payload)

fun ActivityEvent.toEntity() = ActivityEntity(
    id = id, at = at, type = type.name, result = result.name, channel = channel?.name, source = source?.name,
    sourceId = sourceId, ruleId = ruleId, executionId = executionId, recipientRef = recipientRef, attempt = attempt,
    reason = reason, simulated = simulated,
)

fun ActivityEntity.toDomain() = ActivityEvent(
    id = id, at = at, type = enumOr(type, ActivityType.EXECUTION_STARTED), result = enumOr(result, ActivityResult.INFO),
    channel = channel?.let { enumOr(it, ChannelId.DEMO) }, source = source?.let { enumOr(it, ExecutionSource.SCHEDULED) },
    sourceId = sourceId, ruleId = ruleId, executionId = executionId, recipientRef = recipientRef, attempt = attempt,
    reason = reason, simulated = simulated,
)

fun ReplyRecord.toEntity() = ReplyRecordEntity(ruleId = ruleId, channel = channel.name, conversationKey = conversationKey, textHash = textHash, at = at)

fun ReplyRecordEntity.toDomain() = ReplyRecord(ruleId, enumOr(channel, ChannelId.DEMO), conversationKey, textHash, at)

fun ConversationState.toEntity() = ConversationStateEntity(channel.name, conversationKey, humanTakeoverUntil)

fun ConversationStateEntity.toDomain() = ConversationState(enumOr(channel, ChannelId.DEMO), conversationKey, humanTakeoverUntil)

private val ATTACHMENTS = ListSerializer(Attachment.serializer())
