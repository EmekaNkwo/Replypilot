package com.replypilot.features.templates

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.replypilot.data.services.StarterContent
import com.replypilot.domain.common.Clock
import com.replypilot.domain.common.IdGenerator
import com.replypilot.domain.model.MessageTemplate
import com.replypilot.domain.model.TemplateCategory
import com.replypilot.domain.repository.ContactRepository
import com.replypilot.domain.repository.TemplateRepository
import com.replypilot.domain.templates.KnownVariables
import com.replypilot.domain.templates.TemplateRenderer
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TemplatesState(
    val loaded: Boolean = false,
    val query: String = "",
    val total: Int = 0,
    val groups: List<Pair<TemplateCategory, List<MessageTemplate>>> = emptyList(),
    val message: String? = null,
)

@HiltViewModel
class TemplatesViewModel @Inject constructor(
    private val templates: TemplateRepository,
    private val clock: Clock,
) : ViewModel() {
    private val query = MutableStateFlow("")
    private val message = MutableStateFlow<String?>(null)

    val state: StateFlow<TemplatesState> = combine(templates.observeAll(), query, message) { list, q, msg ->
        val needle = q.trim().lowercase()
        val filtered = if (needle.isEmpty()) list else list.filter {
            it.name.lowercase().contains(needle) || it.body.lowercase().contains(needle) || it.category.label.lowercase().contains(needle)
        }
        TemplatesState(
            loaded = true,
            query = q,
            total = list.size,
            groups = filtered.groupBy { it.category }.toList().sortedBy { it.first.ordinal }
                .map { (category, items) -> category to items.sortedBy { it.name.lowercase() } },
            message = msg,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TemplatesState())

    fun setQuery(value: String) {
        query.value = value
    }

    fun addStarterTemplates() {
        viewModelScope.launch {
            try {
                StarterContent.templates(clock.now()).forEach { templates.upsert(it) }
                message.value = "Starter templates added"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message.value = "Couldn't add starter templates. Please try again."
            }
        }
    }

    fun messageShown() {
        message.value = null
    }
}

/** How each placeholder in a body will behave when the message is personalised. */
data class VariableUsage(val name: String, val known: Boolean, val hasFallback: Boolean)

object TemplateAnalysis {
    private val PLACEHOLDER = Regex("""\{\{\s*([A-Za-z][A-Za-z0-9_]*)\s*(\|[^}]*)?\}\}""")

    fun usages(body: String, customFields: Set<String>): List<VariableUsage> {
        val known = KnownVariables.names + customFields + "senderName"
        return PLACEHOLDER.findAll(body)
            .groupBy { it.groupValues[1] }
            .map { (name, matches) ->
                VariableUsage(name, name in known, matches.all { !it.groups[2]?.value?.removePrefix("|").isNullOrBlank() })
            }
    }

    fun preview(body: String, customFields: Set<String>): TemplateRenderer.Result =
        TemplateRenderer.render(body, KnownVariables.samples + customFields.associateWith { "‹$it›" })
}

data class TemplateEditorState(
    val loading: Boolean = true,
    val isNew: Boolean = true,
    val id: String = "",
    val name: String = "",
    val category: TemplateCategory = TemplateCategory.CUSTOM,
    val body: String = "",
    val customFields: Set<String> = emptySet(),
    val nameError: String? = null,
    val bodyError: String? = null,
    val saving: Boolean = false,
    val finished: Boolean = false,
    val message: String? = null,
    val notFound: Boolean = false,
) {
    val usages: List<VariableUsage> get() = TemplateAnalysis.usages(body, customFields)
    val preview: TemplateRenderer.Result get() = TemplateAnalysis.preview(body, customFields)
}

@HiltViewModel
class TemplateEditorViewModel @Inject constructor(
    private val templates: TemplateRepository,
    contacts: ContactRepository,
    private val ids: IdGenerator,
    private val clock: Clock,
) : ViewModel() {
    private val _state = MutableStateFlow(TemplateEditorState())
    val state: StateFlow<TemplateEditorState> = _state.asStateFlow()
    private var loadedId: String? = null

    init {
        viewModelScope.launch {
            contacts.observeAll()
                .map { list -> list.flatMap { it.customFields.keys }.toSortedSet() }
                .collect { fields -> _state.update { it.copy(customFields = fields) } }
        }
    }

    fun load(id: String?) {
        if (loadedId == (id ?: "") && !_state.value.loading) return
        loadedId = id ?: ""
        viewModelScope.launch {
            if (id == null) {
                _state.update { it.copy(loading = false, isNew = true) }
                return@launch
            }
            val template = templates.get(id)
            _state.update {
                if (template == null) {
                    it.copy(loading = false, notFound = true)
                } else {
                    it.copy(loading = false, isNew = false, id = template.id, name = template.name, category = template.category, body = template.body)
                }
            }
        }
    }

    fun setName(value: String) = _state.update { it.copy(name = value, nameError = null) }
    fun setCategory(value: TemplateCategory) = _state.update { it.copy(category = value) }
    fun setBody(value: String) = _state.update { it.copy(body = value, bodyError = null) }
    fun insertVariable(name: String) = _state.update {
        val sep = if (it.body.isEmpty() || it.body.endsWith(" ")) "" else " "
        it.copy(body = it.body + sep + "{{$name}}", bodyError = null)
    }

    fun save() {
        val current = _state.value
        val nameError = if (current.name.isBlank()) "Give the template a name." else null
        val bodyError = when {
            current.body.isBlank() -> "Write the message."
            current.body.length > MAX_BODY -> "Keep templates under $MAX_BODY characters."
            else -> null
        }
        if (nameError != null || bodyError != null) {
            _state.update { it.copy(nameError = nameError, bodyError = bodyError) }
            return
        }
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            try {
                templates.upsert(
                    MessageTemplate(
                        id = current.id.ifBlank { ids.next() },
                        name = current.name.trim(),
                        category = current.category,
                        body = current.body.trim(),
                        updatedAt = clock.now(),
                    ),
                )
                _state.update { it.copy(saving = false, finished = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(saving = false, message = "Couldn't save the template. Please try again.") }
            }
        }
    }

    fun delete() {
        val id = _state.value.id.takeIf { it.isNotBlank() } ?: return
        viewModelScope.launch {
            try {
                templates.delete(id)
                _state.update { it.copy(finished = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(message = "Couldn't delete the template.") }
            }
        }
    }

    fun messageShown() = _state.update { it.copy(message = null) }

    companion object {
        const val MAX_BODY = 4000
    }
}
