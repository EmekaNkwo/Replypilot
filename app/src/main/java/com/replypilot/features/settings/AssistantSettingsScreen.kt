package com.replypilot.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.replypilot.core.datastore.AiProviderKind
import com.replypilot.core.design.BackTopBar
import com.replypilot.core.design.ListCard
import com.replypilot.core.design.Notice
import com.replypilot.core.design.NoticeTone
import com.replypilot.core.design.SectionHeader
import com.replypilot.core.design.SimulatedPill
import com.replypilot.core.design.Spacing
import com.replypilot.navigation.AppNavigator

private fun AiProviderKind.explanation(): String = when (this) {
    AiProviderKind.NONE -> "No AI. Rule-based replies and scheduling work fully without it. AI-assisted rules are suppressed."
    AiProviderKind.MOCK -> "A deterministic, offline stand-in for trying the feature. Its output is labelled as mock and never auto-sends."
    AiProviderKind.ANTHROPIC -> "Uses Claude with your own Anthropic API key. When you use it, the message text is sent to Anthropic to generate suggestions."
}

@Composable
fun AssistantSettingsScreen(nav: AppNavigator) {
    val viewModel: AssistantSettingsViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    var key by remember { mutableStateOf("") }
    var sample by remember { mutableStateOf("hi, can u confirm ur appointment tomorrow pls") }

    Scaffold(topBar = { BackTopBar("Writing assistant", nav::back) }) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Notice(
                "AI is optional. It helps you rewrite messages, and drafts replies only for rules you explicitly set to “AI-assisted”. " +
                    "It is never used to answer every incoming message, and AI drafts wait for your approval unless you enable auto-send in Safeguards.",
            )
            SectionHeader("Provider")
            AiProviderKind.entries.forEach { kind ->
                Row(
                    Modifier.fillMaxWidth().selectable(selected = state.provider == kind, onClick = { viewModel.setProvider(kind) }, role = Role.RadioButton)
                        .padding(vertical = Spacing.sm),
                    verticalAlignment = Alignment.Top,
                ) {
                    RadioButton(selected = state.provider == kind, onClick = null)
                    Column(Modifier.padding(start = Spacing.md)) {
                        Text(kind.label, style = MaterialTheme.typography.bodyLarge)
                        Text(kind.explanation(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (state.provider == AiProviderKind.ANTHROPIC) {
                SectionHeader("API key")
                Text(
                    "Stored encrypted with the Android Keystore on this device only. It's never included in exports.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    label = { Text(if (state.keySaved) "Key saved — enter a new one to replace" else "Anthropic API key") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Button(onClick = { viewModel.saveKey(key); key = "" }, enabled = key.isNotBlank()) { Text("Save key") }
                    if (state.keySaved) OutlinedButton(onClick = viewModel::removeKey) { Text("Remove key") }
                }
                if (!state.keySaved) Notice("Without a key, the assistant is unavailable.", tone = NoticeTone.WARNING)
            }

            SectionHeader("Try it")
            OutlinedTextField(value = sample, onValueChange = { sample = it }, modifier = Modifier.fillMaxWidth(), minLines = 2, label = { Text("Message") })
            Button(onClick = { viewModel.tryIt(sample) }, enabled = !state.trying && sample.isNotBlank() && state.provider != AiProviderKind.NONE) {
                Text(if (state.trying) "Working…" else "Rewrite")
            }
            state.tryError?.let { Notice(it, tone = NoticeTone.ERROR) }
            state.tryResult?.let { result ->
                ListCard(onClick = null) {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            Text(state.tryProvider.orEmpty(), style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                            if (state.provider == AiProviderKind.MOCK) SimulatedPill(text = "Mock")
                        }
                        Text(result, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            Spacer(Modifier.height(Spacing.xl))
        }
    }
}
