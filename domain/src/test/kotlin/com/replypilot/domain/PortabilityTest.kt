package com.replypilot.domain

import com.replypilot.domain.backup.ExportBundle
import com.replypilot.domain.backup.ExportCodec
import com.replypilot.domain.backup.ImportParse
import com.replypilot.domain.contacts.ContactCsvImporter
import com.replypilot.domain.model.AutoReplyRule
import com.replypilot.domain.model.ChannelId
import com.replypilot.domain.model.ChannelIdentity
import com.replypilot.domain.model.Contact
import com.replypilot.domain.model.MatchType
import com.replypilot.domain.model.MessageTemplate
import com.replypilot.domain.model.RuleCondition
import com.replypilot.domain.model.TemplateCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PortabilityTest {
    @Test
    fun `export round-trips rules with every condition type`() {
        val rule = AutoReplyRule(
            id = "r1", name = "Everything", response = "Hi",
            conditions = listOf(
                RuleCondition.MessageMatches(MatchType.REGEX, listOf("^hi")),
                RuleCondition.SenderIsContact(setOf("c1")),
                RuleCondition.SenderInGroup(setOf("VIP")),
                RuleCondition.UnknownSender,
                RuleCondition.ConversationIs(com.replypilot.domain.model.ConversationKind.GROUP),
                RuleCondition.WithinWorkingHours,
                RuleCondition.OutsideWorkingHours,
                RuleCondition.OnWeekdays(setOf(1, 2)),
                RuleCondition.InDateRange("2026-01-01", "2026-12-31"),
                RuleCondition.AfterRuleExecuted("r0", 30),
                RuleCondition.NoHumanTakeover,
            ),
        )
        val bundle = ExportBundle(exportedAt = 1, rules = listOf(rule), templates = listOf(MessageTemplate("t1", "Welcome", TemplateCategory.WELCOME, "Hello")))
        val decoded = ExportCodec.decode(ExportCodec.encode(bundle))
        assertEquals(bundle, (decoded as ImportParse.Ok).bundle)
    }

    @Test
    fun `import rejects foreign, newer and malformed files`() {
        assertTrue(ExportCodec.decode("""{"format":"other","exportedAt":1}""") is ImportParse.Error)
        assertTrue(ExportCodec.decode("""{"format":"replypilot.automation","version":99,"exportedAt":1}""") is ImportParse.Error)
        assertTrue(ExportCodec.decode("not json") is ImportParse.Error)
    }

    @Test
    fun `csv import previews valid, invalid and duplicate rows`() {
        val csv = """
            name,whatsapp,email,tags,opted_out
            "Okafor, Ada",+234 800 000 0001,ada@example.com,vip;new,no
            Ben Cole,12,,,
            Chi Duru,+2348000000002,,,yes
            ,+2348000000003,,,
        """.trimIndent()
        val existing = listOf(Contact("x", "Chi", identities = listOf(ChannelIdentity(ChannelId.WHATSAPP, "+2348000000002"))))
        var n = 0
        val preview = ContactCsvImporter.preview(csv, existing, { "new-${++n}" }, 0)
        assertEquals(4, preview.rows.size)
        val ada = preview.rows[0]
        assertTrue(ada.importable)
        assertEquals("+2348000000001", ada.contact?.addressOn(ChannelId.WHATSAPP))
        assertEquals(setOf("vip", "new"), ada.contact?.tags)
        assertTrue(preview.rows[1].errors.any { it.contains("Invalid") })
        assertEquals("Chi", preview.rows[2].duplicateOf)
        assertTrue(preview.rows[3].errors.contains("Missing name"))
        assertEquals(1, preview.importable.size)
    }

    @Test
    fun `csv without an address column is rejected up front`() {
        val preview = ContactCsvImporter.preview("name,notes\nAda,hi", emptyList(), { "id" }, 0)
        assertTrue(preview.headerError != null)
    }
}
