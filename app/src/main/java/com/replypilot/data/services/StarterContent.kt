package com.replypilot.data.services

import com.replypilot.domain.model.MessageTemplate
import com.replypilot.domain.model.TemplateCategory

/** Starter templates the user can add with one tap. Real content, no demo flags. */
object StarterContent {
    fun templates(now: Long): List<MessageTemplate> = listOf(
        MessageTemplate("starter-welcome", "Welcome", TemplateCategory.WELCOME,
            "Hi {{firstName|there}}, thanks for getting in touch with {{businessName|us}}! How can we help?", now),
        MessageTemplate("starter-away", "Away / out of office", TemplateCategory.OUT_OF_OFFICE,
            "Thanks for your message. We're away right now and will reply during {{supportHours|business hours}}.", now),
        MessageTemplate("starter-hours", "Opening hours", TemplateCategory.BUSINESS_INFO,
            "Our hours are {{supportHours|Monday to Friday, 9am to 5pm}}.", now),
        MessageTemplate("starter-pricing", "Pricing inquiry", TemplateCategory.PRICING,
            "Hi {{firstName|there}}, thanks for asking about pricing. Could you tell us a little about what you need so we can give you an accurate quote?", now),
        MessageTemplate("starter-confirm", "Appointment confirmation", TemplateCategory.APPOINTMENT_CONFIRMATION,
            "Hi {{firstName}}, your appointment on {{appointmentDate}} at {{appointmentTime}} is confirmed.", now),
        MessageTemplate("starter-reminder", "Appointment reminder", TemplateCategory.APPOINTMENT_REMINDER,
            "Hello {{firstName}}, just a reminder about your appointment on {{appointmentDate}} at {{appointmentTime}}.", now),
        MessageTemplate("starter-order", "Order status", TemplateCategory.ORDER_STATUS,
            "Hi {{firstName|there}}, thanks for checking in. We're looking into your order and will update you shortly.", now),
        MessageTemplate("starter-followup", "Follow-up", TemplateCategory.FOLLOW_UP,
            "Hi {{firstName|there}}, just following up on our last conversation. Is there anything else we can help with?", now),
    )
}
