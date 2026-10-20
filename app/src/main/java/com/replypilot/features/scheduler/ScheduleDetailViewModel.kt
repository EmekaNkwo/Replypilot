package com.replypilot.features.scheduler

import com.replypilot.core.handoff.ManualSend
import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.replypilot.domain.common.Clock
import com.replypilot.domain.model.ExecutionItem
import com.replypilot.domain.model.ScheduleState
import com.replypilot.domain.model.ScheduledMessage
import com.replypilot.domain.repository.ExecutionRepository
import com.replypilot.domain.repository.ScheduleRepository
import com.replypilot.domain.scheduling.QueueActions
import com.replypilot.domain.scheduling.RecurrenceCalculator
import com.replypilot.domain.scheduling.ScheduleService
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ScheduleDetailUiState(
    val loading: Boolean = true,
    val schedule: ScheduledMessage? = null,
    val description: String = "",
    val upcoming: List<RecurrenceCalculator.Occurrence> = emptyList(),
    val upcomingZone: ZoneId = ZoneId.systemDefault(),
    val executions: List<ExecutionItem> = emptyList(),
    val now: Long = 0,
)

sealed interface ScheduleEvent {
    data class Message(val text: String) : ScheduleEvent
    data object Deleted : ScheduleEvent
}

@HiltViewModel
class ScheduleDetailViewModel @Inject constructor(
    savedState: SavedStateHandle,
    schedules: ScheduleRepository,
    executions: ExecutionRepository,
    private val service: ScheduleService,
    private val actions: QueueActions,
    private val clock: Clock,
    private val manualSender: ManualSend,
) : ViewModel() {
    private val id: String = savedState.get<String>("id").orEmpty()

    private val _events = MutableSharedFlow<ScheduleEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<ScheduleEvent> = _events.asSharedFlow()

    val state: StateFlow<ScheduleDetailUiState> = combine(
        schedules.observeAll().map { list -> list.firstOrNull { it.id == id } },
        executions.observeBySource(id),
    ) { schedule, rows ->
        val now = clock.now()
        val zone = clock.zone()
        ScheduleDetailUiState(
            loading = false,
            schedule = schedule,
            description = schedule?.let { RecurrenceCalculator.describe(it.spec) }.orEmpty(),
            upcoming = schedule?.takeIf { it.state == ScheduleState.ACTIVE }
                ?.let { RecurrenceCalculator.upcoming(it.spec, zone, now, 5) }.orEmpty(),
            upcomingZone = schedule?.let { RecurrenceCalculator.zoneFor(it.spec, zone) } ?: zone,
            executions = rows.sortedByDescending { it.plannedAt },
            now = now,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ScheduleDetailUiState())

    fun pause() = run("Schedule paused") { service.pause(id) }
    fun resume() = run("Schedule resumed") { service.resume(id) }
    fun cancel() = run("Schedule cancelled") { service.cancel(id) }

    fun delete() = viewModelScope.launch {
        try {
            service.delete(id)
            _events.emit(ScheduleEvent.Deleted)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _events.emit(ScheduleEvent.Message("Couldn't delete: ${e.message ?: "unexpected error"}"))
        }
    }

    fun act(executionId: String, action: ExecutionAction, body: String?) = viewModelScope.launch {
        _events.emit(ScheduleEvent.Message(safe { actions.perform(executionId, action, body) }))
    }

    fun notify(text: String) {
        _events.tryEmit(ScheduleEvent.Message(text))
    }

    /** "Send manually": assisted send for picked chats, classic handoff otherwise. */
    fun manualSend(context: Context, item: ExecutionItem) = viewModelScope.launch { notify(manualSender.send(context, item)) }


    private fun run(done: String, block: suspend () -> Unit) = viewModelScope.launch {
        _events.emit(ScheduleEvent.Message(safe { block(); done }))
    }

    private suspend fun safe(block: suspend () -> String): String = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        "Something went wrong: ${e.message ?: "unexpected error"}"
    }
}
