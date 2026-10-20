package com.replypilot.core.contacts

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.GroupMembership
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.replypilot.domain.model.ChannelId
import com.replypilot.domain.scheduling.AddressRules
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

data class PhoneContact(val name: String, val phone: String?, val email: String?)

/** A contact label (Family, Work…) merged across accounts that use the same title. */
data class PhoneGroup(val title: String, val ids: List<Long>, val memberCount: Int)

/**
 * Reads the phone's own address book. Picking one contact uses the system picker and needs no permission;
 * listing groups needs READ_CONTACTS, which is requested only when the user opens the group picker.
 * Nothing is copied into ReplyPilot unless the user saves it.
 */
@Singleton
class PhoneContacts @Inject constructor(@ApplicationContext private val context: Context) {

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /** The system picker's content URI to use for a channel: email rows for Email, phone rows otherwise. */
    fun pickerUri(channel: ChannelId): Uri = if (channel == ChannelId.EMAIL) Email.CONTENT_URI else Phone.CONTENT_URI

    /**
     * Pick intent aimed at the contacts app. Some phones (e.g. Xiaomi) also offer a file manager for this action,
     * which only adds a confusing chooser.
     */
    @Suppress("DEPRECATION")
    fun pickIntent(channel: ChannelId): Intent {
        val intent = Intent(Intent.ACTION_PICK, pickerUri(channel))
        val handlers = context.packageManager.queryIntentActivities(intent, 0).map { it.activityInfo.packageName }
        handlers.firstOrNull { it.contains("contacts", ignoreCase = true) }?.let { intent.setPackage(it) }
        return intent
    }

    /** Reads the row the system picker returned (the picker grants temporary access to just that row). */
    fun readPicked(uri: Uri): PhoneContact? = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (!c.moveToFirst()) return@use null
            val name = c.getString(c.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME)).orEmpty()
            val value = c.getString(c.getColumnIndexOrThrow(ContactsContract.Data.DATA1)).orEmpty()
            val mime = c.getString(c.getColumnIndexOrThrow(ContactsContract.Data.MIMETYPE)).orEmpty()
            if (mime == Email.CONTENT_ITEM_TYPE) PhoneContact(name, null, value) else PhoneContact(name, value, null)
        }
    }.getOrNull()

    fun groups(): List<PhoneGroup> {
        if (!hasPermission()) return emptyList()
        val raw = mutableListOf<Pair<Long, String>>()
        context.contentResolver.query(
            ContactsContract.Groups.CONTENT_URI,
            arrayOf(ContactsContract.Groups._ID, ContactsContract.Groups.TITLE),
            "${ContactsContract.Groups.DELETED} = 0",
            null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val title = c.getString(1)?.trim().orEmpty()
                if (title.isNotEmpty()) raw += c.getLong(0) to title
            }
        }
        return raw.groupBy({ it.second }, { it.first })
            .map { (title, ids) -> PhoneGroup(title, ids, memberContactIds(ids).size) }
            .filter { it.memberCount > 0 }
            .sortedBy { it.title.lowercase() }
    }

    /** Members of a group that have a phone number (or email, for the Email channel), one entry per person. */
    fun members(group: PhoneGroup, channel: ChannelId): List<PhoneContact> {
        val contactIds = memberContactIds(group.ids)
        if (contactIds.isEmpty()) return emptyList()
        val email = channel == ChannelId.EMAIL
        val uri = if (email) Email.CONTENT_URI else Phone.CONTENT_URI
        val people = linkedMapOf<Long, PhoneContact>()
        contactIds.chunked(500).forEach { chunk ->
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.Data.CONTACT_ID, ContactsContract.Contacts.DISPLAY_NAME, ContactsContract.Data.DATA1, ContactsContract.Data.IS_PRIMARY),
                "${ContactsContract.Data.CONTACT_ID} IN (${chunk.joinToString(",")})",
                null, "${ContactsContract.Data.IS_PRIMARY} DESC",
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    if (id in people) continue
                    val name = c.getString(1).orEmpty()
                    val value = c.getString(2).orEmpty()
                    people[id] = if (email) PhoneContact(name, null, value) else PhoneContact(name, value, null)
                }
            }
        }
        return people.values.toList()
    }

    /** Every contact with a phone number (or email, for the Email channel), one entry per person, sorted by name. */
    fun all(channel: ChannelId): List<PhoneContact> {
        if (!hasPermission()) return emptyList()
        val email = channel == ChannelId.EMAIL
        val people = linkedMapOf<Long, PhoneContact>()
        context.contentResolver.query(
            if (email) Email.CONTENT_URI else Phone.CONTENT_URI,
            arrayOf(ContactsContract.Data.CONTACT_ID, ContactsContract.Contacts.DISPLAY_NAME, ContactsContract.Data.DATA1, ContactsContract.Data.IS_PRIMARY),
            null, null, "${ContactsContract.Contacts.DISPLAY_NAME} COLLATE NOCASE ASC, ${ContactsContract.Data.IS_PRIMARY} DESC",
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                if (id in people) continue
                val name = c.getString(1).orEmpty()
                val value = c.getString(2).orEmpty()
                people[id] = if (email) PhoneContact(name, null, value) else PhoneContact(name, value, null)
            }
        }
        return people.values.toList()
    }

    private fun memberContactIds(groupIds: List<Long>): List<Long> {
        if (!hasPermission() || groupIds.isEmpty()) return emptyList()
        val ids = linkedSetOf<Long>()
        context.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.Data.CONTACT_ID),
            "${ContactsContract.Data.MIMETYPE} = ? AND ${GroupMembership.GROUP_ROW_ID} IN (${groupIds.joinToString(",")})",
            arrayOf(GroupMembership.CONTENT_ITEM_TYPE), null,
        )?.use { c -> while (c.moveToNext()) ids += c.getLong(0) }
        return ids.toList()
    }

    /** The address to use on a channel, normalised; local numbers get the phone's country code. */
    fun addressFor(contact: PhoneContact, channel: ChannelId): String? = when (channel) {
        ChannelId.EMAIL -> contact.email?.trim()?.takeIf { AddressRules.isValid(channel, it) }
        ChannelId.WHATSAPP, ChannelId.WHATSAPP_BUSINESS, ChannelId.SMS, ChannelId.TELEGRAM ->
            contact.phone?.let(::toE164)?.takeIf { AddressRules.isValid(channel, it) }
        ChannelId.DEMO -> contact.name.takeIf { it.isNotBlank() }
        ChannelId.INSTAGRAM, ChannelId.MESSENGER -> null
    }

    fun toE164(raw: String): String {
        val trimmed = raw.trim()
        val country = countryIso()
        val e164 = if (country != null) PhoneNumberUtils.formatNumberToE164(trimmed, country) else null
        return e164 ?: trimmed.filter { it.isDigit() || it == '+' }
    }

    private fun countryIso(): String? {
        val telephony = context.getSystemService(TelephonyManager::class.java)
        return listOfNotNull(telephony?.simCountryIso, telephony?.networkCountryIso, Locale.getDefault().country)
            .firstOrNull { it.isNotBlank() }?.uppercase()
    }
}
