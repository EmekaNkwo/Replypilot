package com.replypilot.features.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.replypilot.core.design.BackTopBar
import com.replypilot.core.design.ConfirmDialog
import com.replypilot.core.design.Notice
import com.replypilot.core.design.NoticeTone
import com.replypilot.core.design.SectionHeader
import com.replypilot.core.design.Spacing
import com.replypilot.domain.backup.ExportCodec
import com.replypilot.navigation.AppNavigator
import com.replypilot.navigation.Routes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val STORED = listOf(
    "Scheduled messages, recurrence rules and the send queue",
    "Auto-reply rules, templates and message sequences",
    "Contacts you add or import, with tags, groups and opt-out status",
    "An activity log of what happened and why (kept 90 days)",
    "Settings, and credentials you enter — encrypted with the Android Keystore",
)
private val NOT_STORED = listOf(
    "Incoming message text: it is matched against your rules and discarded (only a one-way fingerprint is kept briefly to catch duplicates)",
    "Message bodies in the activity log",
    "Cloud or device-transfer backups of any of this data",
    "Anything on a ReplyPilot server — there isn't one",
)

@Composable
fun DataPrivacyScreen(nav: AppNavigator) {
    val viewModel: DataPrivacyViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val demoMode by viewModel.demoMode.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var confirm by remember { mutableStateOf<String?>(null) }
    var deleteOpen by remember { mutableStateOf(false) }

    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it); viewModel.messageShown() } }
    LaunchedEffect(state.deleted) { if (state.deleted) nav.resetTo(Routes.ONBOARDING) }

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = runCatching {
                val json = viewModel.exportJson()
                withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) } ?: error("no stream") }
            }.isSuccess
            viewModel.onExported(ok)
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        stream.bufferedReader().readText().take(ExportCodec.MAX_IMPORT_BYTES + 1)
                    }
                }.getOrNull()
            }
            viewModel.parseImport(text)
        }
    }

    Scaffold(topBar = { BackTopBar("Data & privacy", nav::back) }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            SectionHeader("Stored on this device")
            STORED.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
            SectionHeader("Not stored")
            NOT_STORED.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }

            SectionHeader("Export & import")
            Text("Exports contain rules, templates and sequences — no contacts, history or credentials.", style = MaterialTheme.typography.bodySmall)
            Button(onClick = { exporter.launch("replypilot-automation.json") }, enabled = !state.busy) { Text("Export rules & templates") }
            OutlinedButton(onClick = { importer.launch(arrayOf("application/json", "text/plain", "*/*")) }, enabled = !state.busy) { Text("Import from file") }

            SectionHeader("Demo mode")
            Notice(
                "Demo data uses the simulated Demo channel. Everything it does is labelled as demo and no real message is ever sent.",
                tone = NoticeTone.SIMULATED,
            )
            Button(onClick = { confirm = "demo" }, enabled = !state.busy) { Text("Load demo data") }
            if (demoMode) OutlinedButton(onClick = { confirm = "clearDemo" }, enabled = !state.busy) { Text("Remove demo data") }

            SectionHeader("Delete")
            Button(
                onClick = { deleteOpen = true },
                enabled = !state.busy,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
            ) { Text("Delete all ReplyPilot data") }
            Spacer(Modifier.height(Spacing.xl))
        }
    }

    when (confirm) {
        "demo" -> ConfirmDialog(
            "Load demo data?", "Adds demo contacts, rules, templates, a sequence and a weekly demo schedule. All of it is labelled and can be removed later.",
            "Load", onConfirm = viewModel::loadDemo, onDismiss = { confirm = null },
        )
        "clearDemo" -> ConfirmDialog(
            "Remove demo data?", "Removes demo items and simulated history. Your own data is not touched.",
            "Remove", onConfirm = viewModel::clearDemo, onDismiss = { confirm = null }, destructive = true,
        )
    }
    state.pendingImport?.let { bundle ->
        ConfirmDialog(
            "Import automation?",
            "${bundle.rules.size} rules, ${bundle.templates.size} templates and ${bundle.sequences.size} sequences. Items with the same id are replaced. " +
                "Imported rules and sequences arrive turned off so nothing runs until you review it.",
            "Import", onConfirm = viewModel::confirmImport, onDismiss = viewModel::cancelImport,
        )
    }
    if (deleteOpen) DeleteAllDialog(onConfirm = viewModel::deleteAll, onDismiss = { deleteOpen = false })
}

@Composable
private fun DeleteAllDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    var typed by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete everything?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text("This permanently removes all schedules, pending messages, rules, templates, contacts, history, settings and saved keys from this device. It can't be undone.")
                OutlinedTextField(value = typed, onValueChange = { typed = it }, label = { Text("Type DELETE to confirm") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(); onDismiss() }, enabled = typed.trim() == "DELETE") {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
