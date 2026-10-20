package com.replypilot.data.services

import com.replypilot.core.datastore.AppPreferencesRepository
import com.replypilot.domain.common.Clock
import com.replypilot.domain.model.AutoReplyRule
import com.replypilot.domain.model.ChannelId
import com.replypilot.domain.model.ChannelIdentity
import com.replypilot.domain.model.Contact
import com.replypilot.domain.model.Frequency
import com.replypilot.domain.model.MatchType
import com.replypilot.domain.model.MessageSequence
import com.replypilot.domain.model.MessageTemplate
import com.replypilot.domain.model.Recipient
import com.replypilot.domain.model.RuleCondition
import com.replypilot.domain.model.ScheduleSpec
import com.replypilot.domain.model.ScheduledMessage
import com.replypilot.domain.model.SequenceStep
import com.replypilot.domain.model.TemplateCategory
import com.replypilot.domain.repository.ContactRepository
import com.replypilot.domain.repository.RuleRepository
import com.replypilot.domain.repository.SequenceRepository
import com.replypilot.domain.repository.SettingsRepository
import com.replypilot.domain.repository.TemplateRepository
import com.replypilot.domain.scheduling.SaveResult
import com.replypilot.domain.scheduling.ScheduleService
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Seeds clearly-labelled demo content on the simulated Demo channel. Every id starts with [PREFIX] and every
 * schedule is flagged isDemo, so demo data can be removed without touching anything the user created.
 */
@Singleton
class DemoSeeder @Inject constructor(
    private val contacts: ContactRepository,
    private val templates: TemplateRepository,
    private val rules: RuleRepository,
    private val sequences: SequenceRepository,
    private val schedules: ScheduleService,
    private val settings: SettingsRepository,
    private val preferences: AppPreferencesRepository,
    private val clock: Clock,
) {
    suspend fun seed() {
        val now = clock.now()
        val zone = clock.zone()
        settings.update { if (it.businessName.isBlank()) it.copy(businessName = "Brightside Studio", supportHours = "Mon–Fri, 9am–5pm") else it }

        listOf(
            Contact("${PREFIX}ada", "Ada", "Okafor", listOf(ChannelIdentity(ChannelId.DEMO, "Ada Okafor")), ChannelId.DEMO,
                tags = setOf("client"), groups = setOf("VIP"), customFields = mapOf("appointmentDate" to "Thursday", "appointmentTime" to "10:00"),
                notes = "Demo contact", createdAt = now, updatedAt = now),
            Contact("${PREFIX}ben", "Ben", "Cole", listOf(ChannelIdentity(ChannelId.DEMO, "Ben Cole")), ChannelId.DEMO,
                tags = setOf("prospect"), notes = "Demo contact", createdAt = now, updatedAt = now),
            Contact("${PREFIX}chi", "Chi", "Duru", listOf(ChannelIdentity(ChannelId.DEMO, "Chi Duru")), ChannelId.DEMO,
                optedOut = true, optedOutAt = now, notes = "Demo contact who has opted out", createdAt = now, updatedAt = now),
        ).forEach { contacts.upsert(it) }

        listOf(
            MessageTemplate("${PREFIX}pricing", "Pricing (demo)", TemplateCategory.PRICING, "Hi {{firstName|there}}! Our packages start at ₦25,000. Want the full price list?", now),
            MessageTemplate("${PREFIX}away", "Away (demo)", TemplateCategory.OUT_OF_OFFICE, "Thanks for reaching out to {{businessName}}. We're away now and will reply {{supportHours}}.", now),
            MessageTemplate("${PREFIX}reminder", "Reminder (demo)", TemplateCategory.APPOINTMENT_REMINDER, "Hello {{firstName}}, a reminder about your appointment on {{appointmentDate}} at {{appointmentTime}}.", now),
        ).forEach { templates.upsert(it) }

        listOf(
            AutoReplyRule("${PREFIX}pricing", "Pricing inquiry (demo)", channels = setOf(ChannelId.DEMO),
                conditions = listOf(RuleCondition.MessageMatches(MatchType.ANY_KEYWORD, listOf("price", "cost", "how much", "rates"))),
                response = "", templateId = "${PREFIX}pricing", priority = 10, cooldownMinutes = 60, createdAt = now, updatedAt = now),
            AutoReplyRule("${PREFIX}hours", "Opening hours (demo)", channels = setOf(ChannelId.DEMO),
                conditions = listOf(RuleCondition.MessageMatches(MatchType.CONTAINS, listOf("open", "hours"))),
                response = "We're open {{supportHours}}.", priority = 5, cooldownMinutes = 120, createdAt = now, updatedAt = now),
            AutoReplyRule("${PREFIX}away", "After hours (demo)", channels = setOf(ChannelId.DEMO),
                conditions = listOf(RuleCondition.MessageMatches(MatchType.ANY_MESSAGE, emptyList()), RuleCondition.OutsideWorkingHours),
                response = "", templateId = "${PREFIX}away", priority = 1, cooldownMinutes = 12 * 60, createdAt = now, updatedAt = now),
        ).forEach { rules.upsert(it) }

        sequences.upsertSequence(
            MessageSequence("${PREFIX}followup", "Prospect follow-up (demo)", ChannelId.DEMO, timeZone = zone.id,
                steps = listOf(
                    SequenceStep(0, "10:00", "Hi {{firstName}}, thanks for your interest in {{businessName}}!"),
                    SequenceStep(2, "10:00", "Here's a short guide to how we work. Any questions?"),
                    SequenceStep(5, "10:00", "Just checking in — would you like to book a call?", requiresReview = true),
                ),
                createdAt = now, updatedAt = now),
        )

        val tomorrow = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().plusDays(1)
        val result = schedules.save(
            ScheduledMessage(
                id = "", title = "Weekly check-in (demo)", channel = ChannelId.DEMO,
                recipients = listOf(Recipient("Ada Okafor", "Ada Okafor", "${PREFIX}ada")),
                body = "Hi {{firstName}}, here's your weekly update from {{businessName}}.",
                spec = ScheduleSpec(tomorrow.toString(), "09:00", zone.id, frequency = Frequency.WEEKLY, weekdays = setOf(tomorrow.dayOfWeek.value)),
                isDemo = true,
            ),
        )
        check(result is SaveResult.Saved || (result is SaveResult.Invalid && result.issues.any { it.message.contains("already scheduled") })) {
            "Demo schedule could not be created: $result"
        }
        preferences.setDemoMode(true)
    }

    companion object {
        const val PREFIX = "demo-"
    }
}
