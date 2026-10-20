package com.replypilot.features.scheduler

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.replypilot.core.accessibility.AutoSendBridge
import com.replypilot.core.accessibility.ChatApp
import com.replypilot.core.assistant.ConfiguredAssistant
import com.replypilot.core.attachments.AttachmentStore
import com.replypilot.core.contacts.PhoneContacts
import com.replypilot.core.database.AppJson
import com.replypilot.core.datastore.AppPreferencesRepository
import com.replypilot.domain.assistant.AssistOperation
import com.replypilot.domain.assistant.AssistResult
import com.replypilot.domain.common.Clock
import com.replypilot.domain.messaging.AdapterRegistry
import com.replypilot.domain.model.Attachment
import com.replypilot.domain.model.AutomationSettings
import com.replypilot.domain.model.ChannelCapability
import com.replypilot.domain.model.ChannelId
import com.replypilot.domain.model.ChannelStatus
import com.replypilot.domain.model.Contact
import com.replypilot.domain.model.MessageTemplate
import com.replypilot.domain.model.Recipient
import com.replypilot.domain.model.RecipientKind
import com.replypilot.domain.repository.ContactRepository
import com.replypilot.domain.repository.ScheduleRepository
import com.replypilot.domain.repository.SettingsRepository
import com.replypilot.domain.repository.TemplateRepository
import com.replypilot.domain.scheduling.AddressRules
import com.replypilot.domain.scheduling.RecurrenceCalculator
import com.replypilot.domain.scheduling.SaveResult
import com.replypilot.domain.scheduling.ScheduleService
import com.replypilot.domain.scheduling.ScheduleValidator
import com.replypilot.domain.scheduling.Severity
import com.replypilot.domain.scheduling.ValidationIssue
import com.replypilot.domain.scheduling.hasErrors
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ComposerUiState(
    val loading: Boolean = true,
    val form: ComposerForm = ComposerForm(),
    val channels: List<ChannelStatus> = emptyList(),
    val contacts: List<Contact> = emptyList(),
    val templates: List<MessageTemplate> = emptyList(),
    val settings: AutomationSettings = AutomationSettings(),
    val issues: List<ValidationIssue> = emptyList(),
    val showErrors: Boolean = false,
    val saving: Boolean = false,
    /** Picked files are being copied into ReplyPilot. */
    val importing: Boolean = false,
    val dirty: Boolean = false,
    val upcoming: List<RecurrenceCalculator.Occurrence> = emptyList(),
    /** Names read from the messaging app after "Choose a chat", waiting for the user to confirm. */
    val pickedChats: List<String> = emptyList(),
) {
    val channelStatus: ChannelStatus? get() = channels.firstOrNull { it.id == form.channel }
    val isEditing: Boolean get() = form.editingId.isNotBlank()
}

data class AssistantUi(
    val checked: Boolean = false,
    val available: Boolean = false,
    val providerName: String? = null,
    val running: AssistOperation? = null,
    val result: AssistResult? = null,
    val error: String? = null,
)

sealed interface ComposerEvent {
    data class Message(val text: String) : ComposerEvent
    data class Saved(val text: String) : ComposerEvent
}

private const val SAVED_FORM = "composer.form"
private const val SAVED_INITIAL = "composer.initial"
private const val SNAPSHOT_DEBOUNCE_MS = 300L
private const val PICK_OWNER = "composer"

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class ComposerViewModel @Inject constructor(
    private val savedState: SavedStateHandle,
    private val schedules: ScheduleRepository,
    private val service: ScheduleService,
    private val adapters: AdapterRegistry,
    contacts: ContactRepository,
    private val templates: TemplateRepository,
    settings: SettingsRepository,
    private val preferences: AppPreferencesRepository,
    private val assistant: ConfiguredAssistant,
    private val clock: Clock,
    val phoneContacts: PhoneContacts,
    private val chatBridge: AutoSendBridge,
    private val attachmentStore: AttachmentStore,
) : ViewModel() {
    private val scheduleId = savedState.get<String>("scheduleId")?.takeIf { it.isNotBlank() }
    private val templateId = savedState.get<String>("templateId")?.takeIf { it.isNotBlank() }
    private val duplicateOf = savedState.get<String>("duplicateOf")?.takeIf { it.isNotBlank() }

    private val form = MutableStateFlow(ComposerForm())
    private val initialForm = MutableStateFlow<ComposerForm?>(null)
    private val meta = MutableStateFlow(Meta())

    private data class Meta(
        val channels: List<ChannelStatus> = emptyList(),
        val showErrors: Boolean = false,
        val saving: Boolean = false,
        val importing: Boolean = false,
        val pickedChats: List<String> = emptyList(),
    )

    private val _events = MutableSharedFlow<ComposerEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<ComposerEvent> = _events.asSharedFlow()

    private val _assistant = MutableStateFlow(AssistantUi())
    val assistantState: StateFlow<AssistantUi> = _assistant.asStateFlow()

    private val issues: StateFlow<List<ValidationIssue>> = form.debounce(350).mapLatest { current ->
        if (initialForm.value == null) emptyList() else safeValidate(current)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val state: StateFlow<ComposerUiState> = combine(
        combine(form, initialForm, meta) { f, initial, m -> Triple(f, initial, m) },
        contacts.observeAll(),
        templates.observeAll(),
        settings.settings,
        issues,
    ) { (f, initial, m), contactList, templateList, currentSettings, currentIssues ->
        ComposerUiState(
            loading = initial == null,
            form = f,
            channels = m.channels,
            contacts = contactList,
            templates = templateList,
            settings = currentSettings,
            issues = currentIssues,
            showErrors = m.showErrors,
            saving = m.saving,
            pickedChats = m.pickedChats,
            importing = m.importing,
            dirty = initial != null && initial != f,
            upcoming = if (f.sendNow) emptyList() else runCatching {
                RecurrenceCalculator.upcoming(f.spec(clock.now()), clock.zone(), clock.now(), 5)
            }.getOrDefault(emptyList()),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ComposerUiState())

    init {
        viewModelScope.launch {
            meta.update { it.copy(channels = adapters.all().map { adapter -> adapter.status() }.sortedBy { it.id.ordinal }) }
            // Rebuilt after Android destroyed the screen (e.g. while choosing chats in WhatsApp): carry on where the user was.
            restoreSnapshot()?.let { (current, initial) ->
                form.value = current
                initialForm.value = initial
                return@launch
            }
            val loaded = when {
                scheduleId != null -> schedules.get(scheduleId)?.let { ComposerForm.from(it, keepId = true) }
                duplicateOf != null -> service.duplicate(duplicateOf)?.let { ComposerForm.from(it, keepId = false) }
                else -> null
            } ?: defaultForm()
            val withTemplate = templateId?.let { templates.get(it) }?.let { loaded.copy(body = it.body, templateId = it.id) } ?: loaded
            form.value = withTemplate
            initialForm.value = withTemplate
            if (scheduleId != null && loaded.editingId.isBlank()) _events.emit(ComposerEvent.Message("That schedule no longer exists; starting a new one."))
        }
    }

    init {
        collectPicks()
        // Keep the latest form in the saved state (cheap: only on change, debounced).
        viewModelScope.launch {
            combine(form, initialForm) { f, i -> f to i }.debounce(SNAPSHOT_DEBOUNCE_MS).collect { (f, i) ->
                if (i == null) return@collect
                savedState[SAVED_FORM] = AppJson.encodeToString(ComposerSnapshot.serializer(), ComposerSnapshot.of(f))
                savedState[SAVED_INITIAL] = AppJson.encodeToString(ComposerSnapshot.serializer(), ComposerSnapshot.of(i))
            }
        }
    }

    private fun restoreSnapshot(): Pair<ComposerForm, ComposerForm>? = runCatching {
        val current = savedState.get<String>(SAVED_FORM) ?: return null
        val initial = savedState.get<String>(SAVED_INITIAL) ?: current
        AppJson.decodeFromString(ComposerSnapshot.serializer(), current).toForm() to
            AppJson.decodeFromString(ComposerSnapshot.serializer(), initial).toForm()
    }.getOrNull()

    private suspend fun defaultForm(): ComposerForm {
        val zone = clock.zone()
        val next = Instant.ofEpochMilli(clock.now()).atZone(zone).plusHours(1).truncatedTo(ChronoUnit.HOURS)
        val channel = if (preferences.current().demoMode) ChannelId.DEMO else defaultChannel()
        return ComposerForm(channel = channel, date = next.toLocalDate(), time = LocalTime.of(next.hour, 0), zoneId = zone.id)
    }

    /** Prefers a ready channel that can send unattended, then one with a manual handoff, then Demo. */
    private suspend fun defaultChannel(): ChannelId {
        val statuses = adapters.all().map { it.status() }.filter { it.availability.isUsable }
        return statuses.firstOrNull { it.descriptor.supports(ChannelCapability.SEND_MESSAGE) && !it.descriptor.isSimulated }?.id
            ?: statuses.firstOrNull { it.descriptor.supports(ChannelCapability.MANUAL_HANDOFF) && !it.descriptor.isSimulated }?.id
            ?: ChannelId.DEMO
    }

    fun edit(transform: (ComposerForm) -> ComposerForm) = form.update(transform)

    fun setChannel(channel: ChannelId) = form.update { current ->
        val contactsById = state.value.contacts.associateBy { it.id }
        val remapped = current.recipients.mapNotNull { r ->
            val contact = r.contactId?.let(contactsById::get)
            when {
                contact == null -> r
                else -> contact.addressOn(channel)?.let { r.copy(address = it) }
            }
        }
        current.copy(channel = channel, recipients = remapped)
    }

    fun toggleContact(contact: Contact) = form.update { current ->
        val existing = current.recipients.firstOrNull { it.contactId == contact.id }
        if (existing != null) {
            current.copy(recipients = current.recipients - existing)
        } else {
            val address = contact.addressOn(current.channel) ?: return@update current
            current.copy(recipients = current.recipients + Recipient(address, contact.displayName, contact.id))
        }
    }

    /** Returns an error message, or null when the address was added. */
    fun addManual(raw: String): String? {
        val channel = form.value.channel
        if (!AddressRules.isValid(channel, raw)) return AddressRules.hint(channel)
        val address = AddressRules.normalize(channel, raw)
        if (form.value.recipients.any { AddressRules.normalize(channel, it.address) == address }) return "Already added"
        form.update { it.copy(recipients = it.recipients + Recipient(address, raw.trim())) }
        return null
    }

    /** Opens the channel's app so the user can open the chat to message; ReplyPilot reads its name and comes back. */
    fun pickChat() = viewModelScope.launch { pickChatInApp() }

    private suspend fun pickChatInApp() {
        val app = ChatApp.forChannel(form.value.channel) ?: return
        if (!chatBridge.isRunning) {
            _events.emit(
                ComposerEvent.Message(
                    "To choose chats inside ${form.value.channel.displayName}, turn on ReplyPilot in Accessibility: Settings → Connected channels → WhatsApp → Turn on Auto-send.",
                ),
            )
            return
        }
        // The pick runs outside this screen so it survives Android destroying it; the result comes back via collectPicks.
        if (!chatBridge.startPick(app, PICK_OWNER)) _events.emit(ComposerEvent.Message("Finish the chat choice that's already open first."))
    }

    /** Picks finished while this screen was away (or rebuilt) are offered as soon as it's back. */
    private fun collectPicks() = viewModelScope.launch {
        chatBridge.pickResult.collect { pending ->
            if (pending?.owner != PICK_OWNER) return@collect
            val result = chatBridge.consumePickResult(PICK_OWNER) ?: return@collect
            val names = result.titles.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            if (names.isEmpty()) _events.emit(ComposerEvent.Message("No chat was chosen."))
            else meta.update { it.copy(pickedChats = names) }
        }
    }

    fun confirmPickedChats(names: List<String>) {
        meta.update { it.copy(pickedChats = emptyList()) }
        val chats = names.map { it.trim() }.filter { it.isNotEmpty() }.map { Recipient(it, it, kind = RecipientKind.CHAT) }
        addRecipients(chats)?.let { note -> viewModelScope.launch { _events.emit(ComposerEvent.Message(note)) } }
    }

    fun dismissPickedChats() = meta.update { it.copy(pickedChats = emptyList()) }

    /** Adds picked recipients, skipping duplicates and opted-out people. Returns a note for the user, if any. */
    fun addRecipients(added: List<Recipient>): String? {
        val channel = form.value.channel
        val optedOut = state.value.contacts.filter { it.optedOut }.mapNotNull { it.addressOn(channel) }
            .map { AddressRules.normalize(channel, it) }.toSet()
        // Decide everything first, then apply once (a state update may run its block more than once).
        val current = form.value.recipients
        val keys = current.map { AddressRules.recipientKey(it, channel) }.toMutableSet()
        val accepted = mutableListOf<Recipient>()
        var skippedOptOut = 0
        var skippedDuplicate = 0
        var skippedLimit = 0
        added.forEach { r ->
            when {
                !r.isNamedChat && AddressRules.normalize(channel, r.address) in optedOut -> skippedOptOut++
                !keys.add(AddressRules.recipientKey(r, channel)) -> skippedDuplicate++
                current.size + accepted.size >= ScheduleValidator.MAX_RECIPIENTS -> skippedLimit++
                else -> accepted += r
            }
        }
        form.update { it.copy(recipients = it.recipients + accepted.filter { r -> it.recipients.none { e -> AddressRules.recipientKey(e, channel) == AddressRules.recipientKey(r, channel) } }) }
        val notes = buildList {
            if (skippedOptOut > 0) add("$skippedOptOut opted out")
            if (skippedDuplicate > 0) add("$skippedDuplicate already added")
            if (skippedLimit > 0) add("$skippedLimit over the ${ScheduleValidator.MAX_RECIPIENTS}-recipient limit")
        }
        val addedCount = added.size - skippedOptOut - skippedDuplicate - skippedLimit
        return when {
            notes.isEmpty() && addedCount > 1 -> "Added $addedCount recipients"
            notes.isEmpty() -> null
            else -> "Added $addedCount; skipped ${notes.joinToString(", ")}"
        }
    }

    fun removeRecipient(recipient: Recipient) = form.update { it.copy(recipients = it.recipients - recipient) }

    fun setBody(text: String) = form.update { it.copy(body = text) }

    /** Copies the picked files into ReplyPilot (so the send never depends on the originals) and attaches them. */
    fun attachFiles(uris: List<Uri>) = viewModelScope.launch {
        meta.update { it.copy(importing = true) }
        val room = Attachment.MAX_FILES - form.value.attachments.size
        val problems = mutableListOf<String>()
        val added = buildList {
            uris.take(room.coerceAtLeast(0)).forEach { uri ->
                when (val result = attachmentStore.import(uri)) {
                    is AttachmentStore.Imported.Ok -> add(result.attachment)
                    is AttachmentStore.Imported.Rejected -> problems += result.reason
                }
            }
        }
        if (uris.size > room) problems += "Only ${Attachment.MAX_FILES} files can be attached"
        form.update { it.copy(attachments = it.attachments + added) }
        meta.update { it.copy(importing = false) }
        if (problems.isNotEmpty()) _events.emit(ComposerEvent.Message(problems.joinToString(". ") + "."))
    }

    fun removeAttachment(id: String) = form.update { f -> f.copy(attachments = f.attachments.filterNot { it.id == id }) }

    fun applyTemplate(template: MessageTemplate) =
        form.update { it.copy(body = template.body, templateId = template.id, bodyVersion = it.bodyVersion + 1) }

    fun replaceBody(text: String) = form.update { it.copy(body = text, bodyVersion = it.bodyVersion + 1) }

    fun save() = viewModelScope.launch {
        meta.update { it.copy(showErrors = true, saving = true) }
        try {
            val current = form.value
            when (val result = service.save(current.toDraft(clock.now()), current.sendNow)) {
                is SaveResult.Invalid -> {
                    val errors = result.issues.filter { it.severity == Severity.ERROR }
                    val more = if (errors.size > 1) " (+${errors.size - 1} more below)" else ""
                    _events.emit(ComposerEvent.Message("Can't save: ${errors.firstOrNull()?.message ?: "fix the highlighted problems"}$more"))
                }
                is SaveResult.Saved -> {
                    initialForm.value = form.value
                    val warnings = result.warnings.size
                    val text = when {
                        current.sendNow -> "Queued to send now"
                        current.editingId.isNotBlank() -> "Schedule updated"
                        else -> "Scheduled — ${result.queued} message${if (result.queued == 1) "" else "s"} queued"
                    } + if (warnings > 0) " ($warnings warning${if (warnings == 1) "" else "s"})" else ""
                    _events.emit(ComposerEvent.Saved(text))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _events.emit(ComposerEvent.Message("Couldn't save: ${e.message ?: "unexpected error"}"))
        } finally {
            meta.update { it.copy(saving = false) }
        }
    }

    private suspend fun safeValidate(current: ComposerForm): List<ValidationIssue> = try {
        service.validate(current.toDraft(clock.now()), current.sendNow)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        emptyList()
    }

    fun hasBlockingErrors(): Boolean = state.value.issues.hasErrors

    fun openAssistant() = viewModelScope.launch {
        val available = assistant.isAvailable()
        _assistant.value = AssistantUi(checked = true, available = available, providerName = assistant.activeName())
    }

    fun runAssistant(operation: AssistOperation) = viewModelScope.launch {
        _assistant.update { it.copy(running = operation, error = null, result = null) }
        val result = try {
            assistant.transform(form.value.body, operation)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
        _assistant.update { current ->
            result.fold(
                onSuccess = { current.copy(running = null, result = it) },
                onFailure = { current.copy(running = null, error = it.message ?: "The assistant couldn't help with that.") },
            )
        }
    }

    fun zone(): ZoneId = runCatching { ZoneId.of(form.value.zoneId) }.getOrDefault(clock.zone())
}
