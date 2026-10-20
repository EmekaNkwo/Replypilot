package com.replypilot.core.permissions

import android.Manifest
import android.app.AlarmManager
import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.replypilot.core.accessibility.AutoSendService
import com.replypilot.core.notifications.MessageNotificationListener
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

enum class Capability(val title: String, val why: String, val requiredFor: String) {
    NOTIFICATION_ACCESS(
        "Notification access",
        "Lets ReplyPilot read new-message notifications from the apps you enable and use their Reply button. " +
            "Only apps you turn on in Connected channels are read; message text is matched and then discarded.",
        "Auto-replies on WhatsApp, Telegram, Instagram, Messenger and SMS",
    ),
    POST_NOTIFICATIONS(
        "Alerts",
        "Lets ReplyPilot tell you when a message needs your approval or must be sent manually.",
        "Manual handoff reminders and approvals",
    ),
    EXACT_ALARMS(
        "Precise timing",
        "Lets scheduled messages run at the exact minute. Without it Android may delay them by several minutes to save battery.",
        "On-the-minute scheduling (optional)",
    ),
    AUTO_SEND(
        "WhatsApp Auto-send",
        "An Accessibility service that sends the WhatsApp messages you schedule: it opens the chat, presses Send and checks the " +
            "message went out. It works only inside WhatsApp, only for your scheduled messages, and needs the phone unlocked.",
        "Sending scheduled WhatsApp messages without tapping (optional)",
    ),
    XIAOMI_BACKGROUND_START(
        "Open apps in the background (Xiaomi)",
        "Xiaomi phones block apps from opening screens while they're in the background. Auto-send needs this to open WhatsApp " +
            "at the scheduled time. In the next screen, allow “Display pop-up windows while running in the background”.",
        "Auto-send while you're not using ReplyPilot (Xiaomi, Redmi, POCO)",
    ),
    BATTERY_UNRESTRICTED(
        "Unrestricted battery",
        "Some phones stop background apps aggressively. Exempting ReplyPilot makes scheduled work more dependable. " +
            "ReplyPilot only opens the settings page; you decide.",
        "Reliable background execution (optional)",
    ),
}

data class CapabilityState(val capability: Capability, val granted: Boolean, val applicable: Boolean = true)

/** Reads permission and device state. Never changes system settings; it only builds intents for the user to act on. */
private const val MIUI_OP_BACKGROUND_START = 10021

@Singleton
class PlatformStatus @Inject constructor(@ApplicationContext private val context: Context) {

    fun notificationAccessGranted(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    fun postNotificationsGranted(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun exactAlarmsAllowed(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true
    }

    fun batteryUnrestricted(): Boolean =
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true

    fun autoSendEnabled(): Boolean {
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        val component = ComponentName(context, AutoSendService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == component }
    }

    fun isXiaomi(): Boolean = Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true)

    /**
     * Xiaomi's own "start in background" app-op (10021). Null when unknown (not Xiaomi, or the check is unavailable).
     * Android's public API can't see it, so this uses the documented-by-practice hidden AppOpsManager check.
     */
    fun xiaomiBackgroundStartAllowed(): Boolean? {
        if (!isXiaomi()) return null
        return runCatching {
            val ops = context.getSystemService(AppOpsManager::class.java)
            val check = AppOpsManager::class.java.getMethod("checkOpNoThrow", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java)
            check.invoke(ops, MIUI_OP_BACKGROUND_START, Process.myUid(), context.packageName) as Int == AppOpsManager.MODE_ALLOWED
        }.getOrNull()
    }

    fun isInstalled(packageName: String): Boolean = runCatching {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    }.getOrDefault(false)

    fun states(): List<CapabilityState> = listOf(
        CapabilityState(Capability.NOTIFICATION_ACCESS, notificationAccessGranted()),
        CapabilityState(Capability.POST_NOTIFICATIONS, postNotificationsGranted(), Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU),
        CapabilityState(Capability.EXACT_ALARMS, exactAlarmsAllowed(), Build.VERSION.SDK_INT >= Build.VERSION_CODES.S),
        CapabilityState(Capability.AUTO_SEND, autoSendEnabled()),
        CapabilityState(Capability.XIAOMI_BACKGROUND_START, xiaomiBackgroundStartAllowed() != false, applicable = isXiaomi()),
        CapabilityState(Capability.BATTERY_UNRESTRICTED, batteryUnrestricted()),
    )

    private fun xiaomiPermissionEditor(): Intent {
        val editor = Intent("miui.intent.action.APP_PERM_EDITOR")
            .setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
            .putExtra("extra_pkgname", context.packageName)
        return if (editor.resolveActivity(context.packageManager) != null) editor
        else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
    }

    /** Settings screen for a capability, or null when it is granted through a runtime permission dialog. */
    fun settingsIntent(capability: Capability): Intent? = when (capability) {
        Capability.NOTIFICATION_ACCESS -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, ComponentName(context, MessageNotificationListener::class.java).flattenToString())
        } else {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        }
        Capability.POST_NOTIFICATIONS -> null
        Capability.EXACT_ALARMS -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))
        } else {
            null
        }
        Capability.AUTO_SEND -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        Capability.XIAOMI_BACKGROUND_START -> xiaomiPermissionEditor()
        Capability.BATTERY_UNRESTRICTED -> Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    }?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
