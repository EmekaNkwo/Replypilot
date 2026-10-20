package com.replypilot.domain.assistant

enum class AssistOperation(val label: String) {
    REWRITE("Rewrite"),
    SHORTEN("Shorten"),
    FORMAL("More formal"),
    FRIENDLY("Friendlier"),
    ALTERNATIVES("Alternatives"),
}

data class AssistResult(val suggestions: List<String>, val isMock: Boolean, val providerName: String)

data class DraftReply(val text: String, val confidence: Double, val isMock: Boolean, val providerName: String)

sealed interface AssistantFailure {
    data object NotConfigured : AssistantFailure
    data class Error(val message: String) : AssistantFailure
}

/**
 * Optional writing help. Never used for automatic replies unless a rule is explicitly AI-assisted,
 * and a mock provider can never auto-send: its drafts always wait for review.
 */
interface MessageAssistant {
    val providerName: String
    val isMock: Boolean
    suspend fun isAvailable(): Boolean
    suspend fun transform(text: String, operation: AssistOperation): kotlin.Result<AssistResult>
    suspend fun draftReply(incomingText: String, guidance: String): kotlin.Result<DraftReply>
}

class AssistantUnavailableException(message: String) : Exception(message)

/** Deterministic offline stand-in for development and demo mode. Output is clearly labelled as mock. */
class MockMessageAssistant : MessageAssistant {
    override val providerName: String = "Local mock (no AI)"
    override val isMock: Boolean = true

    override suspend fun isAvailable(): Boolean = true

    override suspend fun transform(text: String, operation: AssistOperation): kotlin.Result<AssistResult> {
        val clean = text.trim()
        if (clean.isEmpty()) return kotlin.Result.failure(IllegalArgumentException("Write something first."))
        val suggestions = when (operation) {
            AssistOperation.REWRITE -> listOf(rewrite(clean))
            AssistOperation.SHORTEN -> listOf(shorten(clean))
            AssistOperation.FORMAL -> listOf(formal(clean))
            AssistOperation.FRIENDLY -> listOf(friendly(clean))
            AssistOperation.ALTERNATIVES -> listOf(rewrite(clean), formal(clean), friendly(clean)).distinct()
        }
        return kotlin.Result.success(AssistResult(suggestions, isMock = true, providerName = providerName))
    }

    override suspend fun draftReply(incomingText: String, guidance: String): kotlin.Result<DraftReply> {
        val base = guidance.trim().ifEmpty { "Thanks for your message. We'll get back to you shortly." }
        return kotlin.Result.success(DraftReply(base, confidence = 0.5, isMock = true, providerName = providerName))
    }

    private fun sentences(text: String): List<String> =
        text.split(Regex("(?<=[.!?])\\s+")).map { it.trim() }.filter { it.isNotEmpty() }

    private fun shorten(text: String): String {
        val first = sentences(text).firstOrNull() ?: text
        val trimmed = first.replace(Regex("\\b(just|really|very|actually|basically|simply)\\s+", RegexOption.IGNORE_CASE), "")
        return if (trimmed.length <= 160) trimmed else trimmed.take(157).trimEnd() + "…"
    }

    private fun formal(text: String): String {
        val replacements = listOf(
            "\\bhi\\b" to "Hello", "\\bhey\\b" to "Hello", "\\bthanks\\b" to "Thank you", "\\bcan't\\b" to "cannot",
            "\\bwon't\\b" to "will not", "\\bdon't\\b" to "do not", "\\bASAP\\b" to "as soon as possible", "\\bpls\\b" to "please",
        )
        val body = replacements.fold(text) { acc, (pattern, value) -> acc.replace(Regex(pattern, RegexOption.IGNORE_CASE), value) }
        return body.replace(Regex("!+"), ".").let { if (it.endsWith(".")) it else "$it." }
    }

    private fun friendly(text: String): String {
        val opener = if (Regex("^(hi|hello|hey)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)) "" else "Hi! "
        val body = text.trimEnd('.', ' ')
        return "$opener$body 😊"
    }

    private fun rewrite(text: String): String {
        val parts = sentences(text)
        return if (parts.size > 1) (parts.drop(1) + parts.first()).joinToString(" ") else "Just a quick note: ${text.replaceFirstChar { it.lowercase() }}"
    }
}
