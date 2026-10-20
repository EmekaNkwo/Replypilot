package com.replypilot.features.scheduler

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.replypilot.core.design.ListCard
import com.replypilot.core.design.Notice
import com.replypilot.core.design.NoticeTone
import com.replypilot.core.design.SimulatedPill
import com.replypilot.core.design.Spacing
import com.replypilot.domain.assistant.AssistOperation

/** Optional writing help. Suggestions are never applied without the user choosing one. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AssistantSheet(
    state: AssistantUi,
    hasText: Boolean,
    onRun: (AssistOperation) -> Unit,
    onUse: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text("Writing assistant", style = MaterialTheme.typography.titleLarge)
            when {
                !state.checked -> CircularProgressIndicator()
                !state.available -> {
                    Notice(
                        "The writing assistant is off. You can turn on Claude with your own API key, or a local mock for testing.",
                        title = "Not configured",
                        action = { TextButton(onClick = onOpenSettings) { Text("Assistant settings") } },
                    )
                    return@Column
                }
                else -> Text(
                    "Using ${state.providerName ?: "assistant"}. Placeholders like {{firstName}} are kept. Nothing changes until you pick a suggestion.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (!hasText) {
                Text("Write a message first.", style = MaterialTheme.typography.bodyMedium)
                return@Column
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                AssistOperation.entries.forEach { op ->
                    AssistChip(onClick = { onRun(op) }, label = { Text(op.label) }, enabled = state.running == null)
                }
            }
            if (state.running != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    CircularProgressIndicator(Modifier.padding(4.dp))
                    Text("${state.running.label}…")
                }
            }
            state.error?.let { Notice(it, tone = NoticeTone.ERROR) }
            state.result?.let { result ->
                if (result.isMock) SimulatedPill(text = "Mock — not AI")
                result.suggestions.forEach { suggestion ->
                    ListCard(onClick = null) {
                        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            Text(suggestion, style = MaterialTheme.typography.bodyMedium)
                            Button(onClick = { onUse(suggestion) }) { Text("Use this") }
                        }
                    }
                }
            }
        }
    }
}
