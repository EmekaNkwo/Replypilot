package com.replypilot.domain.common

import java.security.MessageDigest

/** One-way hashes used for duplicate and loop detection so raw message text never needs to be stored. */
object Fingerprints {
    fun of(vararg parts: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(parts.joinToString("\u0000").toByteArray(Charsets.UTF_8))
        return bytes.take(12).joinToString("") { "%02x".format(it) }
    }

    fun normalizeText(text: String): String = text.trim().lowercase().replace(Regex("\\s+"), " ")
}
