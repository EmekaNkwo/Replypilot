package com.replypilot.features.sequences

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.replypilot.domain.automation.EnrollResult
import com.replypilot.domain.automation.SequenceService
import com.replypilot.domain.common.Clock
import com.replypilot.domain.common.IdGenerator
import com.replypilot.domain.messaging.AdapterRegistry
import com.replypilot.domain.model.ChannelCapability
import com.replypilot.domain.model.ChannelDescriptor
import com.replypilot.domain.model.ChannelId
import com.replypilot.domain.model.Contact
import com.replypilot.domain.model.EnrollmentStatus
import com.replypilot.domain.model.ExecutionItem
import com.replypilot.domain.model.ExecutionSource
import com.replypilot.domain.model.MessageSequence
import com.replypilot.domain.model.MessageTemplate
import com.replypilot.domain.model.QuietHoursBehavior
import com.replypilot.domain.model.Recipient
import com.replypilot.domain.model.SequenceEnrollment
import com.replypilot.domain.model.SequenceStep
import com.replypilot.domain.repository.ContactRepository
import com.replypilot.domain.repository.ExecutionRepository
import com.replypilot.domain.repository.SequenceRepository
import com.replypilot.domain.repository.TemplateRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SequenceListItem(val sequence: MessageSequence, val activeEnrollments: Int)

data class SequencesState(val loaded: Boolean = false, val items: List<SequenceListItem> = emptyList())

@HiltViewModel
class SequencesViewModel @Inject constructor(
    private val sequences: SequenceRepository,
    private val clock: Clock,
) : ViewModel() {
    val state: StateFlow<SequencesState> = combine(sequences.observeSequences(), sequences.observeEnrollments()) { list, enrollments ->
        val active = enrollments.filter { it.status == EnrollmentStatus.ACTIVE }.groupingBy { it.sequenceId }.eachCount()
        SequencesState(true, list.map { SequenceListItem(it, active[it.id] ?: 0) })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SequencesState())

    fun setEnabled(sequence: MessageSequence, enabled: Boolean) {
        viewModelScope.launch { sequences.upsertSequence(sequence.copy(enabled = enabled, updatedAt = clock.now())) }
    }
}

data class SequenceEditorState(
    val loading: Boolean = true,
    val isNew: Boolean = true,
    val id: String = "",
    val name: String = "",
    val channel: ChannelId = ChannelId.DEMO,
    val timeZone: String = ZoneId.systemDefault().id,
    val steps: List<SequenceStep> = listOf(SequenceStep(0, "10:00", "")),
    val stopOnReply: Boolean = true,
    val quietHours: QuietHoursBehavior = QuietHoursBehavior.DEFER,
    val enabled: Boolean = true,
    val createdAt: Long = 0,
    val channels: List<ChannelDescriptor> = emptyList(),
    val templates: List<MessageTemplate> = emptyList(),
    val customFields: Set<String> = emptySet(),
    val errors: List<String> = emptyList(),
    val saving: Boolean = false,
    val finished: Boolean = false,
    val notFound: Boolean = false,
    val message: String? = null,
) {
    val selectedDescriptor: ChannelDescriptor? get() = channels.firstOrNull { it.id == channel }
    val canSendUnattended: Boolean get() = selectedDescriptor?.supports(ChannelCapability.SEND_MESSAGE) == true
    val timeZoneValid: Boolean get() = runCatching { ZoneId.of(timeZone) }.isSuccess
}

@HiltViewModel
class SequenceEditorViewModel @Inject constructor(
    private val sequences: SequenceRepository,
    private val service: SequenceService,
    private val templates: TemplateRepository,
    private val contacts: ContactRepository,
    adapters: AdapterRegistry,
    private val ids: IdGenerator,
    private val clock: Clock,
) : ViewModel() {
    private val _state = MutableStateFlow(
        SequenceEditorState(
            channels = adapters.all().map { it.descriptor }
                .filter { it.supports(ChannelCapability.SEND_MESSAGE) || it.supports(ChannelCapability.MANUAL_HANDOFF) }
                .sortedBy { it.id.ordinal },
        ),
    )
    val state: StateFlow<SequenceEditorState> = _state.asStateFlow()
    private var loaded = false

    fun load(id: String?) {
        if (loaded) return
        loaded = true
        viewModelScope.launch {
            val allTemplates = templates.all().sortedBy { it.name.lowercase() }
            val fields = contacts.all().flatMap { it.customFields.keys }.toSortedSet()
            val existing = id?.let { sequences.getSequence(it) }
            _state.update { current ->
                val base = current.copy(loading = false, templates = allTemplates, customFields = fields)
                when {
                    id == null -> base
                    existing == null -> base.copy(notFound = true)
                    else -> base.copy(
                        isNew = false, id = existing.id, name = existing.name, channel = existing.channel,
                        timeZone = existing.timeZone, steps = existing.steps, stopOnReply = existing.stopOnReply,
                        quietHours = existing.quietHours, enabled = existing.enabled, createdAt = existing.createdAt,
                    )
                }
            }
        }
    }

    fun setName(value: String) = _state.update { it.copy(name = value, errors = emptyList()) }
    fun setChannel(value: ChannelId) = _state.update { it.copy(channel = value) }
    fun setTimeZone(value: String) = _state.update { it.copy(timeZone = value) }
    fun setStopOnReply(value: Boolean) = _state.update { it.copy(stopOnReply = value) }
    fun setQuietHours(value: QuietHoursBehavior) = _state.update { it.copy(quietHours = value) }

    fun updateStep(index: Int, transform: (SequenceStep) -> SequenceStep) = _state.update { s ->
        s.copy(steps = s.steps.mapIndexed { i, step -> if (i == index) transform(step) else step }, errors = emptyList())
    }

    fun applyTemplate(index: Int, template: MessageTemplate) = updateStep(index) { it.copy(body = template.body, templateId = template.id) }

    fun insertVariable(index: Int, name: String) = updateStep(index) {
        val sep = if (it.body.isEmpty() || it.body.endsWith(" ")) "" else " "
        it.copy(body = it.body + sep + "{{$name}}")
    }

    fun addStep() = _state.update { s ->
        if (s.steps.size >= MessageSequence.MAX_STEPS) return@update s
        val last = s.steps.lastOrNull()
        s.copy(steps = s.steps + SequenceStep((last?.dayOffset ?: -2) + 2, last?.time ?: "10:00", ""))
    }

    fun removeStep(index: Int) = _state.update { s -> s.copy(steps = s.steps.filterIndexed { i, _ -> i != index }) }

    /** Swaps a step with its neighbour, keeping day offsets in order by swapping them too. */
    fun moveStep(index: Int, delta: Int) = _state.update { s ->
        val target = index + delta
        if (target !in s.steps.indices) return@update s
        val a = s.steps[index]
        val b = s.steps[target]
        val swapped = s.steps.toMutableList().apply {
            this[index] = b.copy(dayOffset = a.dayOffset, time = a.time)
            this[target] = a.copy(dayOffset = b.dayOffset, time = b.time)
        }
        s.copy(steps = swapped.toList())
    }

    fun save() {
        val s = _state.value
        val now = clock.now()
        val sequence = MessageSequence(
            id = s.id.ifBlank { ids.next() },
            name = s.name.trim(),
            channel = s.channel,
            steps = s.steps.map { it.copy(body = it.body.trim()) },
            stopOnReply = s.stopOnReply,
            stopOnOptOut = true,
            quietHours = s.quietHours,
            timeZone = s.timeZone.trim(),
            enabled = s.enabled,
            createdAt = if (s.isNew) now else s.createdAt,
            updatedAt = now,
        )
        val errors = service.validate(sequence)
        if (errors.isNotEmpty()) {
            _state.update { it.copy(errors = errors) }
            return
        }
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            try {
                sequences.upsertSequence(sequence)
                _state.update { it.copy(saving = false, finished = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(saving = false, message = "Couldn't save the sequence. Please try again.") }
            }
        }
    }

    fun delete() {
        val id = _state.value.id.takeIf { it.isNotBlank() } ?: return
        viewModelScope.launch {
            val active = sequences.enrollments(EnrollmentStatus.ACTIVE).filter { it.sequenceId == id }
            active.forEach { service.stop(it.id, "Sequence deleted") }
            sequences.deleteSequence(id)
            _state.update { it.copy(finished = true) }
        }
    }

    fun messageShown() = _state.update { it.copy(message = null) }
}

data class EnrollmentRow(val enrollment: SequenceEnrollment, val executions: List<ExecutionItem>)

data class EligibleContact(val contact: Contact, val address: String?, val blockedReason: String?)

data class SequenceDetailState(
    val loaded: Boolean = false,
    val sequence: MessageSequence? = null,
    val enrollments: List<EnrollmentRow> = emptyList(),
    val contacts: List<EligibleContact> = emptyList(),
)

@HiltViewModel
class SequenceDetailViewModel @Inject constructor(
    private val sequences: SequenceRepository,
    private val service: SequenceService,
    contacts: ContactRepository,
    executions: ExecutionRepository,
) : ViewModel() {
    private val sequenceId = MutableStateFlow("")
    private val _result = MutableStateFlow<EnrollResult?>(null)
    val result: StateFlow<EnrollResult?> = _result.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    val state: StateFlow<SequenceDetailState> = combine(
        sequenceId, sequences.observeSequences(), sequences.observeEnrollments(), contacts.observeAll(), executions.observeAll(),
    ) { id, allSequences, allEnrollments, allContacts, allExecutions ->
        val sequence = allSequences.firstOrNull { it.id == id }
        val enrollments = allEnrollments.filter { it.sequenceId == id }
        val bySource = allExecutions.filter { it.source == ExecutionSource.SEQUENCE }.groupBy { it.sourceId }
        val activeAddresses = enrollments.filter { it.status == EnrollmentStatus.ACTIVE }.map { it.recipient.contactId }.toSet()
        SequenceDetailState(
            loaded = id.isNotEmpty(),
            sequence = sequence,
            enrollments = enrollments.sortedByDescending { it.startedAt }
                .map { e -> EnrollmentRow(e, bySource[e.id].orEmpty().sortedBy { it.occurrenceIndex }) },
            contacts = if (sequence == null) emptyList() else allContacts.map { contact ->
                val address = contact.addressOn(sequence.channel)
                val reason = when {
                    address == null -> "No ${sequence.channel.displayName} address"
                    contact.optedOut -> "Opted out"
                    contact.id in activeAddresses -> "Already in this sequence"
                    else -> null
                }
                EligibleContact(contact, address, reason)
            }.sortedWith(compareBy<EligibleContact> { it.blockedReason != null }.thenBy { it.contact.displayName.lowercase() }),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SequenceDetailState())

    fun load(id: String) {
        sequenceId.value = id
    }

    fun enroll(contactIds: Set<String>) {
        val current = state.value
        val sequence = current.sequence ?: return
        val recipients = current.contacts
            .filter { it.contact.id in contactIds && it.address != null && it.blockedReason == null }
            .map { Recipient(it.address.orEmpty(), it.contact.displayName, it.contact.id) }
        viewModelScope.launch {
            try {
                _result.value = service.enroll(sequence.id, recipients)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _message.value = "Couldn't start the sequence. Please try again."
            }
        }
    }

    fun stop(enrollmentId: String) {
        viewModelScope.launch {
            service.stop(enrollmentId, "Stopped by you")
            _message.value = "Sequence stopped for this person"
        }
    }

    fun resultShown() {
        _result.value = null
    }

    fun messageShown() {
        _message.value = null
    }
}
