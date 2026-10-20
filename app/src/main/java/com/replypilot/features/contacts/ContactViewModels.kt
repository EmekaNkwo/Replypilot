package com.replypilot.features.contacts

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.replypilot.domain.common.Clock
import com.replypilot.domain.common.IdGenerator
import com.replypilot.domain.contacts.ContactCsvImporter
import com.replypilot.domain.contacts.ImportPreview
import com.replypilot.domain.model.ChannelId
import com.replypilot.domain.model.ChannelIdentity
import com.replypilot.domain.model.Contact
import com.replypilot.domain.repository.ContactRepository
import com.replypilot.domain.repository.SettingsRepository
import com.replypilot.domain.scheduling.AddressRules
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ContactsState(
    val loaded: Boolean = false,
    val all: List<Contact> = emptyList(),
    val visible: List<Contact> = emptyList(),
    val query: String = "",
    val tagFilter: String? = null,
    val tags: List<String> = emptyList(),
    val groups: List<Pair<String, List<Contact>>> = emptyList(),
    val customFields: List<String> = emptyList(),
    val businessName: String = "",
    val supportHours: String = "",
)

@HiltViewModel
class ContactsViewModel @Inject constructor(
    private val contacts: ContactRepository,
    private val settings: SettingsRepository,
    private val clock: Clock,
) : ViewModel() {
    private val query = MutableStateFlow("")
    private val tagFilter = MutableStateFlow<String?>(null)
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    val state: StateFlow<ContactsState> = combine(contacts.observeAll(), query, tagFilter, settings.settings) { list, q, tag, s ->
        val needle = q.trim().lowercase()
        val visible = list.filter { c ->
            (needle.isEmpty() || c.displayName.lowercase().contains(needle) || c.identities.any { it.address.lowercase().contains(needle) }) &&
                (tag == null || tag in c.tags || tag in c.groups)
        }
        ContactsState(
            loaded = true,
            all = list,
            visible = visible,
            query = q,
            tagFilter = tag,
            tags = (list.flatMap { it.tags } + list.flatMap { it.groups }).distinct().sortedBy { it.lowercase() },
            groups = list.flatMap { c -> c.groups.map { it to c } }.groupBy({ it.first }, { it.second })
                .toList().sortedBy { it.first.lowercase() },
            customFields = list.flatMap { it.customFields.keys }.distinct().sorted(),
            businessName = s.businessName,
            supportHours = s.supportHours,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ContactsState())

    fun setQuery(value: String) {
        query.value = value
    }

    fun setTagFilter(value: String?) {
        tagFilter.value = value
    }

    fun renameGroup(old: String, new: String) {
        val target = new.trim()
        if (target.isEmpty() || target == old) return
        updateGroupMembers(old) { it - old + target }
        _message.value = "Group renamed to $target"
    }

    fun removeGroup(name: String) {
        updateGroupMembers(name) { it - name }
        _message.value = "Group \"$name\" removed; contacts were kept"
    }

    private fun updateGroupMembers(group: String, transform: (Set<String>) -> Set<String>) {
        viewModelScope.launch {
            val now = clock.now()
            state.value.all.filter { group in it.groups }.forEach { contacts.upsert(it.copy(groups = transform(it.groups), updatedAt = now)) }
        }
    }

    fun saveBusinessVariables(businessName: String, supportHours: String) {
        viewModelScope.launch {
            settings.update { it.copy(businessName = businessName.trim(), supportHours = supportHours.trim()) }
            _message.value = "Business details saved"
        }
    }

    fun messageShown() {
        _message.value = null
    }
}

data class IdentityDraft(val channel: ChannelId, val address: String) {
    val error: String?
        get() = when {
            address.isBlank() -> "Enter an address"
            !AddressRules.isValid(channel, address) -> AddressRules.hint(channel)
            else -> null
        }
}

data class FieldDraft(val key: String, val value: String) {
    val error: String?
        get() = if (key.isNotBlank() && !KEY_PATTERN.matches(key)) "Use letters, digits and _ only, starting with a letter" else null

    companion object {
        val KEY_PATTERN = Regex("[A-Za-z][A-Za-z0-9_]{0,39}")
    }
}

data class ContactEditorState(
    val loading: Boolean = true,
    val isNew: Boolean = true,
    val id: String = "",
    val firstName: String = "",
    val lastName: String = "",
    val identities: List<IdentityDraft> = emptyList(),
    val preferredChannel: ChannelId? = null,
    val tags: Set<String> = emptySet(),
    val groups: Set<String> = emptySet(),
    val timeZone: String = "",
    val optedOut: Boolean = false,
    val optedOutAt: Long? = null,
    val notes: String = "",
    val fields: List<FieldDraft> = emptyList(),
    val createdAt: Long = 0,
    val showErrors: Boolean = false,
    val errors: List<String> = emptyList(),
    val finished: Boolean = false,
    val notFound: Boolean = false,
    val message: String? = null,
) {
    val timeZoneValid: Boolean get() = timeZone.isBlank() || runCatching { ZoneId.of(timeZone.trim()) }.isSuccess
}

@HiltViewModel
class ContactEditorViewModel @Inject constructor(
    private val contacts: ContactRepository,
    private val ids: IdGenerator,
    private val clock: Clock,
) : ViewModel() {
    private val _state = MutableStateFlow(ContactEditorState())
    val state: StateFlow<ContactEditorState> = _state.asStateFlow()
    private var loaded = false

    fun load(id: String?) {
        if (loaded) return
        loaded = true
        viewModelScope.launch {
            if (id == null) {
                _state.update { it.copy(loading = false) }
                return@launch
            }
            val contact = contacts.get(id)
            _state.update {
                if (contact == null) {
                    it.copy(loading = false, notFound = true)
                } else {
                    it.copy(
                        loading = false, isNew = false, id = contact.id, firstName = contact.firstName, lastName = contact.lastName,
                        identities = contact.identities.map { i -> IdentityDraft(i.channel, i.address) },
                        preferredChannel = contact.preferredChannel, tags = contact.tags, groups = contact.groups,
                        timeZone = contact.timeZone.orEmpty(), optedOut = contact.optedOut, optedOutAt = contact.optedOutAt,
                        notes = contact.notes, fields = contact.customFields.map { (k, v) -> FieldDraft(k, v) }, createdAt = contact.createdAt,
                    )
                }
            }
        }
    }

    fun update(transform: (ContactEditorState) -> ContactEditorState) = _state.update { transform(it).copy(errors = emptyList()) }

    fun addIdentity(channel: ChannelId) = update { it.copy(identities = it.identities + IdentityDraft(channel, "")) }
    fun setIdentity(index: Int, draft: IdentityDraft) = update { s -> s.copy(identities = s.identities.mapIndexed { i, d -> if (i == index) draft else d }) }
    fun removeIdentity(index: Int) = update { s -> s.copy(identities = s.identities.filterIndexed { i, _ -> i != index }) }
    fun addField() = update { it.copy(fields = it.fields + FieldDraft("", "")) }
    fun setField(index: Int, draft: FieldDraft) = update { s -> s.copy(fields = s.fields.mapIndexed { i, d -> if (i == index) draft else d }) }
    fun removeField(index: Int) = update { s -> s.copy(fields = s.fields.filterIndexed { i, _ -> i != index }) }
    fun setOptedOut(value: Boolean) = update { it.copy(optedOut = value, optedOutAt = if (value) it.optedOutAt ?: clock.now() else null) }

    fun save() {
        val s = _state.value
        val errors = buildList {
            if (s.firstName.isBlank()) add("Enter a first name.")
            s.identities.forEach { d -> d.error?.let { add("${d.channel.displayName}: $it") } }
            if (s.identities.map { it.channel to AddressRules.normalize(it.channel, it.address) }.let { it.size != it.toSet().size }) {
                add("The same address is listed twice.")
            }
            if (!s.timeZoneValid) add("Unknown time zone. Use a zone like Africa/Lagos.")
            s.fields.forEach { f -> f.error?.let { add("Field \"${f.key}\": $it") } }
            val keys = s.fields.map { it.key.trim() }.filter { it.isNotEmpty() }
            if (keys.size != keys.toSet().size) add("Each custom field name must be unique.")
        }
        if (errors.isNotEmpty()) {
            _state.update { it.copy(showErrors = true, errors = errors) }
            return
        }
        val now = clock.now()
        val identities = s.identities.map { ChannelIdentity(it.channel, AddressRules.normalize(it.channel, it.address)) }
        val contact = Contact(
            id = s.id.ifBlank { ids.next() },
            firstName = s.firstName.trim(),
            lastName = s.lastName.trim(),
            identities = identities,
            preferredChannel = s.preferredChannel?.takeIf { p -> identities.any { it.channel == p } } ?: identities.firstOrNull()?.channel,
            tags = s.tags,
            groups = s.groups,
            timeZone = s.timeZone.trim().ifBlank { null },
            optedOut = s.optedOut,
            optedOutAt = if (s.optedOut) s.optedOutAt ?: now else null,
            notes = s.notes.trim().take(MAX_NOTES),
            customFields = s.fields.filter { it.key.isNotBlank() }.associate { it.key.trim() to it.value.trim() },
            createdAt = if (s.isNew) now else s.createdAt,
            updatedAt = now,
        )
        viewModelScope.launch {
            try {
                contacts.upsert(contact)
                _state.update { it.copy(finished = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(message = "Couldn't save the contact. Please try again.") }
            }
        }
    }

    fun delete() {
        val id = _state.value.id.takeIf { it.isNotBlank() } ?: return
        viewModelScope.launch {
            val existing = contacts.get(id)
            if (existing?.optedOut == true) {
                // Keep the opt-out so pending or future messages to this address are still blocked.
                contacts.upsert(
                    existing.copy(
                        firstName = "Opted-out contact", lastName = "", tags = emptySet(), groups = emptySet(),
                        notes = "Details removed; kept only to honour the opt-out.", customFields = emptyMap(),
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
            } else {
                contacts.delete(id)
            }
            _state.update { it.copy(finished = true) }
        }
    }

    fun messageShown() = _state.update { it.copy(message = null) }

    companion object {
        const val MAX_NOTES = 1000
    }
}

data class ImportState(
    val fileName: String? = null,
    val reading: Boolean = false,
    val preview: ImportPreview? = null,
    val error: String? = null,
    val importing: Boolean = false,
    val importedCount: Int? = null,
)

@HiltViewModel
class ContactImportViewModel @Inject constructor(
    private val contacts: ContactRepository,
    private val ids: IdGenerator,
    private val clock: Clock,
) : ViewModel() {
    private val _state = MutableStateFlow(ImportState())
    val state: StateFlow<ImportState> = _state.asStateFlow()

    fun load(uri: Uri, resolver: ContentResolver) {
        _state.value = ImportState(reading = true)
        viewModelScope.launch {
            try {
                val (name, text) = withContext(Dispatchers.IO) { readText(uri, resolver) }
                if (text == null) {
                    _state.value = ImportState(fileName = name, error = "This file is larger than 1 MB. Split it into smaller files.")
                    return@launch
                }
                val preview = ContactCsvImporter.preview(text, contacts.all(), ids::next, clock.now())
                _state.value = ImportState(fileName = name, preview = preview, error = preview.headerError)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = ImportState(error = "Couldn't read that file. Make sure it's a CSV file.")
            }
        }
    }

    fun confirmImport() {
        val preview = _state.value.preview ?: return
        val toImport = preview.importable
        if (toImport.isEmpty()) return
        _state.update { it.copy(importing = true) }
        viewModelScope.launch {
            try {
                contacts.upsertAll(toImport)
                _state.update { it.copy(importing = false, importedCount = toImport.size) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(importing = false, error = "Import failed; nothing was saved past the error. Please try again.") }
            }
        }
    }

    fun reset() {
        _state.value = ImportState()
    }

    private fun readText(uri: Uri, resolver: ContentResolver): Pair<String?, String?> {
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
        val bytes = resolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(MAX_BYTES + 1)
            var total = 0
            while (total < buffer.size) {
                val read = input.read(buffer, total, buffer.size - total)
                if (read < 0) break
                total += read
            }
            buffer.copyOf(total)
        } ?: return name to ""
        return name to if (bytes.size > MAX_BYTES) null else String(bytes, Charsets.UTF_8)
    }

    companion object {
        const val MAX_BYTES = 1024 * 1024
    }
}
