package com.replypilot.features.settings

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.replypilot.core.design.BackTopBar
import com.replypilot.core.design.ListCard
import com.replypilot.core.design.LocalStatusColors
import com.replypilot.core.design.Notice
import com.replypilot.core.design.Pill
import com.replypilot.core.design.Spacing
import com.replypilot.core.permissions.Capability
import com.replypilot.core.permissions.CapabilityState
import com.replypilot.core.permissions.PlatformStatus
import com.replypilot.navigation.AppNavigator

/** Capabilities that improve reliability but are never required. */
val OPTIONAL_CAPABILITIES = setOf(Capability.EXACT_ALARMS, Capability.BATTERY_UNRESTRICTED)

@Composable
fun PermissionsScreen(nav: AppNavigator) {
    val viewModel: PermissionsViewModel = hiltViewModel()
    val states by viewModel.states.collectAsStateWithLifecycle()
    OnResume { viewModel.refresh() }

    Scaffold(topBar = { BackTopBar("Permissions", nav::back) }) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize().padding(horizontal = Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            item {
                Notice(
                    "ReplyPilot asks for access only when a feature needs it. It never changes system settings for you — these buttons open the right Android screen so you can decide.",
                )
            }
            items(states.filter { it.applicable }, key = { it.capability.name }) { state ->
                CapabilityCard(state, onRequested = viewModel::refresh)
            }
            item { Spacer(Modifier.height(Spacing.xl)) }
        }
    }
}

/** One permission/capability with its purpose and an action. Shared with onboarding. */
@Composable
fun CapabilityCard(state: CapabilityState, onRequested: () -> Unit) {
    val context = LocalContext.current
    val c = LocalStatusColors.current
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onRequested() }
    val optional = state.capability in OPTIONAL_CAPABILITIES

    ListCard(onClick = null) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(state.capability.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (optional) Pill("Optional", c.paused, outlined = true)
                Pill(if (state.granted) "Allowed" else "Not allowed", if (state.granted) c.success else c.review)
            }
            Text(state.capability.why, style = MaterialTheme.typography.bodyMedium)
            Text("Used for: ${state.capability.requiredFor}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val label = if (state.granted) "Manage" else "Allow"
            val action = {
                if (state.capability == Capability.POST_NOTIFICATIONS && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    openSettings(context, state.capability)
                }
            }
            if (state.granted) OutlinedButton(onClick = action) { Text(label) } else Button(onClick = action) { Text(label) }
        }
    }
}

private fun openSettings(context: Context, capability: Capability) {
    val intent = PlatformStatus(context.applicationContext).settingsIntent(capability) ?: return
    runCatching { context.startActivity(intent) }
}
