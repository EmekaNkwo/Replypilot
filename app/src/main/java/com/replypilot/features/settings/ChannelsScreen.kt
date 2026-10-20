package com.replypilot.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.replypilot.core.design.AvailabilityPill
import com.replypilot.core.design.BackTopBar
import com.replypilot.core.design.ChannelAvatar
import com.replypilot.core.design.ListCard
import com.replypilot.core.design.LocalStatusColors
import com.replypilot.core.design.Notice
import com.replypilot.core.design.NoticeTone
import com.replypilot.core.design.SectionHeader
import com.replypilot.core.design.Spacing
import com.replypilot.domain.model.AvailabilityState
import com.replypilot.domain.model.ChannelCapability
import com.replypilot.domain.model.ChannelId
import com.replypilot.domain.model.ChannelStatus
import com.replypilot.integrations.ChannelCatalog
import com.replypilot.navigation.AppNavigator

val DEVICE_CHANNELS = setOf(ChannelId.WHATSAPP, ChannelId.TELEGRAM, ChannelId.INSTAGRAM, ChannelId.MESSENGER, ChannelId.SMS)

@Composable
fun ChannelsScreen(nav: AppNavigator) {
    val viewModel: ChannelsViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    OnResume { viewModel.refresh() }
    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); viewModel.messageShown() }
    }

    Scaffold(topBar = { BackTopBar("Connected channels", nav::back) }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize().padding(horizontal = Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            item {
                Notice(
                    "A channel is only “Ready” when ReplyPilot can actually use it on this device. Each card lists exactly what works and what doesn't.",
                )
            }
            if (state.loading) item { CircularProgressIndicator(Modifier.padding(Spacing.xl)) }
            items(state.statuses, key = { it.id.name }) { status ->
                ChannelCard(
                    status = status,
                    enabled = status.id in state.enabled,
                    onToggle = { viewModel.setEnabled(status.id, it) },
                ) {
                    if (status.id == ChannelId.WHATSAPP_BUSINESS) {
                        RelayConfig(state, viewModel)
                    }
                    if (status.id == ChannelId.WHATSAPP) {
                        AutoSendSetup(enabled = state.autoSendEnabled)
                    }
                }
            }
            item { SectionHeader("Planned integrations") }
            items(ChannelCatalog.planned, key = { it.title }) { planned ->
                ListCard(onClick = null) {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(planned.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            AvailabilityPill(AvailabilityState.PLANNED)
                        }
                        Text(planned.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Not built yet — it isn't connected and nothing is sent through it.", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            item { Spacer(Modifier.height(Spacing.xl)) }
        }
    }
}

@Composable
private fun ChannelCard(status: ChannelStatus, enabled: Boolean, onToggle: (Boolean) -> Unit, extra: @Composable () -> Unit) {
    val descriptor = status.descriptor
    ListCard(onClick = null) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                ChannelAvatar(status.id)
                Column(Modifier.weight(1f)) {
                    Text(status.id.displayName, style = MaterialTheme.typography.titleMedium)
                    Text(descriptor.mode.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                AvailabilityPill(status.availability)
            }
            Text(status.detail, style = MaterialTheme.typography.bodySmall)
            if (status.id in DEVICE_CHANNELS && status.availability != AvailabilityState.APP_NOT_INSTALLED) {
                SwitchRow(
                    title = "Use for auto-replies",
                    subtitle = "Lets ReplyPilot read new-message notifications from ${status.id.displayName} (sender name, chat name and the latest message) to match your rules. Text is not stored.",
                    checked = enabled,
                    onChange = onToggle,
                )
            }
            HorizontalDivider()
            Text("Capabilities", style = MaterialTheme.typography.labelLarge)
            ChannelCapability.entries.forEach { capability ->
                CapabilityLine(capability.label, descriptor.supports(capability))
            }
            if (descriptor.limitations.isNotEmpty()) {
                Text("Limitations", style = MaterialTheme.typography.labelLarge)
                descriptor.limitations.forEach {
                    Text("• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            extra()
        }
    }
}

@Composable
private fun CapabilityLine(label: String, supported: Boolean) {
    val c = LocalStatusColors.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Icon(
            if (supported) Icons.Filled.Check else Icons.Filled.Close,
            contentDescription = if (supported) "Supported" else "Not supported",
            tint = if (supported) c.success else c.paused,
            modifier = Modifier.padding(2.dp),
        )
        Text(label, style = MaterialTheme.typography.bodySmall, color = if (supported) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RelayConfig(state: ChannelsUiState, viewModel: ChannelsViewModel) {
    var url by rememberSaveable(state.relayUrl) { mutableStateOf(state.relayUrl) }
    var token by remember { mutableStateOf("") }
    val urlError = ChannelsViewModel.relayUrlError(url.trim())

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        HorizontalDivider()
        Text("Relay setup", style = MaterialTheme.typography.labelLarge)
        Notice(
            "The WhatsApp Business Platform is Meta's business API. A standard WhatsApp or WhatsApp Business app account is not the Business Platform — use the on-device WhatsApp channel for that. " +
                "Your Meta access token stays on your own relay server; this phone stores only a relay device token, encrypted. Setup guide: docs/integrations/whatsapp-business-relay.md.",
            tone = NoticeTone.INFO,
        )
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("Relay URL") },
            placeholder = { Text("https://relay.example.com") },
            isError = urlError != null,
            supportingText = urlError?.let { { Text(it) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            label = { Text(if (state.relayTokenSaved) "Device token (saved — enter to replace)" else "Device token") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Button(onClick = { viewModel.saveRelay(url, token); token = "" }, enabled = urlError == null && url.isNotBlank()) { Text("Save") }
            OutlinedButton(onClick = viewModel::testConnection, enabled = state.relayTokenSaved && state.relayUrl.isNotBlank() && !state.testing) {
                Text(if (state.testing) "Testing…" else "Test connection")
            }
            if (state.relayTokenSaved || state.relayUrl.isNotBlank()) {
                OutlinedButton(onClick = viewModel::removeRelay) { Text("Remove") }
            }
        }
        state.testResult?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}
