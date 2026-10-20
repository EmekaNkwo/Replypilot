package com.replypilot.domain

import com.replypilot.domain.common.HOUR_MS
import com.replypilot.domain.model.ChannelId
import com.replypilot.domain.model.ChannelIdentity
import com.replypilot.domain.model.Contact
import com.replypilot.domain.model.EnrollmentStatus
import com.replypilot.domain.model.MessageSequence
import com.replypilot.domain.model.MessageStatus
import com.replypilot.domain.model.Recipient
import com.replypilot.domain.model.SequenceStep
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SequenceServiceTest {
    private val sequence = MessageSequence(
        id = "s1", name = "Prospect follow-up", channel = ChannelId.DEMO, timeZone = "UTC",
        steps = listOf(
            SequenceStep(0, "12:00", "Hi {{firstName}}, thanks for your interest."),
            SequenceStep(2, "12:00", "Here is some helpful information."),
            SequenceStep(5, "12:00", "Any questions?", requiresReview = true),
        ),
    )

    @Test
    fun `enrolling creates one execution per step at the configured offsets`() = runTest {
        val h = TestHarness()
        h.sequencesRepo.upsertSequence(sequence)
        val result = h.sequenceService.enroll("s1", listOf(Recipient("ada", "Ada Okafor")))
        assertEquals(1, result.enrolled.size)
        val rows = h.executions.items.sortedBy { it.occurrenceIndex }
        assertEquals(listOf(0L, 48L, 120L), rows.map { (it.plannedAt - (MONDAY_10AM_UTC + 2 * HOUR_MS)) / HOUR_MS })
        assertEquals("Hi Ada, thanks for your interest.", rows.first().body)
        assertEquals(MessageStatus.AWAITING_REVIEW, rows.last().status)
    }

    @Test
    fun `steps run in order and the enrollment completes`() = runTest {
        val h = TestHarness()
        h.sequencesRepo.upsertSequence(sequence.copy(steps = sequence.steps.take(2)))
        h.sequenceService.enroll("s1", listOf(Recipient("ada", "Ada")))
        h.clock.advance(2 * HOUR_MS)
        h.processor().process()
        assertEquals(1, h.adapter.sent.size)
        h.clock.advance(48 * HOUR_MS)
        h.processor().process()
        assertEquals(2, h.adapter.sent.size)
        assertEquals(EnrollmentStatus.COMPLETED, h.sequencesRepo.enrollments().single().status)
    }

    @Test
    fun `opted-out and duplicate recipients are not enrolled`() = runTest {
        val h = TestHarness()
        h.sequencesRepo.upsertSequence(sequence)
        h.contacts.upsert(Contact("c1", "Ben", identities = listOf(ChannelIdentity(ChannelId.DEMO, "ben")), optedOut = true))
        h.sequenceService.enroll("s1", listOf(Recipient("ada", "Ada")))
        val result = h.sequenceService.enroll("s1", listOf(Recipient("ada", "Ada"), Recipient("ben", "Ben")))
        assertTrue(result.enrolled.isEmpty())
        assertEquals(listOf("Already in this sequence", "Opted out"), result.skipped.map { it.second })
    }

    @Test
    fun `enrollment is capped per action`() = runTest {
        val h = TestHarness()
        h.sequencesRepo.upsertSequence(sequence)
        val many = (1..40).map { Recipient("person$it", "Person $it") }
        val result = h.sequenceService.enroll("s1", many)
        assertEquals(25, result.enrolled.size)
        assertEquals(15, result.skipped.size)
    }

    @Test
    fun `stopping cancels remaining steps and the queue sends nothing further`() = runTest {
        val h = TestHarness()
        h.sequencesRepo.upsertSequence(sequence)
        val enrollment = h.sequenceService.enroll("s1", listOf(Recipient("ada", "Ada"))).enrolled.single()
        h.clock.advance(2 * HOUR_MS)
        h.processor().process()
        h.sequenceService.stop(enrollment.id, "Manual stop")
        h.clock.advance(200 * HOUR_MS)
        h.processor().process()
        assertEquals(1, h.adapter.sent.size)
    }

    @Test
    fun `validation catches empty and out-of-order steps`() {
        val h = TestHarness()
        assertTrue(h.sequenceService.validate(sequence.copy(steps = emptyList())).isNotEmpty())
        assertTrue(h.sequenceService.validate(sequence.copy(steps = sequence.steps.reversed())).isNotEmpty())
        assertTrue(h.sequenceService.validate(sequence).isEmpty())
    }
}
