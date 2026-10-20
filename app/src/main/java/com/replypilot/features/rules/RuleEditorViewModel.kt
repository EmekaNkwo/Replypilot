package com.replypilot.features.rules

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.replypilot.core.accessibility.AutoSendBridge
import com.replypilot.core.accessibility.ChatApp
import com.replypilot.core.contacts.PhoneContacts
import com.replypilot.domain.common.Clock
import com.replypilot.domain.messaging.AdapterRegistry
import com.replypilot.domain.model.AutoReplyRule
import com.replypilot.domain.model.AutomationSettings
import com.replypilot.domain.model.AvailabilityState
import com.replypilot.domain.model.ChannelCapability
import com.replypilot.domain.model.ChannelId
import com.replypilot.domain.model.ChannelIdentity
import com.replypilot.domain.model.ChannelStatus
import com.replypilot.domain.model.Contact
import com.replypilot.domain.model.MatchType
import com.replypilot.domain.model.MessageTemplate
import com.replypilot.domain.model.RuleCondition
import com.replypilot.domain.repository.ContactRepository
import com.replypilot.domain.repository.RuleRepository
import com.replypilot.domain.repository.SettingsRepository
import com.replypilot.domain.repository.TemplateRepository
import com.replypilot.domain.rules.Evaluation
import com.replypilot.domain.scheduling.AddressRules
import com.replypilot.domain.simulation.RuleSimulator
import com.replypilot.domain.simulation.SimulationScenario
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class RuleTestState(
    val text: String = "",
    val senderName: String = "Ada Okafor",
    val contactId: String? = null,
    val includeOtherRules: Boolean = false,
    val result: Evaluation? = null,
)

data class RuleEditorState(
    val loading: Boolean = true,
    val notFound: Boolean = false,
    val isNew: Boolean = true,
    val draft: AutoReplyRule = RuleEditorViewModel.blankRule(),
    val baseline: AutoReplyRule = RuleEditorViewModel.blankRule(),
    val templates: List<MessageTemplate> = emptyList(),
    val contacts: List<Contact> = emptyList(),
    val otherRules: List<AutoReplyRule> = emptyList(),
    val settings: AutomationSettings = AutomationSettings(),
    val channelStatus: Map<ChannelId, ChannelStatus> = emptyMap(),
    val showErrors: Boolean = false,
    val saving: Boolean = false,
    val test: RuleTestState = RuleTestState(),
) {
    val dirty: Boolean get() = draft != baseline
    val templateMap: Map<String, MessageTemplate> get() = templates.associateBy { it.id }
    val errors: List<String> get() = RuleValidation.errors(draft, templateMap)
    val groups: List<String> get() = contacts.flatMap { it.groups }.distinct().sorted()

    /** Channels that can deliver incoming messages to the rule engine. */
    val receivingChannels: List<ChannelId> get() = channelStatus.values
        .filter { it.descriptor.supports(ChannelCapability.RECEIVE_MESSAGE) }
        .map { it.id }.sortedBy { it.ordinal }

    fun canAutoReplyNow(channel: ChannelId): Boolean {
        val status = channelStatus[channel] ?: return false
        return status.descriptor.supports(ChannelCapability.AUTO_REPLY) && status.availability == AvailabilityState.READY
    }
}

private const val RULE_PICK_OWNER = "rule-editor"

@HiltViewModel
class RuleEditorViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val rules: RuleRepository,
    private val templates: TemplateRepository,
    private val contacts: ContactRepository,
    private val settings: SettingsRepository,
    private val adapters: AdapterRegistry,
    private val clock: Clock,
    val phoneContacts: PhoneContacts,
    private val chatBridge: AutoSendBridge,
) : ViewModel() {
    private val ruleId: String? = savedState.get<String>("id")?.takeIf { it.isNotBlank() }

    private val _state = MutableStateFlow(RuleEditorState())
    val state: StateFlow<RuleEditorState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<EditorEvent>(extraBufferCapacity = 4)

    /**
     * Opens [app] so the user can open the chats whose messages this rule should answer. Each chat is saved as a
     * contact named exactly like the chat, which is how that app's notifications name the sender.
     */
    fun pickChatContact(app: ChatApp, onSaved: (List<String>) -> Unit) = viewModelScope.launch {
        if (!chatBridge.isRunning) {
            _events.emit(EditorEvent.Message("Turn on ReplyPilot in Accessibility first: Settings → Connected channels → WhatsApp → Turn on Auto-send."))
            return@launch
        }
        if (!chatBridge.startPick(app, RULE_PICK_OWNER)) {
            _events.emit(EditorEvent.Message("Finish the chat choice that's already open first."))
            return@launch
        }
        val result = chatBridge.pickResult.filterNotNull().first { it.owner == RULE_PICK_OWNER }
        chatBridge.consumePickResult(RULE_PICK_OWNER)
        val titles = result.titles.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (titles.isEmpty()) {
            _events.emit(EditorEvent.Message("No chat was chosen."))
            return@launch
        }
        onSaved(titles.map { saveChatContact(app, it).id })
        _events.emit(EditorEvent.Message("Rule will answer ${titles.joinToString { "“$it”" }} on ${app.channel.displayName}"))
    }

    private suspend fun saveChatContact(app: ChatApp, title: String): Contact {
        val existing = contacts.findByAddress(app.channel, title) ?: contacts.findByName(title)
        val now = clock.now()
        val contact = existing ?: Contact(
            id = UUID.randomUUID().toString(),
            firstName = title.substringBefore(' '),
            lastName = title.substringAfter(' ', ""),
            identities = listOf(ChannelIdentity(app.channel, title)),
            preferredChannel = app.channel,
            notes = "Chosen from ${app.channel.displayName} chats for an auto-reply rule.",
            createdAt = now, updatedAt = now,
        )
        if (existing == null) contacts.upsert(contact)
        return contact
    }

    /**
     * Saves a contact picked from the phone so rules can recognise them. Reuses an existing contact with the
     * same number; a phone number is added for WhatsApp and SMS, the channels that notify with it.
     */
    fun savePhoneContact(uri: Uri, onSaved: (String) -> Unit) = viewModelScope.launch {
        val picked = withContext(Dispatchers.IO) { phoneContacts.readPicked(uri) }
        val number = picked?.phone?.let(phoneContacts::toE164)
        if (picked == null || number == null || !AddressRules.isValid(ChannelId.WHATSAPP, number)) {
            _events.emit(EditorEvent.Message("That contact has no usable phone number."))
            return@launch
        }
        val existing = contacts.findByAddress(ChannelId.WHATSAPP, number) ?: contacts.findByAddress(ChannelId.SMS, number)
        val now = clock.now()
        val contact = existing ?: Contact(
            id = UUID.randomUUID().toString(),
            firstName = picked.name.substringBefore(' ').ifBlank { number },
            lastName = picked.name.substringAfter(' ', ""),
            identities = listOf(ChannelIdentity(ChannelId.WHATSAPP, number), ChannelIdentity(ChannelId.SMS, number)),
            preferredChannel = ChannelId.WHATSAPP,
            notes = "Added from phone contacts for an auto-reply rule.",
            createdAt = now, updatedAt = now,
        )
        if (existing == null) contacts.upsert(contact)
        onSaved(contact.id)
        _events.emit(EditorEvent.Message(if (existing == null) "Saved ${contact.displayName} to ReplyPilot contacts" else "Using saved contact ${contact.displayName}"))
    }
    val events: SharedFlow<EditorEvent> = _events.asSharedFlow()

    sealed interface EditorEvent {
        data object Saved : EditorEvent
        data class Message(val text: String) : EditorEvent
    }

    init {
        viewModelScope.launch {
            val existing = ruleId?.let { rules.get(it) }
            val statuses = adapters.all().associate { it.id to it.status() }
            _state.update {
                it.copy(
                    loading = false,
                    notFound = ruleId != null && existing == null,
                    isNew = existing == null,
                    draft = existing ?: it.draft,
                    baseline = existing ?: it.baseline,
                    channelStatus = statuses,
                )
            }
        }
        viewModelScope.launch { templates.observeAll().collect { list -> _state.update { it.copy(templates = list) } } }
        viewModelScope.launch { contacts.observeAll().collect { list -> _state.update { it.copy(contacts = list) } } }
        viewModelScope.launch { rules.observeAll().collect { list -> _state.update { it.copy(otherRules = list.filter { r -> r.id != ruleId }) } } }
        viewModelScope.launch { settings.settings.collect { s -> _state.update { it.copy(settings = s) } } }
    }

    fun update(transform: (AutoReplyRule) -> AutoReplyRule) = _state.update { it.copy(draft = transform(it.draft)) }

    fun toggleChannel(channel: ChannelId) = update { r ->
        r.copy(channels = if (channel in r.channels) r.channels - channel else r.channels + channel)
    }

    fun addCondition(kind: ConditionKind) = update { it.copy(conditions = it.conditions + kind.create()) }

    fun updateCondition(index: Int, condition: RuleCondition) = update { r ->
        r.copy(conditions = r.conditions.mapIndexed { i, c -> if (i == index) condition else c })
    }

    fun removeCondition(index: Int) = update { r -> r.copy(conditions = r.conditions.filterIndexed { i, _ -> i != index }) }

    fun updateTest(transform: (RuleTestState) -> RuleTestState) = _state.update { it.copy(test = transform(it.test)) }

    /** Runs the production engine on the unsaved draft. Never sends anything. */
    fun runTest() {
        val s = _state.value
        if (s.test.text.isBlank()) return
        val draft = s.draft.copy(id = s.draft.id.ifBlank { DRAFT_ID }, name = s.draft.name.ifBlank { "This rule" }, enabled = true)
        val channel = draft.channels.firstOrNull() ?: ChannelId.DEMO
        val result = RuleSimulator.simulate(
            scenario = SimulationScenario(
                text = s.test.text,
                channel = channel,
                senderName = s.test.contactId?.let { id -> s.contacts.firstOrNull { it.id == id }?.displayName } ?: s.test.senderName.ifBlank { "Test sender" },
                contactId = s.test.contactId,
                at = clock.now(),
                zone = clock.zone(),
                channelCanAutoReply = channel == ChannelId.DEMO || s.canAutoReplyNow(channel),
            ),
            rules = if (s.test.includeOtherRules) s.otherRules + draft else listOf(draft),
            contacts = s.contacts,
            settings = s.settings,
            templates = s.templates,
        )
        updateTest { it.copy(result = result) }
    }

    fun save() {
        val s = _state.value
        if (s.errors.isNotEmpty()) {
            _state.update { it.copy(showErrors = true) }
            viewModelScope.launch { _events.emit(EditorEvent.Message("Fix the highlighted problems before saving.")) }
            return
        }
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            try {
                val now = clock.now()
                val rule = s.draft.copy(
                    id = s.draft.id.ifBlank { UUID.randomUUID().toString() },
                    name = s.draft.name.trim(),
                    response = s.draft.response.trim(),
                    createdAt = if (s.isNew) now else s.draft.createdAt,
                    updatedAt = now,
                )
                rules.upsert(rule)
                _state.update { it.copy(saving = false, draft = rule, baseline = rule, isNew = false) }
                _events.emit(EditorEvent.Saved)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(saving = false) }
                _events.emit(EditorEvent.Message("Couldn't save the rule. Please try again."))
            }
        }
    }

    companion object {
        private const val DRAFT_ID = "draft"

        fun blankRule() = AutoReplyRule(
            id = "",
            name = "",
            conditions = listOf(RuleCondition.MessageMatches(MatchType.CONTAINS, emptyList())),
            response = "",
        )
    }
}
