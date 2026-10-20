package com.replypilot.domain.backup

import com.replypilot.domain.model.AutoReplyRule
import com.replypilot.domain.model.MessageSequence
import com.replypilot.domain.model.MessageTemplate
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Portable, versioned export of user-defined automation. Contains no contacts or message history. */
@Serializable
data class ExportBundle(
    val format: String = FORMAT,
    val version: Int = CURRENT_VERSION,
    val exportedAt: Long,
    val rules: List<AutoReplyRule> = emptyList(),
    val templates: List<MessageTemplate> = emptyList(),
    val sequences: List<MessageSequence> = emptyList(),
) {
    companion object {
        const val FORMAT = "replypilot.automation"
        const val CURRENT_VERSION = 1
    }
}

sealed interface ImportParse {
    data class Ok(val bundle: ExportBundle) : ImportParse
    data class Error(val message: String) : ImportParse
}

object ExportCodec {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "type"
    }

    const val MAX_IMPORT_BYTES = 2 * 1024 * 1024

    fun encode(bundle: ExportBundle): String = json.encodeToString(ExportBundle.serializer(), bundle)

    fun decode(text: String): ImportParse {
        if (text.length > MAX_IMPORT_BYTES) return ImportParse.Error("File is too large to be a ReplyPilot export.")
        val bundle = try {
            json.decodeFromString(ExportBundle.serializer(), text)
        } catch (e: SerializationException) {
            return ImportParse.Error("This isn't a valid ReplyPilot export (${e.message?.take(120)}).")
        } catch (e: IllegalArgumentException) {
            return ImportParse.Error("This isn't a valid ReplyPilot export (${e.message?.take(120)}).")
        }
        return when {
            bundle.format != ExportBundle.FORMAT -> ImportParse.Error("Unrecognised file format \"${bundle.format}\".")
            bundle.version > ExportBundle.CURRENT_VERSION ->
                ImportParse.Error("This export was made by a newer version of ReplyPilot (format ${bundle.version}).")
            bundle.rules.any { it.response.isBlank() && it.templateId == null } ->
                ImportParse.Error("A rule in this file has no response.")
            else -> ImportParse.Ok(bundle)
        }
    }
}
