package com.replypilot.domain.contacts

import com.replypilot.domain.model.ChannelId
import com.replypilot.domain.model.ChannelIdentity
import com.replypilot.domain.model.Contact
import com.replypilot.domain.scheduling.AddressRules
import java.time.ZoneId

data class ImportRow(
    val line: Int,
    val contact: Contact?,
    val errors: List<String>,
    val duplicateOf: String? = null,
) {
    val importable: Boolean get() = contact != null && errors.isEmpty() && duplicateOf == null
}

data class ImportPreview(val rows: List<ImportRow>, val headerError: String? = null) {
    val importable: List<Contact> get() = rows.filter { it.importable }.mapNotNull { it.contact }
}

/**
 * Parses a CSV export into contacts for preview. Nothing is saved until the user confirms.
 * Recognised headers (case-insensitive): first_name, last_name, name, phone, sms, whatsapp, telegram,
 * instagram, messenger, email, tags, groups, timezone, notes, opted_out.
 */
object ContactCsvImporter {
    const val MAX_ROWS = 2_000

    private val CHANNEL_COLUMNS = mapOf(
        "sms" to ChannelId.SMS, "phone" to ChannelId.SMS, "whatsapp" to ChannelId.WHATSAPP,
        "telegram" to ChannelId.TELEGRAM, "instagram" to ChannelId.INSTAGRAM, "messenger" to ChannelId.MESSENGER,
        "email" to ChannelId.EMAIL,
    )

    fun preview(csv: String, existing: List<Contact>, newId: () -> String, now: Long): ImportPreview {
        val lines = parse(csv)
        if (lines.isEmpty()) return ImportPreview(emptyList(), "The file is empty.")
        val header = lines.first().map { it.trim().lowercase().replace(' ', '_') }
        if ("name" !in header && "first_name" !in header) return ImportPreview(emptyList(), "Add a \"name\" or \"first_name\" column.")
        if (header.none { it in CHANNEL_COLUMNS }) return ImportPreview(emptyList(), "Add at least one address column, such as phone, whatsapp or email.")
        if (lines.size - 1 > MAX_ROWS) return ImportPreview(emptyList(), "At most $MAX_ROWS contacts per import.")

        val known = existing.flatMap { c -> c.identities.map { key(it) to c.displayName } }.toMap().toMutableMap()
        val rows = lines.drop(1).mapIndexedNotNull { index, cells ->
            if (cells.all { it.isBlank() }) return@mapIndexedNotNull null
            val row = header.zip(cells.map { it.trim() } + List((header.size - cells.size).coerceAtLeast(0)) { "" }).toMap()
            toRow(index + 2, row, known, newId, now)
        }
        return ImportPreview(rows)
    }

    private fun toRow(line: Int, row: Map<String, String>, known: MutableMap<String, String>, newId: () -> String, now: Long): ImportRow {
        val errors = mutableListOf<String>()
        val first = row["first_name"].orEmpty().ifBlank { row["name"].orEmpty().substringBefore(' ') }
        val last = row["last_name"].orEmpty().ifBlank { row["name"].orEmpty().substringAfter(' ', "") }
        if (first.isBlank()) errors += "Missing name"
        val identities = CHANNEL_COLUMNS.mapNotNull { (column, channel) ->
            val value = row[column].orEmpty()
            when {
                value.isBlank() -> null
                !AddressRules.isValid(channel, value) -> { errors += "Invalid ${channel.displayName} address \"$value\""; null }
                else -> ChannelIdentity(channel, AddressRules.normalize(channel, value))
            }
        }.distinctBy { key(it) }
        if (identities.isEmpty() && errors.none { it.startsWith("Invalid") }) errors += "No address"
        val timeZone = row["timezone"].orEmpty().takeIf { it.isNotBlank() }
        if (timeZone != null && runCatching { ZoneId.of(timeZone) }.isFailure) errors += "Unknown time zone \"$timeZone\""
        val duplicate = identities.firstNotNullOfOrNull { known[key(it)] }
        val contact = Contact(
            id = newId(),
            firstName = first,
            lastName = last,
            identities = identities,
            preferredChannel = identities.firstOrNull()?.channel,
            tags = split(row["tags"]),
            groups = split(row["groups"]),
            timeZone = timeZone,
            optedOut = row["opted_out"].orEmpty().lowercase() in setOf("yes", "true", "1", "y"),
            notes = row["notes"].orEmpty().take(500),
            createdAt = now,
            updatedAt = now,
        )
        if (duplicate == null && errors.isEmpty()) identities.forEach { known[key(it)] = contact.displayName }
        return ImportRow(line, contact, errors, duplicate)
    }

    private fun key(identity: ChannelIdentity) = "${identity.channel}:${AddressRules.normalize(identity.channel, identity.address)}"

    private fun split(value: String?): Set<String> =
        value.orEmpty().split(';', '|').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    /** Minimal RFC 4180 parser: quoted fields, escaped quotes, commas and newlines inside quotes. */
    fun parse(csv: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0
        val text = csv.removePrefix("﻿")
        while (i < text.length) {
            val c = text[i]
            when {
                inQuotes && c == '"' && text.getOrNull(i + 1) == '"' -> { field.append('"'); i++ }
                c == '"' -> inQuotes = !inQuotes
                !inQuotes && c == ',' -> { row.add(field.toString()); field.clear() }
                !inQuotes && (c == '\n' || c == '\r') -> {
                    if (c == '\r' && text.getOrNull(i + 1) == '\n') i++
                    row.add(field.toString()); field.clear()
                    rows.add(row); row = mutableListOf()
                }
                else -> field.append(c)
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) { row.add(field.toString()); rows.add(row) }
        return rows
    }
}
