package com.replypilot.features.autoresponder

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.replypilot.domain.automation.IncomingMessageHandler
import com.replypilot.domain.common.Clock
import com.replypilot.domain.messaging.AdapterRegistry
import com.replypilot.domain.model.AutoReplyRule
import com.replypilot.domain.model.AutomationSettings
import com.replypilot.domain.model.ChannelCapability
import com.replypilot.domain.model.ChannelId
import com.replypilot.domain.model.Contact
import com.replypilot.domain.model.IncomingMessage
import com.replypilot.domain.model.MessageTemplate
import com.replypilot.domain.repository.ContactRepository
import com.replypilot.domain.repository.RuleRepository
import com.replypilot.domain.repository.SettingsRepository
import com.replypilot.domain.repository.TemplateRepository
import com.replypilot.domain.rules.Evaluation
import com.replypilot.domain.scheduling.ProcessReport
import com.replypilot.domain.scheduling.QueueProcessor
import com.replypilot.domain.simulation.QueueScenario
import com.replypilot.domain.simulation.QueueSimulationResult
import com.replypilot.domain.simulation.QueueSimulator
import com.replypilot.domain.simulation.RuleSimulator
import com.replypilot.domain.simulation.SimulationScenario
import com.replypilot.integrations.demo.DemoControls
import com.replypilot.integrations.demo.DemoOutcome
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Every input of a rule simulation. Identical forms always give identical results. */
data class ScenarioForm(
    val text: String = "Hi, how much is your service?",
    val channel: ChannelId = ChannelId.DEMO,
    val contactId: String? = null,
    val senderName: String = "Test sender",
    val isGroup: Boolean = false,
    val date: LocalDate = LocalDate.now(),
    val time: LocalTime = LocalTime.now().withSecond(0).withNano(0),
    val repliedRecently: Boolean = false,
    val minutesSinceLastReply: Int = 10,
    val recentAutoReplies: Int = 0,
    val takeoverActive: Boolean = false,
    val contactOptedOut: Boolean = false,
    val duplicate: Boolean = false,
    val fromSelf: Boolean = false,
    val channelCanAutoReply: Boolean = true,
    val sentToday: Int = 0,
    val onlyFocusedRule: Boolean = true,
)

data class DemoRun(val evaluation: Evaluation, val report: ProcessReport?)

data class SimulatorState(
    val focusedRule: AutoReplyRule? = null,
    val rules: List<AutoReplyRule> = emptyList(),
    val contacts: List<Contact> = emptyList(),
    val templates: List<MessageTemplate> = emptyList(),
    val settings: AutomationSettings = AutomationSettings(),
    val receivingChannels: List<ChannelId> = listOf(ChannelId.DEMO),
    val form: ScenarioForm = ScenarioForm(),
    val ruleResult: Evaluation? = null,
    val queueResults: Map<QueueScenario, QueueSimulationResult> = emptyMap(),
    val runningScenario: QueueScenario? = null,
    val demoText: String = "How much do your sessions cost?",
    val demoSender: String = "Ada Okafor",
    val demoOutcome: DemoOutcome = DemoOutcome.SUCCEED,
    val demoRunning: Boolean = false,
    val demoRun: DemoRun? = null,
)

@HiltViewModel
class SimulatorViewModel @Inject constructor(
    savedState: SavedStateHandle,
    rules: RuleRepository,
    contacts: ContactRepository,
    templates: TemplateRepository,
    settings: SettingsRepository,
    private val adapters: AdapterRegistry,
    private val handler: IncomingMessageHandler,
    private val processor: QueueProcessor,
    private val demoControls: DemoControls,
    private val clock: Clock,
) : ViewModel() {
    private val focusedRuleId: String? = savedState.get<String>("ruleId")?.takeIf { it.isNotBlank() }

    private val _state = MutableStateFlow(SimulatorState())
    val state: StateFlow<SimulatorState> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    init {
        viewModelScope.launch {
            rules.observeAll().collect { list ->
                val focused = focusedRuleId?.let { id -> list.firstOrNull { it.id == id } }
                _state.update { s ->
                    s.copy(
                        rules = list,
                        focusedRule = focused,
                        form = if (s.focusedRule == null && focused != null) s.form.copy(channel = focused.channels.firstOrNull() ?: s.form.channel) else s.form,
                    )
                }
            }
        }
        viewModelScope.launch { contacts.observeAll().collect { list -> _state.update { it.copy(contacts = list) } } }
        viewModelScope.launch { templates.observeAll().collect { list -> _state.update { it.copy(templates = list) } } }
        viewModelScope.launch { settings.settings.collect { s -> _state.update { it.copy(settings = s) } } }
        viewModelScope.launch { demoControls.nextOutcome.collect { o -> _state.update { it.copy(demoOutcome = o) } } }
        viewModelScope.launch {
            val receiving = adapters.all().filter { it.descriptor.supports(ChannelCapability.RECEIVE_MESSAGE) }.map { it.id }.sortedBy { it.ordinal }
            _state.update { it.copy(receivingChannels = receiving.ifEmpty { listOf(ChannelId.DEMO) }) }
        }
    }

    fun updateForm(transform: (ScenarioForm) -> ScenarioForm) = _state.update { it.copy(form = transform(it.form)) }

    fun resetTime() = updateForm { it.copy(date = LocalDate.now(), time = LocalTime.now().withSecond(0).withNano(0)) }

    /** Pure evaluation by the production rule engine; has no access to any channel. */
    fun runRuleTest() {
        val s = _state.value
        val f = s.form
        val contact = f.contactId?.let { id -> s.contacts.firstOrNull { it.id == id } }
        val at = LocalDateTime.of(f.date, f.time).atZone(clock.zone()).toInstant().toEpochMilli()
        val rules = if (f.onlyFocusedRule && s.focusedRule != null) listOf(s.focusedRule.copy(enabled = true)) else s.rules
        val scenario = SimulationScenario(
            text = f.text,
            channel = f.channel,
            senderName = contact?.displayName ?: f.senderName.ifBlank { "Test sender" },
            contactId = contact?.id,
            isGroup = f.isGroup,
            at = at,
            zone = clock.zone(),
            minutesSinceLastReply = f.minutesSinceLastReply.takeIf { f.repliedRecently },
            recentAutoReplies = f.recentAutoReplies,
            takeoverActive = f.takeoverActive,
            contactOptedOut = f.contactOptedOut,
            duplicate = f.duplicate,
            fromSelf = f.fromSelf,
            channelCanAutoReply = f.channelCanAutoReply,
            sentToday = f.sentToday,
        )
        _state.update { it.copy(ruleResult = RuleSimulator.simulate(scenario, rules, s.contacts, s.settings, s.templates)) }
    }

    fun runQueueScenario(scenario: QueueScenario) {
        if (_state.value.runningScenario != null) return
        _state.update { it.copy(runningScenario = scenario) }
        viewModelScope.launch {
            try {
                val result = QueueSimulator.run(scenario)
                _state.update { it.copy(queueResults = it.queueResults + (scenario to result)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.emit("The simulation couldn't run: ${e.message ?: "unknown error"}")
            } finally {
                _state.update { it.copy(runningScenario = null) }
            }
        }
    }

    fun updateDemo(text: String? = null, sender: String? = null) = _state.update {
        it.copy(demoText = text ?: it.demoText, demoSender = sender ?: it.demoSender)
    }

    fun setDemoOutcome(outcome: DemoOutcome) = demoControls.set(outcome)

    /**
     * Sends a simulated message on the Demo channel through the real pipeline (rule engine, safeguards, queue,
     * history). The Demo adapter never contacts anyone and every record is marked simulated.
     */
    fun runDemoPipeline() {
        val s = _state.value
        if (s.demoText.isBlank() || s.demoRunning) return
        _state.update { it.copy(demoRunning = true) }
        viewModelScope.launch {
            try {
                val sender = s.demoSender.ifBlank { "Demo sender" }
                val evaluation = handler.handle(
                    IncomingMessage(
                        id = UUID.randomUUID().toString(),
                        channel = ChannelId.DEMO,
                        conversationKey = sender,
                        senderName = sender,
                        text = s.demoText,
                        receivedAt = clock.now(),
                        simulated = true,
                    ),
                )
                val report = if (evaluation.wouldSend) processor.process() else null
                _state.update { it.copy(demoRun = DemoRun(evaluation, report)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.emit("The demo run failed: ${e.message ?: "unknown error"}")
            } finally {
                _state.update { it.copy(demoRunning = false) }
            }
        }
    }
}
