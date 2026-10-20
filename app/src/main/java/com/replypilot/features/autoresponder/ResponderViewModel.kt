package com.replypilot.features.autoresponder

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.replypilot.domain.common.Clock
import com.replypilot.domain.model.ActivityEvent
import com.replypilot.domain.model.ActivityType
import com.replypilot.domain.model.ConversationState
import com.replypilot.domain.model.ExecutionItem
import com.replypilot.domain.model.ExecutionSource
import com.replypilot.domain.model.MessageStatus
import com.replypilot.domain.repository.ActivityRepository
import com.replypilot.domain.repository.ConversationRepository
import com.replypilot.domain.repository.ExecutionRepository
import com.replypilot.domain.repository.RuleRepository
import com.replypilot.domain.repository.SettingsRepository
import com.replypilot.domain.scheduling.ActionResult
import com.replypilot.domain.scheduling.QueueActions
import com.replypilot.domain.scheduling.TimeWindows
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ResponderState(
    val loading: Boolean = true,
    val paused: Boolean = false,
    val activeRules: Int = 0,
    val totalRules: Int = 0,
    val repliesToday: Int = 0,
    val suppressedToday: Int = 0,
    val approvals: List<ExecutionItem> = emptyList(),
    val takeovers: List<ConversationState> = emptyList(),
    val recent: List<ActivityEvent> = emptyList(),
    val ruleNames: Map<String, String> = emptyMap(),
)

@HiltViewModel
class ResponderViewModel @Inject constructor(
    settings: SettingsRepository,
    rules: RuleRepository,
    executions: ExecutionRepository,
    activity: ActivityRepository,
    conversations: ConversationRepository,
    private val actions: QueueActions,
    private val clock: Clock,
) : ViewModel() {
    val state: StateFlow<ResponderState> = combine(
        settings.settings, rules.observeAll(), executions.observeAll(), activity.observeRecent(RECENT_LIMIT), conversations.observeTakeovers(),
    ) { s, r, e, a, t ->
        val now = clock.now()
        val dayStart = TimeWindows.startOfDay(now, clock.zone())
        ResponderState(
            loading = false,
            paused = s.automationPaused,
            activeRules = r.count { it.enabled },
            totalRules = r.size,
            repliesToday = a.count { it.type == ActivityType.REPLY_SENT && it.at >= dayStart },
            suppressedToday = a.count { it.type == ActivityType.AUTO_REPLY_SUPPRESSED && it.at >= dayStart },
            approvals = e.filter { it.source == ExecutionSource.AUTO_REPLY && it.status == MessageStatus.AWAITING_REVIEW }.sortedByDescending { it.createdAt },
            takeovers = t.filter { (it.humanTakeoverUntil ?: 0) > now },
            recent = a.filter { it.type in AUTO_REPLY_TYPES }.take(SHOWN_RECENT),
            ruleNames = r.associate { it.id to it.name },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ResponderState())

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    fun setPaused(paused: Boolean) = launch(if (paused) "All automation paused. Nothing will be sent until you resume." else "Automation resumed") {
        actions.setAutomationPaused(paused)
        ActionResult.Done
    }

    fun approve(item: ExecutionItem, editedBody: String? = null) = launch("Reply approved and queued") { actions.approve(item.id, editedBody) }

    fun reject(item: ExecutionItem) = launch("Reply discarded") { actions.reject(item.id) }

    fun release(state: ConversationState) = launch("Automation resumed for ${state.conversationKey}") {
        actions.releaseTakeover(state.channel, state.conversationKey)
        ActionResult.Done
    }

    private fun launch(success: String, block: suspend () -> ActionResult) {
        viewModelScope.launch {
            try {
                when (val result = block()) {
                    ActionResult.Done -> _messages.emit(success)
                    is ActionResult.Rejected -> _messages.emit(result.reason)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.emit("Something went wrong. Please try again.")
            }
        }
    }

    private companion object {
        const val RECENT_LIMIT = 500
        const val SHOWN_RECENT = 20
        val AUTO_REPLY_TYPES = setOf(
            ActivityType.RULE_MATCHED, ActivityType.AUTO_REPLY_SUPPRESSED, ActivityType.REPLY_SENT,
            ActivityType.REPLY_AWAITING_APPROVAL, ActivityType.OPT_OUT_RECORDED,
        )
    }
}
