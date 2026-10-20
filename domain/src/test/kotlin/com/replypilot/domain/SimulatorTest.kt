package com.replypilot.domain

import com.replypilot.domain.model.AutoReplyRule
import com.replypilot.domain.model.AutomationSettings
import com.replypilot.domain.model.MatchType
import com.replypilot.domain.model.MessageStatus
import com.replypilot.domain.model.RuleCondition
import com.replypilot.domain.rules.SuppressionReason
import com.replypilot.domain.simulation.QueueScenario
import com.replypilot.domain.simulation.QueueSimulator
import com.replypilot.domain.simulation.RuleSimulator
import com.replypilot.domain.simulation.SimulationScenario
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SimulatorTest {
    private val pricing = AutoReplyRule(
        id = "pricing", name = "Pricing inquiry",
        conditions = listOf(RuleCondition.MessageMatches(MatchType.ANY_KEYWORD, listOf("price", "much"))),
        response = "Our pricing starts at 10.",
    )
    private val settings = AutomationSettings(quietHoursEnabled = false)
    private fun scenario(text: String) = SimulationScenario(text = text, at = MONDAY_10AM_UTC, zone = ZoneOffset.UTC)

    @Test
    fun `same scenario always yields the same evaluation`() {
        val a = RuleSimulator.simulate(scenario("Hi, how much is your service?"), listOf(pricing), emptyList(), settings)
        val b = RuleSimulator.simulate(scenario("Hi, how much is your service?"), listOf(pricing), emptyList(), settings)
        assertEquals(a, b)
        assertEquals("Our pricing starts at 10.", a.replies.single().text)
        assertTrue(a.message.simulated)
    }

    @Test
    fun `scenario toggles reproduce cooldown and takeover`() {
        val cooled = RuleSimulator.simulate(scenario("price").copy(minutesSinceLastReply = 5), listOf(pricing), emptyList(), settings)
        assertEquals(SuppressionReason.COOLDOWN, cooled.blockingSuppression?.reason)
        val takeover = RuleSimulator.simulate(scenario("price").copy(takeoverActive = true), listOf(pricing), emptyList(), settings)
        assertEquals(SuppressionReason.HUMAN_TAKEOVER, takeover.blockingSuppression?.reason)
    }

    @Test
    fun `queue scenarios end in their documented states`() = runTest {
        val expected = mapOf(
            QueueScenario.SUCCESS to (MessageStatus.SUBMITTED to 1),
            QueueScenario.TRANSIENT_FAILURE to (MessageStatus.SUBMITTED to 3),
            QueueScenario.RETRIES_EXHAUSTED to (MessageStatus.FAILED to 4),
            QueueScenario.PERMANENT_FAILURE to (MessageStatus.FAILED to 1),
            QueueScenario.PERMISSION_MISSING to (MessageStatus.NEEDS_ATTENTION to 4),
            QueueScenario.QUIET_HOURS to (MessageStatus.SUBMITTED to 1),
            QueueScenario.OPTED_OUT to (MessageStatus.SKIPPED to 0),
            QueueScenario.DOUBLE_WORKER to (MessageStatus.SUBMITTED to 1),
            QueueScenario.RESTART_DURING_SEND to (MessageStatus.NEEDS_ATTENTION to 0),
        )
        expected.forEach { (scenario, outcome) ->
            val result = QueueSimulator.run(scenario)
            assertEquals("$scenario final status", outcome.first, result.finalStatus)
            assertEquals("$scenario send attempts", outcome.second, result.sendAttempts)
            assertTrue("$scenario timeline", result.timeline.isNotEmpty())
        }
    }
}
