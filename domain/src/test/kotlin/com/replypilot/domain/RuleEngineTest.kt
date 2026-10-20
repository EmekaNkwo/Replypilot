package com.replypilot.domain

import com.replypilot.domain.common.Fingerprints
import com.replypilot.domain.common.MINUTE_MS
import com.replypilot.domain.model.AutoReplyRule
import com.replypilot.domain.model.AutomationSettings
import com.replypilot.domain.model.ChannelId
import com.replypilot.domain.model.ChannelIdentity
import com.replypilot.domain.model.ConditionCombinator
import com.replypilot.domain.model.Contact
import com.replypilot.domain.model.IncomingMessage
import com.replypilot.domain.model.MatchType
import com.replypilot.domain.model.ReplyRecord
import com.replypilot.domain.model.RuleCondition
import com.replypilot.domain.model.TimeWindow
import com.replypilot.domain.rules.RegexGuard
import com.replypilot.domain.rules.RuleContext
import com.replypilot.domain.rules.RuleEngine
import com.replypilot.domain.rules.SuppressionReason
import java.time.LocalDateTime
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleEngineTest {
    private val now = MONDAY_10AM_UTC
    private val ada = Contact("c1", "Ada", "Okafor", identities = listOf(ChannelIdentity(ChannelId.WHATSAPP, "+2348000000001")), groups = setOf("VIP"))

    private fun message(text: String, sender: String = "Ada Okafor", group: Boolean = false, self: Boolean = false) = IncomingMessage(
        id = "m1", channel = ChannelId.WHATSAPP, conversationKey = sender, senderName = sender, text = text,
        isGroup = group, receivedAt = now, isFromSelf = self,
    )

    private fun context(
        settings: AutomationSettings = AutomationSettings(quietHoursEnabled = false),
        contact: Contact? = null,
        replies: List<ReplyRecord> = emptyList(),
        at: Long = now,
        canReply: Boolean = true,
        duplicate: Boolean = false,
        takeover: Boolean = false,
    ) = RuleContext(at, ZoneOffset.UTC, settings, contact, replies, duplicate, takeover, canReply, sentToday = 0, sentTodayOnChannel = 0)

    private fun rule(
        id: String,
        type: MatchType = MatchType.CONTAINS,
        patterns: List<String> = listOf("price"),
        priority: Int = 0,
        extra: List<RuleCondition> = emptyList(),
        response: String = "Reply from $id",
        stop: Boolean = true,
        cooldown: Int = 60,
        combinator: ConditionCombinator = ConditionCombinator.ALL,
    ) = AutoReplyRule(
        id = id, name = id, conditions = listOf(RuleCondition.MessageMatches(type, patterns)) + extra, response = response,
        priority = priority, stopProcessing = stop, cooldownMinutes = cooldown, combinator = combinator,
    )

    @Test
    fun `match types behave as described`() {
        val ctx = context()
        fun matches(type: MatchType, pattern: String, text: String) =
            RuleEngine.evaluate(message(text), listOf(rule("r", type, listOf(pattern))), ctx).wouldSend
        assertTrue(matches(MatchType.EXACT, "hours", "  Hours "))
        assertFalse(matches(MatchType.EXACT, "hours", "your hours?"))
        assertTrue(matches(MatchType.CONTAINS, "price", "What's the PRICE?"))
        assertTrue(matches(MatchType.STARTS_WITH, "hello", "Hello there"))
        assertFalse(matches(MatchType.STARTS_WITH, "hello", "Oh hello"))
        assertTrue(matches(MatchType.ANY_KEYWORD, "cost", "how much does it cost?"))
        assertFalse("keywords match whole words only", matches(MatchType.ANY_KEYWORD, "cost", "costume party"))
        assertTrue(matches(MatchType.REGEX, "order\\s*#?\\d{4}", "where is order #1234"))
    }

    @Test
    fun `unsafe or invalid regex never matches and never throws`() {
        assertTrue(RegexGuard.check("(a+)+$") is RegexGuard.Check.Invalid)
        assertTrue(RegexGuard.check("([") is RegexGuard.Check.Invalid)
        val evaluation = RuleEngine.evaluate(message("aaaaaaaaaaaaaaaaaaaaaaaaaaaaab"), listOf(rule("r", MatchType.REGEX, listOf("(a+)+$"))), context())
        assertFalse(evaluation.wouldSend)
        assertTrue(evaluation.traces.single().checks.last().detail.contains("invalid"))
    }

    @Test
    fun `higher priority wins and stop-processing ends evaluation`() {
        val rules = listOf(rule("low", priority = 1), rule("high", priority = 5))
        val evaluation = RuleEngine.evaluate(message("price?"), rules, context())
        assertEquals("high", evaluation.replies.single().rule.id)
        assertEquals(listOf("high"), evaluation.traces.map { it.rule.id })
    }

    @Test
    fun `continue-processing lets lower rules also reply`() {
        val rules = listOf(rule("high", priority = 5, stop = false), rule("low", priority = 1))
        val evaluation = RuleEngine.evaluate(message("price?"), rules, context())
        assertEquals(listOf("high", "low"), evaluation.replies.map { it.rule.id })
    }

    @Test
    fun `all versus any combinators`() {
        val outside = listOf<RuleCondition>(RuleCondition.OutsideWorkingHours)
        val all = rule("all", extra = outside, combinator = ConditionCombinator.ALL)
        val any = rule("any", extra = outside, combinator = ConditionCombinator.ANY)
        // 10:00 on Monday is inside working hours.
        assertFalse(RuleEngine.evaluate(message("price"), listOf(all), context()).wouldSend)
        assertTrue(RuleEngine.evaluate(message("price"), listOf(any), context()).wouldSend)
    }

    @Test
    fun `working hours condition uses settings and local time`() {
        val outsideRule = rule("ooo", MatchType.ANY_MESSAGE, emptyList(), extra = listOf(RuleCondition.OutsideWorkingHours))
        val evening = LocalDateTime.of(2026, 1, 5, 19, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        assertFalse(RuleEngine.evaluate(message("hi"), listOf(outsideRule), context()).wouldSend)
        assertTrue(RuleEngine.evaluate(message("hi"), listOf(outsideRule), context(at = evening)).wouldSend)
    }

    @Test
    fun `contact and group conditions need an identified sender`() {
        val vip = rule("vip", MatchType.ANY_MESSAGE, emptyList(), extra = listOf(RuleCondition.SenderInGroup(setOf("vip"))))
        val unknown = rule("unknown", MatchType.ANY_MESSAGE, emptyList(), extra = listOf(RuleCondition.UnknownSender))
        assertTrue(RuleEngine.evaluate(message("hi"), listOf(vip), context(contact = ada)).wouldSend)
        assertFalse(RuleEngine.evaluate(message("hi"), listOf(vip), context()).wouldSend)
        assertTrue(RuleEngine.evaluate(message("hi"), listOf(unknown), context()).wouldSend)
        assertFalse(RuleEngine.evaluate(message("hi"), listOf(unknown), context(contact = ada)).wouldSend)
    }

    @Test
    fun `rule channel filter is respected`() {
        val telegramOnly = rule("tg").copy(channels = setOf(ChannelId.TELEGRAM))
        assertFalse(RuleEngine.evaluate(message("price"), listOf(telegramOnly), context()).firstMatch != null)
    }

    @Test
    fun `cooldown suppresses repeat replies in the same conversation`() {
        val r = rule("r", cooldown = 60)
        val recent = ReplyRecord("r", ChannelId.WHATSAPP, "Ada Okafor", "hash", now - 10 * MINUTE_MS)
        val evaluation = RuleEngine.evaluate(message("price"), listOf(r), context(replies = listOf(recent)))
        assertFalse(evaluation.wouldSend)
        assertEquals(SuppressionReason.COOLDOWN, evaluation.blockingSuppression?.reason)
        val other = RuleEngine.evaluate(message("price", sender = "Ben"), listOf(r), context(replies = listOf(recent)))
        assertTrue("cooldown is per conversation", other.wouldSend)
    }

    @Test
    fun `quiet hours suppress auto-replies when configured`() {
        val settings = AutomationSettings(quietHoursEnabled = true, quietHours = TimeWindow("09:00", "11:00"))
        val evaluation = RuleEngine.evaluate(message("price"), listOf(rule("r")), context(settings = settings))
        assertEquals(SuppressionReason.QUIET_HOURS, evaluation.blockingSuppression?.reason)
        assertTrue("the matching rule is still shown", evaluation.firstMatch != null)
    }

    @Test
    fun `opt-out keyword is detected and suppresses replies`() {
        val evaluation = RuleEngine.evaluate(message("STOP"), listOf(rule("any", MatchType.ANY_MESSAGE, emptyList())), context())
        assertTrue(evaluation.optOutDetected)
        assertEquals(SuppressionReason.OPT_OUT_REQUEST, evaluation.blockingSuppression?.reason)
        assertFalse(RuleEngine.evaluate(message("please stop by tomorrow"), emptyList(), context()).optOutDetected)
    }

    @Test
    fun `opted-out contacts never get replies`() {
        val evaluation = RuleEngine.evaluate(message("price"), listOf(rule("r")), context(contact = ada.copy(optedOut = true)))
        assertEquals(SuppressionReason.OPTED_OUT, evaluation.blockingSuppression?.reason)
    }

    @Test
    fun `own messages and echoed replies are ignored`() {
        val self = RuleEngine.evaluate(message("price", self = true), listOf(rule("r")), context())
        assertEquals(SuppressionReason.OWN_MESSAGE, self.blockingSuppression?.reason)

        val echo = ReplyRecord("r", ChannelId.WHATSAPP, "Ada Okafor", Fingerprints.of(Fingerprints.normalizeText("Our price is 10")), now - MINUTE_MS)
        val evaluation = RuleEngine.evaluate(message("Our price is 10"), listOf(rule("r", cooldown = 0)), context(replies = listOf(echo)))
        assertEquals(SuppressionReason.OWN_MESSAGE, evaluation.blockingSuppression?.reason)
    }

    @Test
    fun `rapid back-and-forth is treated as a loop`() {
        val replies = (1..3).map { ReplyRecord("r$it", ChannelId.WHATSAPP, "Ada Okafor", "h$it", now - it * MINUTE_MS) }
        val evaluation = RuleEngine.evaluate(message("price"), listOf(rule("r", cooldown = 0)), context(replies = replies))
        assertEquals(SuppressionReason.LOOP_DETECTED, evaluation.blockingSuppression?.reason)
    }

    @Test
    fun `duplicate events, takeover and group chats are suppressed`() {
        val rules = listOf(rule("r"))
        assertEquals(SuppressionReason.DUPLICATE_EVENT, RuleEngine.evaluate(message("price"), rules, context(duplicate = true)).blockingSuppression?.reason)
        assertEquals(SuppressionReason.HUMAN_TAKEOVER, RuleEngine.evaluate(message("price"), rules, context(takeover = true)).blockingSuppression?.reason)
        assertEquals(SuppressionReason.GROUPS_DISABLED, RuleEngine.evaluate(message("price", group = true), rules, context()).blockingSuppression?.reason)
    }

    @Test
    fun `replies with unresolved variables are not sent`() {
        val r = rule("r", response = "Your slot is {{appointmentTime}}")
        val evaluation = RuleEngine.evaluate(message("price"), listOf(r), context())
        assertEquals(SuppressionReason.UNRESOLVED_VARIABLES, evaluation.blockingSuppression?.reason)
    }

    @Test
    fun `automatic rule on a channel that cannot reply is suppressed`() {
        val evaluation = RuleEngine.evaluate(message("price"), listOf(rule("r")), context(canReply = false))
        assertEquals(SuppressionReason.CHANNEL_CANNOT_REPLY, evaluation.blockingSuppression?.reason)
    }

    @Test
    fun `response personalises with contact or sender name`() {
        val r = rule("r", response = "Hi {{firstName}}!")
        assertEquals("Hi Ada!", RuleEngine.evaluate(message("price"), listOf(r), context(contact = ada)).replies.single().text)
        assertEquals("Hi Ben!", RuleEngine.evaluate(message("price", sender = "Ben Cole"), listOf(r), context()).replies.single().text)
    }

    @Test
    fun `disabled rules are ignored`() {
        val evaluation = RuleEngine.evaluate(message("price"), listOf(rule("r").copy(enabled = false)), context())
        assertNull(evaluation.firstMatch)
    }
}
