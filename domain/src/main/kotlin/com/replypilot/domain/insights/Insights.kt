package com.replypilot.domain.insights

import com.replypilot.domain.common.DAY_MS
import com.replypilot.domain.model.ActivityEvent
import com.replypilot.domain.model.ActivityType
import com.replypilot.domain.model.AutoReplyRule
import com.replypilot.domain.model.ChannelId
import com.replypilot.domain.model.ExecutionItem
import com.replypilot.domain.model.ExecutionSource
import com.replypilot.domain.model.MessageStatus
import com.replypilot.domain.scheduling.TimeWindows
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class DashboardStats(
    val dueToday: Int,
    val sentToday: Int,
    val awaiting: Int,
    val failed: Int,
    val needsAttention: Int,
    val awaitingReview: Int,
    val activeRules: Int,
    val rulesNeedingAttention: Int,
    val incomingProcessedToday: Int,
    val nextScheduled: ExecutionItem?,
    val upcoming: List<ExecutionItem>,
)

data class DailyCount(val date: LocalDate, val submitted: Int, val failed: Int)

data class AnalyticsReport(
    val days: List<DailyCount>,
    val byChannel: Map<ChannelId, Int>,
    val bySource: Map<ExecutionSource, Int>,
    val successRate: Double?,
    val topRules: List<Pair<String, Int>>,
    val suppressions: List<Pair<String, Int>>,
    val incomingProcessed: Int,
    val simulatedShare: Double?,
)

/** Pure aggregations over local state. Every number shown on the dashboard comes from here. */
object Insights {
    private val INCOMING_TYPES = setOf(ActivityType.RULE_MATCHED, ActivityType.NO_RULE_MATCHED)

    fun dashboard(
        executions: List<ExecutionItem>,
        rules: List<AutoReplyRule>,
        activity: List<ActivityEvent>,
        now: Long,
        zone: ZoneId,
    ): DashboardStats {
        val dayStart = TimeWindows.startOfDay(now, zone)
        val dayEnd = TimeWindows.startOfNextDay(now, zone)
        val pending = executions.filter { it.status in MessageStatus.RUNNABLE }
        val ruleIdsWithFailures = executions
            .filter { it.source == ExecutionSource.AUTO_REPLY && it.status == MessageStatus.FAILED && it.updatedAt >= now - DAY_MS }
            .map { it.sourceId }.toSet()
        return DashboardStats(
            dueToday = executions.count { it.plannedAt in dayStart until dayEnd && it.status != MessageStatus.CANCELLED },
            sentToday = executions.count { it.status in MessageStatus.SUCCESS && (it.lastAttemptAt ?: it.updatedAt) >= dayStart },
            awaiting = pending.size,
            failed = executions.count { it.status == MessageStatus.FAILED && it.updatedAt >= dayStart },
            needsAttention = executions.count { it.status == MessageStatus.NEEDS_ATTENTION },
            awaitingReview = executions.count { it.status == MessageStatus.AWAITING_REVIEW },
            activeRules = rules.count { it.enabled },
            rulesNeedingAttention = rules.count { it.enabled && it.id in ruleIdsWithFailures },
            incomingProcessedToday = activity.count { it.type in INCOMING_TYPES && it.at >= dayStart },
            nextScheduled = pending.minByOrNull { it.nextAttemptAt },
            upcoming = pending.sortedBy { it.nextAttemptAt }.take(5),
        )
    }

    fun analytics(
        executions: List<ExecutionItem>,
        rules: List<AutoReplyRule>,
        activity: List<ActivityEvent>,
        now: Long,
        zone: ZoneId,
        days: Int = 7,
    ): AnalyticsReport {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val since = today.minusDays((days - 1).toLong()).atStartOfDay(zone).toInstant().toEpochMilli()
        val recent = executions.filter { (it.lastAttemptAt ?: it.updatedAt) >= since }
        val succeeded = recent.filter { it.status in MessageStatus.SUCCESS }
        val failed = recent.filter { it.status == MessageStatus.FAILED }
        val finished = succeeded.size + failed.size
        fun dateOf(item: ExecutionItem) = Instant.ofEpochMilli(item.lastAttemptAt ?: item.updatedAt).atZone(zone).toLocalDate()
        val ruleNames = rules.associate { it.id to it.name }
        val recentActivity = activity.filter { it.at >= since }
        return AnalyticsReport(
            days = (0 until days).map { offset ->
                val date = today.minusDays((days - 1 - offset).toLong())
                DailyCount(date, succeeded.count { dateOf(it) == date }, failed.count { dateOf(it) == date })
            },
            byChannel = succeeded.groupingBy { it.channel }.eachCount(),
            bySource = succeeded.groupingBy { it.source }.eachCount(),
            successRate = if (finished == 0) null else succeeded.size.toDouble() / finished,
            topRules = recentActivity.filter { it.type == ActivityType.RULE_MATCHED && it.ruleId != null }
                .groupingBy { ruleNames[it.ruleId] ?: "Deleted rule" }.eachCount()
                .entries.sortedByDescending { it.value }.take(5).map { it.key to it.value },
            suppressions = recentActivity.filter { it.type == ActivityType.AUTO_REPLY_SUPPRESSED }
                .groupingBy { it.reason?.substringBefore(":") ?: "Other" }.eachCount()
                .entries.sortedByDescending { it.value }.map { it.key to it.value },
            incomingProcessed = recentActivity.count { it.type in INCOMING_TYPES },
            simulatedShare = if (succeeded.isEmpty()) null else succeeded.count { it.simulated }.toDouble() / succeeded.size,
        )
    }
}
