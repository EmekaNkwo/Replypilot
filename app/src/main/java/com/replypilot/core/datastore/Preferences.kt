package com.replypilot.core.datastore

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import com.replypilot.core.database.AppJson
import com.replypilot.domain.model.AutomationSettings
import com.replypilot.domain.model.ChannelId
import com.replypilot.domain.repository.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class AiProviderKind(val label: String) {
    NONE("Off"),
    MOCK("Local mock (no AI, for testing)"),
    ANTHROPIC("Claude (Anthropic API, your own key)"),
}

data class AppPreferences(
    val onboardingComplete: Boolean = false,
    val demoMode: Boolean = false,
    /** Channels the user has explicitly enabled for on-device automation. */
    val enabledChannels: Set<ChannelId> = setOf(ChannelId.DEMO),
    /** Use exact alarms (when the system allows) instead of WorkManager's inexact timing. */
    val preciseTiming: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val aiProvider: AiProviderKind = AiProviderKind.NONE,
    /** Base HTTPS URL of a self-hosted WhatsApp Business relay; empty when not configured. */
    val businessRelayUrl: String = "",
)

fun createPreferencesDataStore(context: Context, scope: CoroutineScope): DataStore<Preferences> =
    PreferenceDataStoreFactory.create(
        corruptionHandler = ReplaceFileCorruptionHandler { error ->
            Log.e("ReplyPilot", "Preferences file was corrupted and has been reset", error)
            emptyPreferences()
        },
        scope = scope,
        produceFile = { context.preferencesDataStoreFile("replypilot_settings") },
    )

private object Keys {
    val automation = stringPreferencesKey("automation_settings_json")
    val onboarding = booleanPreferencesKey("onboarding_complete")
    val demo = booleanPreferencesKey("demo_mode")
    val channels = stringSetPreferencesKey("enabled_channels")
    val precise = booleanPreferencesKey("precise_timing")
    val theme = stringPreferencesKey("theme_mode")
    val ai = stringPreferencesKey("ai_provider")
    val relay = stringPreferencesKey("business_relay_url")
}

/** Automation policy stored as one JSON value so updates are atomic. */
@Singleton
class DataStoreSettingsRepository @Inject constructor(
    private val store: DataStore<Preferences>,
) : SettingsRepository {
    override val settings: Flow<AutomationSettings> = store.data.map { decode(it[Keys.automation]) }

    override suspend fun current(): AutomationSettings = settings.first()

    override suspend fun update(transform: (AutomationSettings) -> AutomationSettings) {
        store.edit { prefs ->
            prefs[Keys.automation] = AppJson.encodeToString(AutomationSettings.serializer(), transform(decode(prefs[Keys.automation])))
        }
    }

    private fun decode(json: String?): AutomationSettings = json?.let {
        runCatching { AppJson.decodeFromString(AutomationSettings.serializer(), it) }
            .onFailure { e -> Log.e("ReplyPilot", "Automation settings unreadable; using defaults", e) }
            .getOrNull()
    } ?: AutomationSettings(defaultTimeZone = java.time.ZoneId.systemDefault().id)
}

@Singleton
class AppPreferencesRepository @Inject constructor(
    private val store: DataStore<Preferences>,
) {
    val preferences: Flow<AppPreferences> = store.data.map { prefs ->
        AppPreferences(
            onboardingComplete = prefs[Keys.onboarding] ?: false,
            demoMode = prefs[Keys.demo] ?: false,
            enabledChannels = prefs[Keys.channels]?.mapNotNull { name -> ChannelId.entries.firstOrNull { it.name == name } }?.toSet()
                ?: setOf(ChannelId.DEMO),
            preciseTiming = prefs[Keys.precise] ?: false,
            themeMode = prefs[Keys.theme]?.let { name -> ThemeMode.entries.firstOrNull { it.name == name } } ?: ThemeMode.SYSTEM,
            aiProvider = prefs[Keys.ai]?.let { name -> AiProviderKind.entries.firstOrNull { it.name == name } } ?: AiProviderKind.NONE,
            businessRelayUrl = prefs[Keys.relay].orEmpty(),
        )
    }

    suspend fun current(): AppPreferences = preferences.first()

    suspend fun setOnboardingComplete(value: Boolean) = store.edit { it[Keys.onboarding] = value }
    suspend fun setDemoMode(value: Boolean) = store.edit { it[Keys.demo] = value }
    suspend fun setPreciseTiming(value: Boolean) = store.edit { it[Keys.precise] = value }
    suspend fun setThemeMode(value: ThemeMode) = store.edit { it[Keys.theme] = value.name }
    suspend fun setAiProvider(value: AiProviderKind) = store.edit { it[Keys.ai] = value.name }
    suspend fun setBusinessRelayUrl(value: String) = store.edit { it[Keys.relay] = value.trim() }

    suspend fun setChannelEnabled(channel: ChannelId, enabled: Boolean) = store.edit { prefs ->
        val current = prefs[Keys.channels] ?: setOf(ChannelId.DEMO.name)
        prefs[Keys.channels] = if (enabled) current + channel.name else current - channel.name
    }

    suspend fun clear() = store.edit { it.clear() }
}
