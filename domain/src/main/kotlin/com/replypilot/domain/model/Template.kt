package com.replypilot.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class TemplateCategory(val label: String) {
    WELCOME("Welcome"),
    OUT_OF_OFFICE("Out of office"),
    FAQ("Frequently asked questions"),
    BUSINESS_INFO("Business information"),
    APPOINTMENT_CONFIRMATION("Appointment confirmation"),
    APPOINTMENT_REMINDER("Appointment reminder"),
    PRICING("Pricing inquiry"),
    ORDER_STATUS("Order status"),
    FOLLOW_UP("Follow-up"),
    CUSTOM("Custom"),
}

@Serializable
data class MessageTemplate(
    val id: String,
    val name: String,
    val category: TemplateCategory,
    val body: String,
    val updatedAt: Long = 0,
)
