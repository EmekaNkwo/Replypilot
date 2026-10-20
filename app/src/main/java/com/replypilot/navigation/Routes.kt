package com.replypilot.navigation

import android.net.Uri
import androidx.navigation.NavHostController

/** Every destination in one place. Feature screens navigate only through [AppNavigator]. */
object Routes {
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
    const val MESSAGES = "messages?tab={tab}&filter={filter}"
    const val COMPOSER = "composer?scheduleId={scheduleId}&templateId={templateId}&duplicateOf={duplicateOf}"
    const val SCHEDULE = "schedule/{id}"
    const val EXECUTION = "execution/{id}"
    const val AUTOMATION = "automation?tab={tab}"
    const val RULE_EDITOR = "rule?id={id}"
    const val SIMULATOR = "simulator?ruleId={ruleId}"
    const val SEQUENCE_EDITOR = "sequence/edit?id={id}"
    const val SEQUENCE = "sequence/{id}"
    const val TEMPLATE_EDITOR = "template?id={id}"
    const val CONTACTS = "contacts?tab={tab}"
    const val CONTACT_EDITOR = "contact?id={id}"
    const val CONTACT_IMPORT = "contacts/import"
    const val INSIGHTS = "insights?tab={tab}&filter={filter}"
    const val SETTINGS = "settings"
    const val SETTINGS_CHANNELS = "settings/channels"
    const val SETTINGS_PERMISSIONS = "settings/permissions"
    const val SETTINGS_SAFEGUARDS = "settings/safeguards"
    const val SETTINGS_PRIVACY = "settings/privacy"
    const val SETTINGS_ASSISTANT = "settings/assistant"

    private fun q(value: String?) = value?.let(Uri::encode).orEmpty()

    fun messages(tab: MessagesTab = MessagesTab.SCHEDULED, filter: String? = null) = "messages?tab=${tab.name}&filter=${q(filter)}"
    fun composer(scheduleId: String? = null, templateId: String? = null, duplicateOf: String? = null) =
        "composer?scheduleId=${q(scheduleId)}&templateId=${q(templateId)}&duplicateOf=${q(duplicateOf)}"
    fun schedule(id: String) = "schedule/${Uri.encode(id)}"
    fun execution(id: String) = "execution/${Uri.encode(id)}"
    fun automation(tab: AutomationTab = AutomationTab.RESPONDER) = "automation?tab=${tab.name}"
    fun ruleEditor(id: String? = null) = "rule?id=${q(id)}"
    fun simulator(ruleId: String? = null) = "simulator?ruleId=${q(ruleId)}"
    fun sequenceEditor(id: String? = null) = "sequence/edit?id=${q(id)}"
    fun sequence(id: String) = "sequence/${Uri.encode(id)}"
    fun templateEditor(id: String? = null) = "template?id=${q(id)}"
    fun contacts(tab: ContactsTab = ContactsTab.CONTACTS) = "contacts?tab=${tab.name}"
    fun contactEditor(id: String? = null) = "contact?id=${q(id)}"
    fun insights(tab: InsightsTab = InsightsTab.ANALYTICS, filter: String? = null) = "insights?tab=${tab.name}&filter=${q(filter)}"
}

enum class MessagesTab(val title: String) { SCHEDULED("Scheduled"), CALENDAR("Calendar"), OUTBOX("Outbox"), SENT("Sent") }
enum class AutomationTab(val title: String) { RESPONDER("Auto-reply"), RULES("Rules"), SEQUENCES("Sequences"), TEMPLATES("Templates") }
enum class ContactsTab(val title: String) { CONTACTS("Contacts"), GROUPS("Groups"), PERSONALIZATION("Personalization") }
enum class InsightsTab(val title: String) { ANALYTICS("Analytics"), DELIVERY("Delivery"), ACTIVITY("Activity") }

/**
 * Filters understood by list screens. Dashboard metrics deep-link with these values.
 * Outbox: "due_today", "pending", "failed", "attention", "review". Sent: "today". Activity: "incoming", "suppressed", "failed".
 */
object Filters {
    const val DUE_TODAY = "due_today"
    const val PENDING = "pending"
    const val FAILED = "failed"
    const val ATTENTION = "attention"
    const val REVIEW = "review"
    const val TODAY = "today"
    const val INCOMING = "incoming"
    const val SUPPRESSED = "suppressed"
}

class AppNavigator(private val controller: NavHostController) {
    /**
     * Links into another bottom-bar section (e.g. a dashboard tile opening Insights) must switch sections
     * rather than stack on the current one; otherwise the section's saved back stack ends with a foreign
     * screen and its bottom-bar button reopens that screen forever.
     */
    fun navigate(route: String) = if (isSectionRoute(route)) {
        controller.navigate(route) {
            popUpTo(Routes.HOME) { saveState = true }
            launchSingleTop = true
        }
    } else {
        controller.navigate(route) { launchSingleTop = true }
    }

    private fun isSectionRoute(route: String): Boolean =
        route == Routes.HOME || SECTION_PREFIXES.any { route.startsWith(it) }

    private companion object {
        val SECTION_PREFIXES = listOf("messages?", "automation?", "contacts?", "insights?")
    }
    fun back() {
        if (!controller.popBackStack()) controller.navigate(Routes.HOME)
    }

    /** Starts over at [route], clearing the whole back stack (used after deleting all data). */
    fun resetTo(route: String) = controller.navigate(route) {
        popUpTo(controller.graph.id) { inclusive = true }
    }

    /** Switches bottom-bar sections without stacking duplicates. */
    fun switchSection(route: String) = controller.navigate(route) {
        popUpTo(Routes.HOME) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
