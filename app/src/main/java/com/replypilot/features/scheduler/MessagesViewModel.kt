package com.replypilot.features.scheduler

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.replypilot.core.handoff.ManualSend
import com.replypilot.domain.common.Clock
import com.replypilot.domain.model.ExecutionItem
import com.replypilot.domain.model.MessageStatus
import com.replypilot.domain.model.ScheduleState
import com.replypilot.domain.model.ScheduledMessage
import com.replypilot.domain.repository.ExecutionRepository
import com.replypilot.domain.repository.ScheduleRepository
import com.replypilot.domain.scheduling.QueueActions
import com.replypilot.domain.scheduling.RecurrenceCalculator
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ScheduleRow(
    val schedule: ScheduledMessage,
    val description: String,
    val nextRun: Long?,
    val pending: Int,
    val needsAttention: Int,
)

data class MessagesUiState(
    val loading: Boolean = true,
    val now: Long = 0,
    val zone: ZoneId = ZoneId.systemDefault(),
    val schedules: List<ScheduleRow> = emptyList(),
    val executions: List<ExecutionItem> = emptyList(),
)

@HiltViewModel
class MessagesViewModel @Inject constructor(
    schedules: ScheduleRepository,
    executions: ExecutionRepository,
    private val actions: QueueActions,
    private val clock: Clock,
    private val manualSender: ManualSend,
) : ViewModel() {
    private val ticker = flow {
        while (true) {
            emit(clock.now())
            delay(60_000)
        }
    }

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    val state: StateFlow<MessagesUiState> = combine(schedules.observeAll(), executions.observeAll(), ticker) { list, items, now ->
        val zone = clock.zone()
        val bySource = items.groupBy { it.sourceId }
        MessagesUiState(
            loading = false,
            now = now,
            zone = zone,
            schedules = list.sortedWith(compareBy<ScheduledMessage> { it.state.ordinal }.thenByDescending { it.updatedAt }).map { schedule ->
                val rows = bySource[schedule.id].orEmpty()
                val nextPending = rows.filter { it.status in MessageStatus.RUNNABLE || it.status == MessageStatus.PAUSED }.minOfOrNull { it.nextAttemptAt }
                val nextRun = nextPending ?: if (schedule.state == ScheduleState.ACTIVE) RecurrenceCalculator.next(schedule.spec, zone, now)?.at else null
                ScheduleRow(
                    schedule = schedule,
                    description = RecurrenceCalculator.describe(schedule.spec),
                    nextRun = nextRun,
                    pending = rows.count { it.status in MessageStatus.PENDING },
                    needsAttention = rows.count { it.status == MessageStatus.NEEDS_ATTENTION },
                )
            },
            executions = items,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MessagesUiState())

    fun act(id: String, action: ExecutionAction, editedBody: String? = null) = viewModelScope.launch {
        _messages.emit(
            try {
                actions.perform(id, action, editedBody)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "Couldn't ${action.label.lowercase()}: ${e.message ?: "unexpected error"}"
            },
        )
    }

    fun notify(message: String) {
        _messages.tryEmit(message)
    }

    /** "Send manually": assisted send for picked chats, classic handoff otherwise. */
    fun manualSend(context: Context, item: ExecutionItem) = viewModelScope.launch { notify(manualSender.send(context, item)) }

    /** Removes failed messages from the Outbox (their history stays in Activity). */
    fun clearFailed() = viewModelScope.launch {
        val removed = actions.clearFailed()
        notify(if (removed == 0) "No failed messages to clear" else "Cleared $removed failed message${if (removed == 1) "" else "s"}")
    }
}
