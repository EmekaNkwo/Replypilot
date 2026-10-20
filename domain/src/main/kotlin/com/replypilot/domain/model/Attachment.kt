package com.replypilot.domain.model

import kotlinx.serialization.Serializable

/**
 * A file sent with a scheduled message. The app keeps its own copy at [path] (inside app storage), so the
 * send still works if the original is moved or deleted, and nothing outside ReplyPilot can change it.
 */
@Serializable
data class Attachment(
    val id: String,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val path: String,
) {
    /** Photos and videos travel as media (with a caption); everything else as a document. */
    val isMedia: Boolean get() = mimeType.startsWith("image/") || mimeType.startsWith("video/")

    companion object {
        const val MAX_FILES = 10
        const val MAX_FILE_BYTES = 64L * 1024 * 1024
    }
}
