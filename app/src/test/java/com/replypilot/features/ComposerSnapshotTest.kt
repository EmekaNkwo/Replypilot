package com.replypilot.features

import com.replypilot.core.database.AppJson
import com.replypilot.domain.model.Attachment
import com.replypilot.domain.model.ChannelId
import com.replypilot.domain.model.Frequency
import com.replypilot.domain.model.QuietHoursBehavior
import com.replypilot.domain.model.Recipient
import com.replypilot.domain.model.RecipientKind
import com.replypilot.features.scheduler.ComposerForm
import com.replypilot.features.scheduler.ComposerSnapshot
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Test

class ComposerSnapshotTest {
    @Test
    fun `a half-written message survives the screen being rebuilt`() {
        val form = ComposerForm(
            title = "Weekly check-in",
            channel = ChannelId.WHATSAPP,
            recipients = listOf(Recipient("Eze EZE", "Eze EZE", kind = RecipientKind.CHAT), Recipient("+2348000000001", "Ada")),
            body = "Hi {{firstName|there}}, see the agenda",
            attachments = listOf(Attachment("a", "agenda.pdf", "application/pdf", 1200, "/x/agenda.pdf")),
            date = LocalDate.of(2026, 10, 21),
            time = LocalTime.of(9, 30),
            zoneId = "Africa/Lagos",
            frequency = Frequency.WEEKLY,
            weekdays = setOf(1, 3),
            endDate = LocalDate.of(2026, 12, 31),
            skipDates = setOf(LocalDate.of(2026, 11, 2)),
            quietHours = QuietHoursBehavior.SKIP,
            maxOccurrences = "10",
        )
        val json = AppJson.encodeToString(ComposerSnapshot.serializer(), ComposerSnapshot.of(form))
        val restored = AppJson.decodeFromString(ComposerSnapshot.serializer(), json).toForm()
        assertEquals(form, restored)
    }
}
