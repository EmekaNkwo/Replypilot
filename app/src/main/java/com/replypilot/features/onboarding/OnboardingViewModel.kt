package com.replypilot.features.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.replypilot.core.datastore.AppPreferencesRepository
import com.replypilot.core.permissions.CapabilityState
import com.replypilot.core.permissions.PlatformStatus
import com.replypilot.data.services.DemoSeeder
import com.replypilot.data.services.StarterContent
import com.replypilot.domain.common.Clock
import com.replypilot.domain.common.IdGenerator
import com.replypilot.domain.common.MINUTE_MS
import com.replypilot.domain.messaging.AdapterRegistry
import com.replypilot.domain.model.AutoReplyRule
import com.replypilot.domain.model.AvailabilityState
import com.replypilot.domain.model.ChannelId
import com.replypilot.domain.model.MatchType
import com.replypilot.domain.model.Recipient
import com.replypilot.domain.model.RuleCondition
import com.replypilot.domain.model.ScheduleSpec
import com.replypilot.domain.model.ScheduledMessage
import com.replypilot.domain.repository.RuleRepository
import com.replypilot.domain.scheduling.SaveResult
import com.replypilot.domain.scheduling.ScheduleService
import com.replypilot.domain.scheduling.Severity
import com.replypilot.features.settings.DEVICE_CHANNELS
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class OnboardingStep(val title: String) {
    WELCOME("What ReplyPilot does"),
    CHANNELS("Choose channels"),
    PERMISSIONS("Permissions"),
    BACKGROUND("Background work"),
    TRY_IT("Try it safely"),
    FIRST_RULE("Your first auto-reply"),
    PRIVACY("Privacy & limits"),
}

data class ChannelChoice(val channel: ChannelId, val installed: Boolean, val enabled: Boolean)

data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.WELCOME,
    val channels: List<ChannelChoice> = emptyList(),
    val capabilities: List<CapabilityState> = emptyList(),
    val demoLoaded: Boolean = false,
    val testResult: String? = null,
    val ruleEnabled: Boolean = false,
    val ruleCreated: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
) {
    val anyDeviceChannel: Boolean get() = channels.any { it.enabled && it.channel in DEVICE_CHANNELS }
}

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val preferences: AppPreferencesRepository,
    private val platform: PlatformStatus,
    private val adapters: AdapterRegistry,
    private val seeder: DemoSeeder,
    private val schedules: ScheduleService,
    private val rules: RuleRepository,
    private val clock: Clock,
    private val ids: IdGenerator,
) : ViewModel() {
    private val _state = MutableStateFlow(OnboardingUiState())
    val state: StateFlow<OnboardingUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() = viewModelScope.launch {
        val enabled = preferences.current().enabledChannels
        val choices = DEVICE_CHANNELS.sortedBy { it.ordinal }.map { channel ->
            val installed = adapters.get(channel)?.status()?.availability != AvailabilityState.APP_NOT_INSTALLED
            ChannelChoice(channel, installed, channel in enabled)
        }
        _state.update { it.copy(channels = choices, capabilities = platform.states()) }
    }

    fun next() = _state.update { s -> s.copy(step = OnboardingStep.entries.getOrElse(s.step.ordinal + 1) { s.step }, error = null) }
    fun back() = _state.update { s -> s.copy(step = OnboardingStep.entries.getOrElse(s.step.ordinal - 1) { s.step }, error = null) }

    fun setChannel(channel: ChannelId, enabled: Boolean) = viewModelScope.launch {
        preferences.setChannelEnabled(channel, enabled)
        refresh()
    }

    fun loadDemo() = guarded {
        seeder.seed()
        _state.update { it.copy(demoLoaded = true) }
    }

    /** One-off message on the simulated Demo channel, two minutes from now. Nothing real is sent. */
    fun scheduleTest() = guarded {
        val zone = clock.zone()
        val at = Instant.ofEpochMilli(clock.now() + 2 * MINUTE_MS).atZone(zone)
        val draft = ScheduledMessage(
            id = "", title = "Test message (demo)", channel = ChannelId.DEMO,
            recipients = listOf(Recipient("Test recipient", "Test recipient")),
            body = "This is a ReplyPilot test message.",
            spec = ScheduleSpec(at.toLocalDate().toString(), "%02d:%02d".format(at.hour, at.minute), zone.id),
            isDemo = true,
        )
        val message = when (val result = schedules.save(draft)) {
            is SaveResult.Saved -> "Scheduled for ${"%02d:%02d".format(at.hour, at.minute)} on the Demo channel. Check Messages → Outbox, then Insights → Activity to see it run (labelled Simulated)."
            is SaveResult.Invalid -> "Couldn't schedule: " + result.issues.filter { it.severity == Severity.ERROR }.joinToString { it.message }
        }
        _state.update { it.copy(testResult = message) }
    }

    fun setRuleEnabled(value: Boolean) = _state.update { it.copy(ruleEnabled = value) }

    fun createAwayRule() = guarded {
        val s = _state.value
        val channels = s.channels.filter { it.enabled }.map { it.channel }.toSet().ifEmpty { setOf(ChannelId.DEMO) }
        val away = StarterContent.templates(clock.now()).first { it.id == "starter-away" }
        rules.upsert(
            AutoReplyRule(
                id = ids.next(),
                name = "After-hours away message",
                enabled = s.ruleEnabled,
                channels = channels,
                conditions = listOf(RuleCondition.MessageMatches(MatchType.ANY_MESSAGE, emptyList()), RuleCondition.OutsideWorkingHours),
                response = away.body,
                cooldownMinutes = 12 * 60,
                createdAt = clock.now(),
                updatedAt = clock.now(),
            ),
        )
        _state.update { it.copy(ruleCreated = true) }
    }

    fun finish(onDone: () -> Unit) = viewModelScope.launch {
        preferences.setOnboardingComplete(true)
        onDone()
    }

    private fun guarded(block: suspend () -> Unit) = viewModelScope.launch {
        _state.update { it.copy(busy = true, error = null) }
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(error = e.message ?: "Something went wrong") }
        } finally {
            _state.update { it.copy(busy = false) }
        }
    }
}
