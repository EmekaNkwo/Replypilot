package com.replypilot.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.navDeepLink
import com.replypilot.features.analytics.InsightsScreen
import com.replypilot.features.autoresponder.AutomationScreen
import com.replypilot.features.autoresponder.RuleEditorScreen
import com.replypilot.features.autoresponder.SimulatorScreen
import com.replypilot.features.contacts.ContactEditorScreen
import com.replypilot.features.contacts.ContactImportScreen
import com.replypilot.features.contacts.ContactsScreen
import com.replypilot.features.dashboard.DashboardScreen
import com.replypilot.features.onboarding.OnboardingScreen
import com.replypilot.features.scheduler.ComposerScreen
import com.replypilot.features.scheduler.ExecutionDetailScreen
import com.replypilot.features.scheduler.MessagesScreen
import com.replypilot.features.scheduler.ScheduleDetailScreen
import com.replypilot.features.sequences.SequenceDetailScreen
import com.replypilot.features.sequences.SequenceEditorScreen
import com.replypilot.features.settings.AssistantSettingsScreen
import com.replypilot.features.settings.ChannelsScreen
import com.replypilot.features.settings.DataPrivacyScreen
import com.replypilot.features.settings.PermissionsScreen
import com.replypilot.features.settings.SafeguardsScreen
import com.replypilot.features.settings.SettingsScreen
import com.replypilot.features.templates.TemplateEditorScreen

private data class Section(val label: String, val icon: ImageVector, val route: String, val matches: String)

private val sections = listOf(
    Section("Home", Icons.Filled.Home, Routes.HOME, Routes.HOME),
    Section("Messages", Icons.Filled.Schedule, Routes.messages(), "messages"),
    Section("Automation", Icons.Filled.AutoAwesome, Routes.automation(), "automation"),
    Section("Contacts", Icons.Filled.Contacts, Routes.contacts(), "contacts?"),
    Section("Insights", Icons.Filled.Insights, Routes.insights(), "insights"),
)

private fun NavBackStackEntry.str(name: String): String? = arguments?.getString(name)?.takeIf { it.isNotBlank() }

private inline fun <reified T : Enum<T>> NavBackStackEntry.tab(default: T): T =
    str("tab")?.let { value -> enumValues<T>().firstOrNull { it.name == value } } ?: default

private fun optional(vararg names: String) = names.map { navArgument(it) { type = NavType.StringType; nullable = true; defaultValue = null } }

@Composable
fun AppNavHost(startOnboarding: Boolean) {
    val controller = rememberNavController()
    val nav = remember(controller) { AppNavigator(controller) }
    val entry by controller.currentBackStackEntryAsState()
    val currentRoute = entry?.destination?.route.orEmpty()
    val showBar = sections.any { currentRoute.startsWith(it.matches) }

    Scaffold(
        bottomBar = {
            if (showBar) {
                NavigationBar {
                    sections.forEach { section ->
                        NavigationBarItem(
                            selected = currentRoute.startsWith(section.matches),
                            onClick = { nav.switchSection(section.route) },
                            icon = { Icon(section.icon, contentDescription = null) },
                            label = { Text(section.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = controller,
            startDestination = if (startOnboarding) Routes.ONBOARDING else Routes.HOME,
            modifier = Modifier.padding(bottom = padding.calculateBottomPadding()),
        ) {
            composable(Routes.ONBOARDING) {
                OnboardingScreen(onFinished = { controller.navigate(Routes.HOME) { popUpTo(Routes.ONBOARDING) { inclusive = true } } })
            }
            composable(Routes.HOME) { DashboardScreen(nav) }
            composable(
                Routes.MESSAGES, optional("tab", "filter"),
                deepLinks = listOf(navDeepLink { uriPattern = "replypilot://outbox?filter={filter}" }),
            ) { e ->
                val tab = if (e.str("tab") == null && e.str("filter") != null) MessagesTab.OUTBOX else e.tab(MessagesTab.SCHEDULED)
                MessagesScreen(nav, tab, e.str("filter"))
            }
            composable(Routes.COMPOSER, optional("scheduleId", "templateId", "duplicateOf")) { e ->
                ComposerScreen(nav, e.str("scheduleId"), e.str("templateId"), e.str("duplicateOf"))
            }
            composable(Routes.SCHEDULE) { e -> ScheduleDetailScreen(nav, e.str("id").orEmpty()) }
            composable(Routes.EXECUTION) { e -> ExecutionDetailScreen(nav, e.str("id").orEmpty()) }
            composable(Routes.AUTOMATION, optional("tab")) { e -> AutomationScreen(nav, e.tab(AutomationTab.RESPONDER)) }
            composable(Routes.RULE_EDITOR, optional("id")) { e -> RuleEditorScreen(nav, e.str("id")) }
            composable(Routes.SIMULATOR, optional("ruleId")) { e -> SimulatorScreen(nav, e.str("ruleId")) }
            composable(Routes.SEQUENCE_EDITOR, optional("id")) { e -> SequenceEditorScreen(nav, e.str("id")) }
            composable(Routes.SEQUENCE) { e -> SequenceDetailScreen(nav, e.str("id").orEmpty()) }
            composable(Routes.TEMPLATE_EDITOR, optional("id")) { e -> TemplateEditorScreen(nav, e.str("id")) }
            composable(Routes.CONTACTS, optional("tab")) { e -> ContactsScreen(nav, e.tab(ContactsTab.CONTACTS)) }
            composable(Routes.CONTACT_EDITOR, optional("id")) { e -> ContactEditorScreen(nav, e.str("id")) }
            composable(Routes.CONTACT_IMPORT) { ContactImportScreen(nav) }
            composable(Routes.INSIGHTS, optional("tab", "filter")) { e -> InsightsScreen(nav, e.tab(InsightsTab.ANALYTICS), e.str("filter")) }
            composable(Routes.SETTINGS) { SettingsScreen(nav) }
            composable(Routes.SETTINGS_CHANNELS) { ChannelsScreen(nav) }
            composable(Routes.SETTINGS_PERMISSIONS) { PermissionsScreen(nav) }
            composable(Routes.SETTINGS_SAFEGUARDS) { SafeguardsScreen(nav) }
            composable(Routes.SETTINGS_PRIVACY) { DataPrivacyScreen(nav) }
            composable(Routes.SETTINGS_ASSISTANT) { AssistantSettingsScreen(nav) }
        }
    }
}
