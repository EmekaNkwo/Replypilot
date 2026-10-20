package com.replypilot.core.attachments

import android.content.ClipData
import android.content.Intent
import android.net.Uri

/** Builds the Android share intent that hands files (plus the message as caption) to a messaging app. */
object ShareIntents {
    data class Target(
        val packageName: String? = null,
        /** WhatsApp: open straight on this person's chat ("<digits>@s.whatsapp.net") instead of the "Send to…" list. */
        val whatsAppJid: String? = null,
        val email: String? = null,
        val smsNumber: String? = null,
    )

    fun build(uris: List<Uri>, mimeTypes: List<String>, text: String?, target: Target): Intent {
        require(uris.isNotEmpty()) { "Nothing to share" }
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.first())
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        intent.type = commonType(mimeTypes)
        // ClipData carries the read grant for every file (EXTRA_STREAM alone only covers the first on some versions).
        intent.clipData = ClipData.newRawUri("", uris.first()).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        text?.takeIf { it.isNotBlank() }?.let {
            intent.putExtra(Intent.EXTRA_TEXT, it)
            intent.putExtra("sms_body", it)
        }
        target.packageName?.let(intent::setPackage)
        target.whatsAppJid?.let { intent.putExtra("jid", it) }
        target.smsNumber?.let { intent.putExtra("address", it) }
        target.email?.let {
            intent.putExtra(Intent.EXTRA_EMAIL, arrayOf(it))
            text?.lineSequence()?.firstOrNull()?.take(SUBJECT_CHARS)?.let { subject -> intent.putExtra(Intent.EXTRA_SUBJECT, subject) }
            // Only email apps should offer to handle it.
            intent.selector = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
        }
        return intent
    }

    fun whatsAppJid(digits: String): String? = digits.filter(Char::isDigit).takeIf { it.length >= MIN_DIGITS }?.let { "$it@s.whatsapp.net" }

    /** One MIME type for the whole share: exact if they agree, a family wildcard (images, videos…) if only that agrees, else any. */
    fun commonType(types: List<String>): String {
        val distinct = types.map { it.lowercase() }.distinct()
        if (distinct.size == 1) return distinct.first()
        val families = distinct.map { it.substringBefore('/') }.distinct()
        return if (families.size == 1) "${families.first()}/*" else "*/*"
    }

    private const val SUBJECT_CHARS = 60
    private const val MIN_DIGITS = 7
}
