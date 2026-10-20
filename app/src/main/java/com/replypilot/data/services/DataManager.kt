package com.replypilot.data.services

import android.content.Context
import androidx.work.WorkManager
import com.replypilot.core.database.ReplyPilotDatabase
import com.replypilot.core.datastore.AppPreferencesRepository
import com.replypilot.core.security.CredentialStore
import com.replypilot.domain.backup.ExportBundle
import com.replypilot.domain.backup.ExportCodec
import com.replypilot.domain.backup.ImportParse
import com.replypilot.domain.common.Clock
import com.replypilot.domain.model.AutomationSettings
import com.replypilot.domain.repository.RuleRepository
import com.replypilot.domain.repository.SequenceRepository
import com.replypilot.domain.repository.SettingsRepository
import com.replypilot.domain.repository.TemplateRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ImportSummary(val rules: Int, val templates: Int, val sequences: Int)

/** Export, import and deletion of the user's local data. */
@Singleton
class DataManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: ReplyPilotDatabase,
    private val rules: RuleRepository,
    private val templates: TemplateRepository,
    private val sequences: SequenceRepository,
    private val settings: SettingsRepository,
    private val preferences: AppPreferencesRepository,
    private val credentials: CredentialStore,
    private val clock: Clock,
) {
    suspend fun exportAutomation(): String = ExportCodec.encode(
        ExportBundle(
            exportedAt = clock.now(),
            rules = rules.all(),
            templates = templates.all(),
            sequences = sequences.allSequences(),
        ),
    )

    fun parseImport(text: String): ImportParse = ExportCodec.decode(text)

    /** Imported items replace existing ones with the same id. Rules arrive disabled so nothing runs unreviewed. */
    suspend fun applyImport(bundle: ExportBundle): ImportSummary {
        val now = clock.now()
        bundle.templates.forEach { templates.upsert(it.copy(updatedAt = now)) }
        bundle.rules.forEach { rules.upsert(it.copy(enabled = false, updatedAt = now)) }
        bundle.sequences.forEach { sequences.upsertSequence(it.copy(enabled = false, updatedAt = now)) }
        return ImportSummary(bundle.rules.size, bundle.templates.size, bundle.sequences.size)
    }

    /** Removes everything ReplyPilot stores on this device, including credentials and pending work. */
    suspend fun deleteAllData() = withContext(Dispatchers.IO) {
        WorkManager.getInstance(context).cancelAllWork()
        db.clearAllTables()
        preferences.clear()
        settings.update { AutomationSettings(defaultTimeZone = ZoneId.systemDefault().id) }
        credentials.clearAll()
    }

    suspend fun clearDemoData() = withContext(Dispatchers.IO) {
        db.executions().deleteSimulated()
        db.activity().deleteSimulated()
        db.schedules().all().filter { it.isDemo }.forEach { db.schedules().delete(it.id) }
        db.rules().all().filter { it.id.startsWith(DemoSeeder.PREFIX) }.forEach { db.rules().delete(it.id) }
        db.templates().all().filter { it.id.startsWith(DemoSeeder.PREFIX) }.forEach { db.templates().delete(it.id) }
        db.contacts().all().filter { it.id.startsWith(DemoSeeder.PREFIX) }.forEach { db.contacts().delete(it.id) }
        db.sequences().allSequences().filter { it.id.startsWith(DemoSeeder.PREFIX) }.forEach { db.sequences().deleteSequence(it.id) }
        preferences.setDemoMode(false)
    }
}
