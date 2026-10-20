package com.replypilot.features.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.replypilot.BuildConfig
import com.replypilot.core.datastore.ThemeMode
import com.replypilot.core.design.BackTopBar
import com.replypilot.core.design.Notice
import com.replypilot.core.design.NoticeTone
import com.replypilot.core.design.SectionHeader
import com.replypilot.core.design.Spacing
import com.replypilot.navigation.AppNavigator
import com.replypilot.navigation.Routes

@Composable
fun SettingsScreen(nav: AppNavigator) {
    val viewModel: SettingsRootViewModel = hiltViewModel()
    val prefs by viewModel.prefs.collectAsStateWithLifecycle()
    val exactAllowed by viewModel.exactAllowed.collectAsStateWithLifecycle()
    val context = LocalContext.current
    OnResume { viewModel.refresh() }

    Scaffold(topBar = { BackTopBar("Settings", nav::back) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.lg)) {
            NavRow(Icons.Filled.Hub, "Connected channels", "What each channel can and can't do", { nav.navigate(Routes.SETTINGS_CHANNELS) })
            HorizontalDivider()
            NavRow(Icons.Filled.Security, "Permissions", "What ReplyPilot can access, and why", { nav.navigate(Routes.SETTINGS_PERMISSIONS) })
            HorizontalDivider()
            NavRow(Icons.Filled.Shield, "Quiet hours & safeguards", "Working hours, limits, opt-outs, loop protection", { nav.navigate(Routes.SETTINGS_SAFEGUARDS) })
            HorizontalDivider()
            NavRow(Icons.Filled.AutoAwesome, "Writing assistant", "Optional AI help for composing messages", { nav.navigate(Routes.SETTINGS_ASSISTANT) })
            HorizontalDivider()
            NavRow(Icons.Filled.Lock, "Data & privacy", "Export, import, demo data, delete everything", { nav.navigate(Routes.SETTINGS_PRIVACY) })

            SectionHeader("Appearance")
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                ThemeMode.entries.forEach { mode ->
                    FilterChip(
                        selected = prefs.themeMode == mode,
                        onClick = { viewModel.setTheme(mode) },
                        label = { Text(mode.name.lowercase().replaceFirstChar { it.uppercase() }) },
                    )
                }
            }

            SectionHeader("Timing")
            SwitchRow(
                title = "Precise timing",
                subtitle = "Send scheduled messages at the exact minute using an exact alarm. When off, Android may run them a few minutes late to save battery.",
                checked = prefs.preciseTiming,
                onChange = viewModel::setPreciseTiming,
            )
            if (prefs.preciseTiming && !exactAllowed) {
                Notice(
                    "Precise timing is on, but Android hasn't allowed exact alarms for ReplyPilot, so messages may still be a few minutes late.",
                    tone = NoticeTone.WARNING,
                    action = {
                        TextButton(onClick = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                runCatching {
                                    context.startActivity(
                                        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                    )
                                }
                            }
                        }) { Text("Allow exact alarms") }
                    },
                )
            }

            SectionHeader("About")
            Text("ReplyPilot ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium)
            Text(
                "You define the rules; ReplyPilot only runs what you've allowed, and you can pause everything at any time.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Spacing.xl))
        }
    }
}
