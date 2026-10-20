package com.replypilot.features.settings

import com.replypilot.core.accessibility.AutoSendBridge
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.replypilot.core.datastore.AiProviderKind
import com.replypilot.core.datastore.AppPreferences
import com.replypilot.core.datastore.AppPreferencesRepository
import com.replypilot.core.datastore.ThemeMode
import com.replypilot.core.permissions.CapabilityState
import com.replypilot.core.permissions.PlatformStatus
import com.replypilot.core.security.CredentialKey
import com.replypilot.core.security.CredentialStore
import com.replypilot.domain.assistant.AssistOperation
import com.replypilot.domain.assistant.MessageAssistant
import com.replypilot.domain.messaging.AdapterRegistry
import com.replypilot.domain.model.AutomationSettings
import com.replypilot.domain.model.ChannelId
import com.replypilot.domain.model.ChannelStatus
import com.replypilot.domain.repository.SettingsRepository
import com.replypilot.domain.scheduling.QueueActions
import com.replypilot.integrations.whatsapp.WhatsAppBusinessAdapter
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class SettingsRootViewModel @Inject constructor(
    private val preferences: AppPreferencesRepository,
    private val platform: PlatformStatus,
) : ViewModel() {
    val prefs: StateFlow<AppPreferences> =
        preferences.preferences.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppPreferences())

    private val _exactAllowed = MutableStateFlow(platform.exactAlarmsAllowed())
    val exactAllowed: StateFlow<Boolean> = _exactAllowed.asStateFlow()

    fun refresh() {
        _exactAllowed.value = platform.exactAlarmsAllowed()
    }

    fun setTheme(mode: ThemeMode) = viewModelScope.launch { preferences.setThemeMode(mode) }
    fun setPreciseTiming(enabled: Boolean) = viewModelScope.launch { preferences.setPreciseTiming(enabled) }
}

data class ChannelsUiState(
    val loading: Boolean = true,
    val statuses: List<ChannelStatus> = emptyList(),
    val enabled: Set<ChannelId> = emptySet(),
    val relayUrl: String = "",
    val relayTokenSaved: Boolean = false,
    val autoSendEnabled: Boolean = false,
    val testResult: String? = null,
    val testing: Boolean = false,
    val message: String? = null,
)

@HiltViewModel
class ChannelsViewModel @Inject constructor(
    private val adapters: AdapterRegistry,
    private val preferences: AppPreferencesRepository,
    private val credentials: CredentialStore,
    private val business: WhatsAppBusinessAdapter,
    private val autoSend: AutoSendBridge,
) : ViewModel() {
    private val _state = MutableStateFlow(ChannelsUiState())
    val state: StateFlow<ChannelsUiState> = _state.asStateFlow()

    fun refresh() = viewModelScope.launch {
        val prefs = preferences.current()
        val statuses = adapters.all().map { it.status() }.sortedBy { it.id.ordinal }
        _state.update {
            it.copy(
                loading = false, statuses = statuses, enabled = prefs.enabledChannels, relayUrl = prefs.businessRelayUrl,
                relayTokenSaved = credentials.has(CredentialKey.BUSINESS_RELAY_TOKEN),
                autoSendEnabled = autoSend.isEnabled(),
            )
        }
    }

    fun setEnabled(channel: ChannelId, enabled: Boolean) = viewModelScope.launch {
        preferences.setChannelEnabled(channel, enabled)
        refresh()
    }

    /** Saves the relay URL and, when provided, a new device token. Returns an error message or null. */
    fun saveRelay(url: String, token: String) = viewModelScope.launch {
        val trimmed = url.trim()
        val error = relayUrlError(trimmed)
        if (error != null) {
            _state.update { it.copy(message = error) }
            return@launch
        }
        preferences.setBusinessRelayUrl(trimmed)
        if (token.isNotBlank()) credentials.write(CredentialKey.BUSINESS_RELAY_TOKEN, token.trim())
        _state.update { it.copy(message = "Relay settings saved", testResult = null) }
        refresh()
    }

    fun removeRelay() = viewModelScope.launch {
        preferences.setBusinessRelayUrl("")
        credentials.remove(CredentialKey.BUSINESS_RELAY_TOKEN)
        _state.update { it.copy(message = "Relay removed", testResult = null) }
        refresh()
    }

    fun testConnection() = viewModelScope.launch {
        _state.update { it.copy(testing = true, testResult = null) }
        val result = try {
            business.testConnection()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "Test failed: ${e.message ?: "unknown error"}"
        }
        _state.update { it.copy(testing = false, testResult = result) }
    }

    fun messageShown() = _state.update { it.copy(message = null) }

    companion object {
        fun relayUrlError(url: String): String? = when {
            url.isEmpty() -> null
            !url.startsWith("https://") -> "The relay URL must start with https://"
            runCatching { java.net.URL(url) }.isFailure -> "That doesn't look like a valid URL"
            else -> null
        }
    }
}

@HiltViewModel
class PermissionsViewModel @Inject constructor(private val platform: PlatformStatus) : ViewModel() {
    private val _states = MutableStateFlow(platform.states())
    val states: StateFlow<List<CapabilityState>> = _states.asStateFlow()

    fun refresh() {
        _states.value = platform.states()
    }
}

@HiltViewModel
class SafeguardsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val actions: QueueActions,
    private val preferences: AppPreferencesRepository,
    private val credentials: CredentialStore,
) : ViewModel() {
    val settingsState: StateFlow<AutomationSettings?> =
        settings.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _realAi = MutableStateFlow(false)
    val realAiConfigured: StateFlow<Boolean> = _realAi.asStateFlow()

    fun refresh() = viewModelScope.launch {
        _realAi.value = preferences.current().aiProvider == AiProviderKind.ANTHROPIC && credentials.has(CredentialKey.AI_API_KEY)
    }

    fun update(transform: (AutomationSettings) -> AutomationSettings) = viewModelScope.launch { settings.update(transform) }

    fun setPaused(paused: Boolean) = viewModelScope.launch { actions.setAutomationPaused(paused) }
}

data class AssistantUiState(
    val provider: AiProviderKind = AiProviderKind.NONE,
    val keySaved: Boolean = false,
    val trying: Boolean = false,
    val tryResult: String? = null,
    val tryProvider: String? = null,
    val tryError: String? = null,
)

@HiltViewModel
class AssistantSettingsViewModel @Inject constructor(
    private val preferences: AppPreferencesRepository,
    private val credentials: CredentialStore,
    private val assistant: MessageAssistant,
) : ViewModel() {
    private val _state = MutableStateFlow(AssistantUiState())
    val state: StateFlow<AssistantUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            preferences.preferences.collect { prefs ->
                _state.update { it.copy(provider = prefs.aiProvider, keySaved = credentials.has(CredentialKey.AI_API_KEY)) }
            }
        }
    }

    fun setProvider(kind: AiProviderKind) = viewModelScope.launch { preferences.setAiProvider(kind) }

    fun saveKey(key: String) {
        if (key.isBlank()) return
        credentials.write(CredentialKey.AI_API_KEY, key.trim())
        _state.update { it.copy(keySaved = true) }
    }

    fun removeKey() {
        credentials.remove(CredentialKey.AI_API_KEY)
        _state.update { it.copy(keySaved = false) }
    }

    fun tryIt(text: String) = viewModelScope.launch {
        _state.update { it.copy(trying = true, tryResult = null, tryError = null) }
        assistant.transform(text, AssistOperation.REWRITE).fold(
            onSuccess = { result ->
                val label = if (result.isMock) "${result.providerName} — mock output, not AI" else result.providerName
                _state.update { it.copy(trying = false, tryResult = result.suggestions.joinToString("\n\n"), tryProvider = label) }
            },
            onFailure = { e -> _state.update { it.copy(trying = false, tryError = e.message ?: "The assistant isn't available.") } },
        )
    }
}
