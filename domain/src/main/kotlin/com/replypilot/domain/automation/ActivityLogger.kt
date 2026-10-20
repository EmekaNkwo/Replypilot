package com.replypilot.domain.automation

import com.replypilot.domain.common.Clock
import com.replypilot.domain.common.IdGenerator
import com.replypilot.domain.model.ActivityEvent
import com.replypilot.domain.model.ActivityResult
import com.replypilot.domain.model.ActivityType
import com.replypilot.domain.model.ExecutionItem
import com.replypilot.domain.model.ExecutionSource
import com.replypilot.domain.repository.ActivityRepository

class ActivityLogger(
    private val repository: ActivityRepository,
    private val clock: Clock,
    private val ids: IdGenerator,
) {
    suspend fun log(
        type: ActivityType,
        result: ActivityResult,
        reason: String? = null,
        simulated: Boolean = false,
        build: (ActivityEvent) -> ActivityEvent = { it },
    ) {
        val base = ActivityEvent(
            id = ids.next(),
            at = clock.now(),
            type = type,
            result = result,
            reason = reason,
            simulated = simulated,
        )
        repository.append(build(base))
    }

    suspend fun forExecution(item: ExecutionItem, type: ActivityType, result: ActivityResult, reason: String? = null) =
        log(type, result, reason, item.simulated) {
            it.copy(
                channel = item.channel,
                source = item.source,
                sourceId = item.sourceId,
                ruleId = item.sourceId.takeIf { item.source == ExecutionSource.AUTO_REPLY },
                executionId = item.id,
                recipientRef = item.recipient.displayName,
                attempt = item.attempts,
            )
        }
}
