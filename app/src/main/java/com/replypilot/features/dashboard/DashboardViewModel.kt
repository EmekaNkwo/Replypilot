package com.replypilot.features.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.replypilot.core.datastore.AppPreferencesRepository
import com.replypilot.core.permissions.Capability
import com.replypilot.core.permissions.CapabilityState
import com.replypilot.core.permissions.PlatformStatus
import com.replypilot.domain.common.Clock
import com.replypilot.domain.insights.DashboardStats
import com.replypilot.domain.insights.Insights
import com.replypilot.domain.messaging.AdapterRegistry
import com.replypilot.domain.model.ActivityEvent
import com.replypilot.domain.model.AutoReplyRule
import com.replypilot.domain.model.ChannelStatus
import com.replypilot.domain.model.IntegrationMode
import com.replypilot.domain.repository.ActivityRepository
import com.replypilot.domain.repository.ExecutionRepository
import com.replypilot.domain.repository.RuleRepository
import com.replypilot.domain.repository.SettingsRepository
import com.replypilot.domain.scheduling.QueueActions
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class DashboardUiState(
    val loading: Boolean = true,
    val now: Long = 0,
    val stats: DashboardStats? = null,
    val paused: Boolean = false,
    val demoMode: Boolean = false,
    val channels: List<ChannelStatus> = emptyList(),
    val missingPermissions: List<CapabilityState> = emptyList(),
    val recent: List<ActivityEvent> = emptyList(),
    val activeRules: List<AutoReplyRule> = emptyList(),
    val ruleNames: Map<String, String> = emptyMap(),
)

private data class DeviceState(val channels: List<ChannelStatus>, val permissions: List<CapabilityState>)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    executions: ExecutionRepository,
    rules: RuleRepository,
    activity: ActivityRepository,
    settings: SettingsRepository,
    preferences: AppPreferencesRepository,
    private val adapters: AdapterRegistry,
    private val platform: PlatformStatus,
    private val actions: QueueActions,
    private val clock: Clock,
) : ViewModel() {
    private val refresh = MutableStateFlow(0)

    private val ticker = flow {
        while (true) {
            emit(clock.now())
            delay(TICK_MS)
        }
    }

    /** Channel and permission state can change outside the app, so it is re-read on resume and every tick. */
    private val device = combine(ticker, refresh) { _, _ ->
        DeviceState(adapters.all().map { it.status() }.sortedBy { it.id.ordinal }, platform.states())
    }

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    val state: StateFlow<DashboardUiState> = combine(
        executions.observeAll(),
        rules.observeAll(),
        activity.observeRecent(RECENT_SCAN),
        combine(settings.settings, preferences.preferences) { s, p -> s to p },
        device,
    ) { items, ruleList, events, (currentSettings, prefs), deviceState ->
        val now = clock.now()
        val usesDeviceChannels = deviceState.channels.any {
            it.descriptor.mode == IntegrationMode.DEVICE_NOTIFICATION && it.id in prefs.enabledChannels
        }
        DashboardUiState(
            loading = false,
            now = now,
            stats = Insights.dashboard(items, ruleList, events, now, clock.zone()),
            paused = currentSettings.automationPaused,
            demoMode = prefs.demoMode,
            channels = deviceState.channels,
            missingPermissions = deviceState.permissions.filter { state ->
                state.applicable && !state.granted && when (state.capability) {
                    Capability.NOTIFICATION_ACCESS -> usesDeviceChannels
                    Capability.POST_NOTIFICATIONS -> true
                    // Only matters once Auto-send is on: without it, background sends can't open WhatsApp.
                    Capability.XIAOMI_BACKGROUND_START -> deviceState.permissions.any { it.capability == Capability.AUTO_SEND && it.granted }
                    else -> false
                }
            },
            recent = events.take(RECENT_SHOWN),
            activeRules = ruleList.filter { it.enabled }.sortedByDescending { it.priority },
            ruleNames = ruleList.associate { it.id to it.name },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardUiState())

    fun refresh() {
        refresh.value++
    }

    fun setPaused(paused: Boolean) = viewModelScope.launch {
        _messages.emit(
            try {
                actions.setAutomationPaused(paused)
                if (paused) "All automation paused. Nothing will be sent until you resume." else "Automation resumed"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "Couldn't change automation: ${e.message ?: "unexpected error"}"
            },
        )
    }

    private companion object {
        const val TICK_MS = 60_000L
        const val RECENT_SCAN = 500
        const val RECENT_SHOWN = 8
    }
}
