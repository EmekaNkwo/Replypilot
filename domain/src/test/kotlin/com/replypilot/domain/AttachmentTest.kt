package com.replypilot.domain

import com.replypilot.domain.common.HOUR_MS
import com.replypilot.domain.model.Attachment
import com.replypilot.domain.model.ChannelCapability
import com.replypilot.domain.scheduling.SaveResult
import com.replypilot.domain.scheduling.Severity
import com.replypilot.domain.scheduling.ValidationField
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentTest {
    private val withFiles = FakeAdapter(
        capabilities = setOf(ChannelCapability.SEND_MESSAGE, ChannelCapability.MANUAL_HANDOFF, ChannelCapability.ATTACHMENTS),
    )
    private fun file(name: String = "flyer.jpg", size: Long = 2_000, mime: String = "image/jpeg") =
        Attachment(id = name, fileName = name, mimeType = mime, sizeBytes = size, path = "/data/attachments/$name/$name")

    private fun issues(result: SaveResult) = (result as SaveResult.Invalid).issues

    @Test
    fun `files travel from the schedule to every execution and to the channel`() = runTest {
        val h = TestHarness(adapter = withFiles)
        val files = listOf(file(), file("menu.pdf", mime = "application/pdf"))
        h.scheduleService.save(h.draft(body = "See attached, {{firstName}}").copy(attachments = files))
        assertEquals(files, h.executions.items.single().attachments)
        h.clock.advance(2 * HOUR_MS)
        h.processor().process()
        assertEquals(files, h.adapter.sent.single().attachments)
        assertEquals("See attached, Ada", h.adapter.sent.single().body)
    }

    @Test
    fun `a file without text is a valid message`() = runTest {
        val h = TestHarness(adapter = withFiles)
        val result = h.scheduleService.save(h.draft(body = "").copy(attachments = listOf(file())))
        assertTrue("$result", result is SaveResult.Saved)
    }

    @Test
    fun `no text and no file is still rejected`() = runTest {
        val h = TestHarness(adapter = withFiles)
        assertTrue(issues(h.scheduleService.save(h.draft(body = " "))).any { it.field == ValidationField.BODY })
    }

    @Test
    fun `channels that can't send files reject attachments`() = runTest {
        val h = TestHarness()
        val found = issues(h.scheduleService.save(h.draft().copy(attachments = listOf(file()))))
        assertTrue(found.any { it.field == ValidationField.ATTACHMENTS && it.severity == Severity.ERROR })
    }

    @Test
    fun `too many or too large files are rejected`() = runTest {
        val h = TestHarness(adapter = withFiles)
        val many = (1..Attachment.MAX_FILES + 1).map { file("f$it.jpg") }
        assertTrue(issues(h.scheduleService.save(h.draft().copy(attachments = many))).any { it.message.contains("At most") })
        val huge = file("video.mp4", size = Attachment.MAX_FILE_BYTES + 1, mime = "video/mp4")
        assertTrue(issues(h.scheduleService.save(h.draft().copy(attachments = listOf(huge)))).any { it.message.contains("larger than") })
    }
}
