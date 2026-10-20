package com.replypilot.features.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.replypilot.core.datastore.AppPreferencesRepository
import com.replypilot.data.services.DataManager
import com.replypilot.data.services.DemoSeeder
import com.replypilot.domain.backup.ExportBundle
import com.replypilot.domain.backup.ImportParse
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DataPrivacyUiState(
    val busy: Boolean = false,
    val message: String? = null,
    val pendingImport: ExportBundle? = null,
    val deleted: Boolean = false,
)

@HiltViewModel
class DataPrivacyViewModel @Inject constructor(
    private val data: DataManager,
    private val seeder: DemoSeeder,
    preferences: AppPreferencesRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(DataPrivacyUiState())
    val state: StateFlow<DataPrivacyUiState> = _state.asStateFlow()

    val demoMode: StateFlow<Boolean> = preferences.preferences.map { it.demoMode }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    suspend fun exportJson(): String = data.exportAutomation()

    fun onExported(ok: Boolean) = _state.update { it.copy(message = if (ok) "Rules and templates exported" else "Export failed") }

    fun parseImport(text: String?) {
        if (text == null) {
            _state.update { it.copy(message = "Couldn't read that file") }
            return
        }
        when (val parsed = data.parseImport(text)) {
            is ImportParse.Ok -> _state.update { it.copy(pendingImport = parsed.bundle) }
            is ImportParse.Error -> _state.update { it.copy(message = parsed.message) }
        }
    }

    fun cancelImport() = _state.update { it.copy(pendingImport = null) }

    fun confirmImport() = run("Import failed") {
        val bundle = _state.value.pendingImport ?: return@run
        val summary = data.applyImport(bundle)
        _state.update {
            it.copy(
                pendingImport = null,
                message = "Imported ${summary.rules} rules, ${summary.templates} templates, ${summary.sequences} sequences (rules and sequences are off until you enable them)",
            )
        }
    }

    fun loadDemo() = run("Couldn't load demo data") {
        seeder.seed()
        _state.update { it.copy(message = "Demo data loaded — look for the Demo labels") }
    }

    fun clearDemo() = run("Couldn't remove demo data") {
        data.clearDemoData()
        _state.update { it.copy(message = "Demo data removed") }
    }

    fun deleteAll() = run("Couldn't delete data") {
        data.deleteAllData()
        _state.update { it.copy(deleted = true, message = "All ReplyPilot data deleted") }
    }

    fun messageShown() = _state.update { it.copy(message = null) }

    private fun run(failure: String, block: suspend () -> Unit) = viewModelScope.launch {
        _state.update { it.copy(busy = true) }
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(message = "$failure: ${e.message ?: "unknown error"}") }
        } finally {
            _state.update { it.copy(busy = false) }
        }
    }
}
