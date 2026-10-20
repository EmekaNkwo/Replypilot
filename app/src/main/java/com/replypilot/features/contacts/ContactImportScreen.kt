package com.replypilot.features.contacts

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.replypilot.core.design.BackTopBar
import com.replypilot.core.design.ConfirmDialog
import com.replypilot.core.design.ListCard
import com.replypilot.core.design.LocalStatusColors
import com.replypilot.core.design.Notice
import com.replypilot.core.design.NoticeTone
import com.replypilot.core.design.Pill
import com.replypilot.core.design.SectionHeader
import com.replypilot.core.design.Spacing
import com.replypilot.domain.contacts.ContactCsvImporter
import com.replypilot.domain.contacts.ImportRow
import com.replypilot.navigation.AppNavigator

private const val HEADER_HELP =
    "The first row must name the columns. Recognised columns: name or first_name, last_name, phone, sms, whatsapp, " +
        "telegram, instagram, messenger, email, tags and groups (separate several with ;), timezone, notes, opted_out (yes/no). " +
        "Phone numbers should include the country code."

@Composable
fun ContactImportScreen(nav: AppNavigator) {
    val viewModel: ContactImportViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val resolver = LocalContext.current.contentResolver
    var confirm by rememberSaveable { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.load(uri, resolver)
    }
    fun pick() = picker.launch(arrayOf("text/csv", "text/comma-separated-values", "text/plain"))

    Scaffold(topBar = { BackTopBar("Import contacts", nav::back) }) { padding ->
        val preview = state.preview
        LazyColumn(
            Modifier.padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            item { Notice(HEADER_HELP, title = "CSV format") }
            item {
                Notice("Nothing is saved until you review the preview and confirm. Contacts stay on this device.", tone = NoticeTone.INFO)
            }
            state.importedCount?.let { count ->
                item { Notice("Imported $count contact${if (count == 1) "" else "s"}.", title = "Done") }
                item { Button(onClick = nav::back, modifier = Modifier.fillMaxWidth()) { Text("Back to contacts") } }
            }
            item {
                OutlinedButton(onClick = ::pick, modifier = Modifier.fillMaxWidth(), enabled = !state.reading && !state.importing) {
                    Text(if (state.fileName == null) "Choose a CSV file" else "Choose a different file")
                }
            }
            if (state.reading) item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() } }
            state.error?.let { item { Notice(it, tone = NoticeTone.ERROR) } }
            if (preview != null && preview.headerError == null && state.importedCount == null) {
                val ready = preview.rows.count { it.importable }
                val duplicates = preview.rows.count { it.duplicateOf != null }
                val invalid = preview.rows.count { it.errors.isNotEmpty() }
                item {
                    SectionHeader("${state.fileName ?: "File"} · ${preview.rows.size} rows")
                    Text(
                        "$ready ready to import · $duplicates already in your contacts · $invalid with problems",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (preview.rows.size >= ContactCsvImporter.MAX_ROWS) {
                        Text("At most ${ContactCsvImporter.MAX_ROWS} rows per import.", style = MaterialTheme.typography.bodySmall)
                    }
                }
                item {
                    Button(
                        onClick = { confirm = true },
                        enabled = ready > 0 && !state.importing,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (state.importing) "Importing…" else "Import $ready contact${if (ready == 1) "" else "s"}") }
                }
                items(preview.rows, key = { it.line }) { row -> PreviewRow(row) }
            }
        }
    }

    if (confirm) {
        val ready = state.preview?.importable?.size ?: 0
        ConfirmDialog(
            title = "Import $ready contacts?",
            text = "Rows with problems and duplicates are skipped. You can edit or delete imported contacts afterwards.",
            confirmLabel = "Import",
            onConfirm = viewModel::confirmImport,
            onDismiss = { confirm = false },
        )
    }
}

@Composable
private fun PreviewRow(row: ImportRow) {
    val colors = LocalStatusColors.current
    ListCard(onClick = null) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    row.contact?.displayName?.ifBlank { null } ?: "Row ${row.line}",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                when {
                    row.errors.isNotEmpty() -> Pill("Problem", colors.failed)
                    row.duplicateOf != null -> Pill("Duplicate", colors.paused)
                    else -> Pill("Ready", colors.success)
                }
            }
            Text("Line ${row.line}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            row.contact?.identities?.takeIf { it.isNotEmpty() }?.let { ids ->
                Text(ids.joinToString { "${it.channel.displayName}: ${it.address}" }, style = MaterialTheme.typography.bodySmall)
            }
            row.duplicateOf?.let { Text("Matches existing contact $it", style = MaterialTheme.typography.bodySmall) }
            row.errors.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            if (row.contact?.optedOut == true) Pill("Opted out", colors.failed)
        }
    }
}
