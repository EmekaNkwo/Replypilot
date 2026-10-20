package com.replypilot.core

import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.replypilot.core.attachments.ShareIntents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShareIntentsTest {
    private val photo = Uri.parse("content://com.replypilot.attachments/attachments/a/flyer.jpg")
    private val pdf = Uri.parse("content://com.replypilot.attachments/attachments/b/menu.pdf")

    @Test
    fun `one file addressed to a WhatsApp number`() {
        val intent = ShareIntents.build(
            listOf(photo), listOf("image/jpeg"), "See you at 6",
            ShareIntents.Target(packageName = "com.whatsapp", whatsAppJid = ShareIntents.whatsAppJid("+234 703 712 3201")),
        )
        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("image/jpeg", intent.type)
        assertEquals("2347037123201@s.whatsapp.net", intent.getStringExtra("jid"))
        assertEquals("See you at 6", intent.getStringExtra(Intent.EXTRA_TEXT))
        assertEquals("com.whatsapp", intent.`package`)
        assertTrue("read access is granted with the share", intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }

    @Test
    fun `several files share every uri with a read grant for each`() {
        val intent = ShareIntents.build(listOf(photo, pdf), listOf("image/jpeg", "application/pdf"), null, ShareIntents.Target())
        assertEquals(Intent.ACTION_SEND_MULTIPLE, intent.action)
        assertEquals("*/*", intent.type)
        assertEquals(2, intent.clipData?.itemCount)
        assertNull(intent.getStringExtra(Intent.EXTRA_TEXT))
    }

    @Test
    fun `email shares go only to email apps, addressed`() {
        val intent = ShareIntents.build(listOf(pdf), listOf("application/pdf"), "Invoice attached\nThanks", ShareIntents.Target(email = "ada@example.com"))
        assertEquals("mailto", intent.selector?.data?.scheme)
        assertEquals("ada@example.com", intent.getStringArrayExtra(Intent.EXTRA_EMAIL)?.single())
        assertEquals("Invoice attached", intent.getStringExtra(Intent.EXTRA_SUBJECT))
    }

    @Test
    fun `mime family is kept when the exact types differ`() {
        assertEquals("image/*", ShareIntents.commonType(listOf("image/png", "image/jpeg")))
        assertNull(ShareIntents.whatsAppJid("12"))
    }
}
