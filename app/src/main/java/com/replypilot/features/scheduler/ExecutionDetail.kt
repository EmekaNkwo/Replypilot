package com.replypilot.features.scheduler

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.replypilot.core.design.BackTopBar
import com.replypilot.core.design.EmptyState
import com.replypilot.core.design.Format
import com.replypilot.core.design.ListCard
import com.replypilot.core.design.Notice
import com.replypilot.core.design.NoticeTone
import com.replypilot.core.design.ResultPill
import com.replypilot.core.design.SectionHeader
import com.replypilot.core.design.SimulatedPill
import com.replypilot.core.design.Spacing
import com.replypilot.core.design.StatusPill
import com.replypilot.core.handoff.ManualSend
import com.replypilot.domain.common.Clock
import com.replypilot.domain.model.ActivityEvent
import com.replypilot.domain.model.ExecutionItem
import com.replypilot.domain.model.ExecutionSource
import com.replypilot.domain.model.FailureCode
import com.replypilot.domain.model.MessageStatus
import com.replypilot.domain.repository.ActivityRepository
import com.replypilot.domain.repository.ExecutionRepository
import com.replypilot.domain.scheduling.QueueActions
import com.replypilot.navigation.AppNavigator
import com.replypilot.navigation.Routes
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

data class ExecutionDetailUiState(
    val loading: Boolean = true,
    val item: ExecutionItem? = null,
    val history: List<ActivityEvent> = emptyList(),
    val now: Long = 0,
)

@HiltViewModel
class ExecutionDetailViewModel @Inject constructor(
    savedState: SavedStateHandle,
    executions: ExecutionRepository,
    activity: ActivityRepository,
    private val actions: QueueActions,
    private val clock: Clock,
    private val manualSender: ManualSend,
) : ViewModel() {
    private val id: String = savedState.get<String>("id").orEmpty()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    val state: StateFlow<ExecutionDetailUiState> = combine(executions.observeAll(), activity.observeRecent(HISTORY_SCAN)) { items, events ->
        ExecutionDetailUiState(
            loading = false,
            item = items.firstOrNull { it.id == id },
            history = events.filter { it.executionId == id }.sortedBy { it.at },
            now = clock.now(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ExecutionDetailUiState())

    fun act(action: ExecutionAction, body: String?) = viewModelScope.launch {
        _messages.emit(
            try {
                actions.perform(id, action, body)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "Couldn't ${action.label.lowercase()}: ${e.message ?: "unexpected error"}"
            },
        )
    }

    fun notify(text: String) {
        _messages.tryEmit(text)
    }

    /** "Send manually": assisted send for picked chats, classic handoff otherwise. */
    fun manualSend(context: Context, item: ExecutionItem) = viewModelScope.launch { notify(manualSender.send(context, item)) }


    private companion object {
        const val HISTORY_SCAN = 1_000
    }
}

/** Plain-language explanation of a row's state and what the user can do about it. */
fun diagnosis(item: ExecutionItem): Pair<String, String>? = when {
    item.status == MessageStatus.AWAITING_REVIEW -> "Waiting for your approval" to
        "Nothing is sent until you approve it. You can edit the text first, or reject it."
    item.status == MessageStatus.NEEDS_ATTENTION && item.failureCode == FailureCode.INTERRUPTED -> "Outcome unknown" to
        "ReplyPilot stopped while sending this. To avoid duplicates it wasn't retried automatically. Check the conversation; mark it sent or retry."
    item.status == MessageStatus.NEEDS_ATTENTION && item.failureCode == FailureCode.UNRESOLVED_VARIABLES -> "Missing personalization" to
        "The message still contains placeholders with no value, so it was held back. Add the value to the contact, then edit and save the schedule so the message is rebuilt; retrying alone won't fill it in."
    item.status == MessageStatus.NEEDS_ATTENTION && item.handoff != null -> "Send it yourself" to
        "${item.channel.displayName} can't send this unattended. Tap \"Send manually\" to open it with your message, then mark it as sent."
    item.status == MessageStatus.NEEDS_ATTENTION -> "Needs attention" to (item.statusReason ?: "Review this message.")
    item.status == MessageStatus.RETRY_SCHEDULED -> "Retrying automatically" to
        "The last attempt failed with a temporary error. ReplyPilot retries with increasing gaps, up to ${item.maxAttempts - 1} times."
    item.status == MessageStatus.FAILED -> "Failed" to when (item.failureCode) {
        FailureCode.NOT_CONFIGURED -> "Set up the channel in Settings → Connected channels, then retry."
        FailureCode.PERMISSION_MISSING -> "Grant the missing permission in Settings → Permissions, then retry."
        FailureCode.INVALID_RECIPIENT -> "Fix the recipient address in the schedule, then retry."
        FailureCode.NO_REPLY_TARGET -> "The notification it was replying to is gone. Reply in the app yourself."
        FailureCode.PROVIDER_REJECTED -> "The channel refused this message. Check its content and recipient before retrying."
        else -> "Retry once the problem is fixed. Permanent errors are not retried automatically."
    }
    item.status == MessageStatus.SKIPPED -> "Skipped" to (item.statusReason ?: "A safeguard stopped this message.")
    item.status == MessageStatus.EXPIRED -> "Expired" to "It couldn't be sent before its expiry time, so it was dropped rather than sent late."
    item.status == MessageStatus.PAUSED -> "Paused" to "Resume its schedule (or global automation) to send it."
    else -> null
}

@Composable
fun ExecutionDetailScreen(nav: AppNavigator, id: String) {
    val viewModel: ExecutionDetailViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    LaunchedEffect(viewModel) { viewModel.messages.collect { snackbar.showSnackbar(it) } }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = { BackTopBar("Message details", onBack = nav::back) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val item = state.item
        if (item == null) {
            if (!state.loading) EmptyState(Icons.Filled.SearchOff, "Message not found", "It may have been removed.", action = { TextButton(onClick = nav::back) { Text("Go back") } })
            return@Scaffold
        }
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    StatusPill(item.status)
                    if (item.simulated) SimulatedPill(text = "Simulated — not really sent")
                }
            }
            diagnosis(item)?.let { (title, body) ->
                item {
                    val tone = when (item.status) {
                        MessageStatus.FAILED -> NoticeTone.ERROR
                        MessageStatus.NEEDS_ATTENTION, MessageStatus.AWAITING_REVIEW -> NoticeTone.WARNING
                        else -> NoticeTone.INFO
                    }
                    Notice(body, title = title, tone = tone)
                }
            }
            item {
                ExecutionActionButtons(
                    item,
                    onAction = { action, body -> viewModel.act(action, body) },
                    onHandoff = { viewModel.manualSend(context, item) },
                )
            }
            item { SectionHeader("Message") }
            item { ListCard(onClick = null) { Text(item.body, style = MaterialTheme.typography.bodyMedium) } }
            item { SectionHeader("Details") }
            item {
                ListCard(onClick = if (item.source == ExecutionSource.SCHEDULED) ({ nav.navigate(Routes.schedule(item.sourceId)) }) else null) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Field("Source", sourceLabel(item) + if (item.source == ExecutionSource.SCHEDULED) " (tap to open schedule)" else "")
                        Field("Channel", item.channel.displayName)
                        Field("Recipient", "${item.recipient.displayName} · ${item.recipient.address}")
                        if (item.attachments.isNotEmpty()) {
                            Field("Files", item.attachments.joinToString { "${it.fileName} (${formatSize(it.sizeBytes)})" })
                        }
                        Field("Planned", Format.dateTime(item.plannedAt))
                        if (item.status in MessageStatus.RUNNABLE) Field("Next attempt", "${Format.dateTime(item.nextAttemptAt)} (${Format.relative(item.nextAttemptAt, state.now)})")
                        item.lastAttemptAt?.let { Field("Last attempt", Format.dateTime(it)) }
                        Field("Attempts", "${item.attempts} of ${item.maxAttempts}")
                        item.expiresAt?.let { Field("Expires", Format.dateTime(it)) }
                        Field("Quiet hours", item.quietHours.label)
                        item.failureCode?.let { Field("Error", "${it.label}${if (it.permanent) " (permanent)" else " (temporary)"}") }
                        item.statusReason?.let { Field("Reason", it) }
                        item.providerRef?.let { Field("Provider reference", it) }
                    }
                }
            }
            item {
                Text(
                    "Submitted means the channel accepted the message. Delivered only appears when the channel confirms delivery; some channels never report it.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item { SectionHeader("Timeline") }
            if (state.history.isEmpty()) {
                item { Text("No recorded events yet.", style = MaterialTheme.typography.bodySmall) }
            }
            items(state.history, key = { it.id }) { event ->
                Row {
                    Column(Modifier.weight(1f)) {
                        Text(event.type.label, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            Format.dateTime(event.at) + (event.reason?.let { " · $it" } ?: ""),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(Spacing.sm))
                    ResultPill(event.result)
                }
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String) {
    Row {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(120.dp))
        Text(value, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
    }
}
