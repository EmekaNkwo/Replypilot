package com.replypilot.features.analytics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.replypilot.domain.common.Clock
import com.replypilot.domain.common.DAY_MS
import com.replypilot.domain.insights.AnalyticsReport
import com.replypilot.domain.insights.Insights
import com.replypilot.domain.messaging.AdapterRegistry
import com.replypilot.domain.model.ActivityEvent
import com.replypilot.domain.model.ChannelCapability
import com.replypilot.domain.model.ChannelId
import com.replypilot.domain.model.ExecutionItem
import com.replypilot.domain.model.MessageStatus
import com.replypilot.domain.repository.ActivityRepository
import com.replypilot.domain.repository.ExecutionRepository
import com.replypilot.domain.repository.RuleRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class DeliveryRow(
    val channel: ChannelId,
    val submitted: Int,
    val delivered: Int,
    val failed: Int,
    val needsAttention: Int,
    val confirmsDelivery: Boolean,
)

data class InsightsUiState(
    val loading: Boolean = true,
    val days: Int = 7,
    val report: AnalyticsReport? = null,
    val delivery: List<DeliveryRow> = emptyList(),
    val deliveryChannels: List<ChannelId> = emptyList(),
    val recentSubmitted: List<ExecutionItem> = emptyList(),
    val activity: List<ActivityEvent> = emptyList(),
    val ruleNames: Map<String, String> = emptyMap(),
)

@HiltViewModel
class InsightsViewModel @Inject constructor(
    executions: ExecutionRepository,
    rules: RuleRepository,
    activity: ActivityRepository,
    adapters: AdapterRegistry,
    private val clock: Clock,
) : ViewModel() {
    private val days = MutableStateFlow(7)
    private val confirming = adapters.all().filter { it.descriptor.supports(ChannelCapability.DELIVERY_STATUS) }.map { it.id }

    val state: StateFlow<InsightsUiState> = combine(
        executions.observeAll(), rules.observeAll(), activity.observeRecent(ACTIVITY_LIMIT), days,
    ) { items, ruleList, events, range ->
        val now = clock.now()
        val windowStart = now - range * DAY_MS
        val inWindow = items.filter { (it.lastAttemptAt ?: it.updatedAt) >= windowStart }
        InsightsUiState(
            loading = false,
            days = range,
            report = Insights.analytics(items, ruleList, events, now, clock.zone(), range),
            delivery = inWindow.groupBy { it.channel }.map { (channel, rows) ->
                DeliveryRow(
                    channel = channel,
                    submitted = rows.count { it.status == MessageStatus.SUBMITTED },
                    delivered = rows.count { it.status == MessageStatus.DELIVERED },
                    failed = rows.count { it.status == MessageStatus.FAILED },
                    needsAttention = rows.count { it.status == MessageStatus.NEEDS_ATTENTION },
                    confirmsDelivery = channel in confirming,
                )
            }.filter { it.submitted + it.delivered + it.failed + it.needsAttention > 0 }.sortedBy { it.channel.displayName },
            deliveryChannels = confirming,
            recentSubmitted = items.filter { it.status in MessageStatus.SUCCESS || it.status == MessageStatus.FAILED }
                .sortedByDescending { it.lastAttemptAt ?: it.updatedAt }.take(RECENT_LIMIT),
            activity = events,
            ruleNames = ruleList.associate { it.id to it.name },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InsightsUiState())

    fun setDays(value: Int) {
        days.value = value
    }

    private companion object {
        const val ACTIVITY_LIMIT = 500
        const val RECENT_LIMIT = 30
    }
}
