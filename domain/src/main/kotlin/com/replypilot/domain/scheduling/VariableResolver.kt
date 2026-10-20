package com.replypilot.domain.scheduling

import com.replypilot.domain.model.AutomationSettings
import com.replypilot.domain.model.Contact
import com.replypilot.domain.model.Recipient

/** Values available to templates: business-wide settings plus the recipient's own fields. */
object VariableResolver {
    fun global(settings: AutomationSettings): Map<String, String> = buildMap {
        if (settings.businessName.isNotBlank()) put("businessName", settings.businessName)
        if (settings.supportHours.isNotBlank()) put("supportHours", settings.supportHours)
    }

    fun forRecipient(recipient: Recipient, contact: Contact?, settings: AutomationSettings): Map<String, String> = buildMap {
        putAll(global(settings))
        if (contact != null) {
            putAll(contact.templateVariables())
        } else if (!recipient.isGroup) {
            recipient.displayName.split(" ").firstOrNull()?.takeIf { it.isNotBlank() }?.let { put("firstName", it) }
        }
    }
}
