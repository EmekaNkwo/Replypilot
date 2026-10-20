package com.replypilot.features.rules

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.replypilot.domain.common.Clock
import com.replypilot.domain.model.AutoReplyRule
import com.replypilot.domain.model.MessageTemplate
import com.replypilot.domain.repository.ContactRepository
import com.replypilot.domain.repository.RuleRepository
import com.replypilot.domain.repository.TemplateRepository
import com.replypilot.domain.rules.RuleEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
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

data class RulesState(
    val loading: Boolean = true,
    /** All rules in the exact order the engine evaluates them (disabled rules are skipped at runtime). */
    val rules: List<AutoReplyRule> = emptyList(),
    val templates: Map<String, MessageTemplate> = emptyMap(),
    val contactNames: Map<String, String> = emptyMap(),
) {
    val ruleNames: Map<String, String> get() = rules.associate { it.id to it.name }
}

@HiltViewModel
class RulesViewModel @Inject constructor(
    private val rules: RuleRepository,
    templates: TemplateRepository,
    contacts: ContactRepository,
    private val clock: Clock,
) : ViewModel() {
    val state: StateFlow<RulesState> = combine(rules.observeAll(), templates.observeAll(), contacts.observeAll()) { r, t, c ->
        RulesState(
            loading = false,
            rules = RuleEngine.order(r),
            templates = t.associateBy { it.id },
            contactNames = c.associate { it.id to it.displayName },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RulesState())

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    fun setEnabled(rule: AutoReplyRule, enabled: Boolean) = act(if (enabled) "“${rule.name}” is on" else "“${rule.name}” is off") {
        rules.upsert(rule.copy(enabled = enabled, updatedAt = clock.now()))
    }

    /** Copies start disabled so a duplicate never doubles replies by accident. */
    fun duplicate(rule: AutoReplyRule) = act("Copied as “${rule.name} (copy)” — it starts switched off") {
        val now = clock.now()
        rules.upsert(rule.copy(id = UUID.randomUUID().toString(), name = "${rule.name} (copy)", enabled = false, createdAt = now, updatedAt = now))
    }

    fun delete(rule: AutoReplyRule) = act("Deleted “${rule.name}”") { rules.delete(rule.id) }

    /** Moves a rule one place in evaluation order by renumbering priorities so the order is unambiguous. */
    fun move(rule: AutoReplyRule, up: Boolean) = act(null) {
        val ordered = state.value.rules
        val index = ordered.indexOfFirst { it.id == rule.id }
        val target = if (up) index - 1 else index + 1
        if (index < 0 || target !in ordered.indices) return@act
        val reordered = ordered.toMutableList().apply { add(target, removeAt(index)) }.toList()
        val now = clock.now()
        reordered.forEachIndexed { i, r ->
            val priority = (reordered.size - i) * PRIORITY_STEP
            if (r.priority != priority) rules.upsert(r.copy(priority = priority, updatedAt = now))
        }
    }

    private fun act(success: String?, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
                success?.let { _messages.emit(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.emit("Couldn't update the rule. Please try again.")
            }
        }
    }

    private companion object {
        const val PRIORITY_STEP = 10
    }
}
