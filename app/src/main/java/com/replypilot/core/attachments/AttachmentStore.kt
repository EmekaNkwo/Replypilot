package com.replypilot.core.attachments

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.replypilot.domain.model.Attachment
import com.replypilot.domain.model.MessageStatus
import com.replypilot.domain.repository.ExecutionRepository
import com.replypilot.domain.repository.ScheduleRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Keeps ReplyPilot's own copy of every attached file (in private app storage) so a scheduled send never depends on
 * the original still existing, and shares them with messaging apps through a FileProvider (read-only, per intent).
 */
@Singleton
class AttachmentStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val schedules: ScheduleRepository,
    private val executions: ExecutionRepository,
) {
    sealed interface Imported {
        data class Ok(val attachment: Attachment) : Imported
        data class Rejected(val reason: String) : Imported
    }

    private val root: File get() = File(context.filesDir, DIR).apply { mkdirs() }

    /** Copies a file the user picked. Refuses files over [Attachment.MAX_FILE_BYTES] without reading all of them. */
    suspend fun import(uri: Uri): Imported = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        var name: String? = null
        var declaredSize: Long? = null
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    name = c.getString(0)
                    declaredSize = if (c.isNull(1)) null else c.getLong(1)
                }
            }
        }
        val fileName = safeName(name ?: uri.lastPathSegment ?: "file")
        if ((declaredSize ?: 0) > Attachment.MAX_FILE_BYTES) return@withContext Imported.Rejected("\"$fileName\" is larger than ${MAX_MB} MB")
        val mime = resolver.getType(uri)
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(fileName.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"
        val id = UUID.randomUUID().toString()
        val dir = File(root, id).apply { mkdirs() }
        val target = File(dir, fileName)
        try {
            val copied = resolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> copyLimited(input, output) }
            } ?: return@withContext Imported.Rejected("\"$fileName\" couldn't be opened").also { dir.deleteRecursively() }
            if (copied < 0) {
                dir.deleteRecursively()
                Imported.Rejected("\"$fileName\" is larger than $MAX_MB MB")
            } else {
                Imported.Ok(Attachment(id, fileName, mime, copied, target.absolutePath))
            }
        } catch (e: IOException) {
            dir.deleteRecursively()
            Imported.Rejected("\"$fileName\" couldn't be copied: ${e.message ?: "read error"}")
        } catch (e: SecurityException) {
            dir.deleteRecursively()
            Imported.Rejected("ReplyPilot wasn't allowed to read \"$fileName\"")
        }
    }

    /** The file is still present and is one of ours (never share a path from outside our attachment folder). */
    fun exists(attachment: Attachment): Boolean = ownedFile(attachment)?.isFile == true

    fun uriFor(attachment: Attachment): Uri? = ownedFile(attachment)?.takeIf { it.isFile }?.let {
        FileProvider.getUriForFile(context, "${context.packageName}$AUTHORITY_SUFFIX", it)
    }

    /**
     * Deletes copies no schedule or pending message uses any more. Files younger than [GRACE_MS] are kept, because they
     * may belong to a message still being written in the composer.
     */
    suspend fun deleteUnused(now: Long = System.currentTimeMillis()): Int = withContext(Dispatchers.IO) {
        val inUse = buildSet {
            schedules.all().forEach { s -> s.attachments.forEach { add(it.id) } }
            executions.observeAll().first().filter { it.status !in FINISHED }.forEach { e -> e.attachments.forEach { add(it.id) } }
        }
        root.listFiles().orEmpty()
            .filter { it.isDirectory && it.name !in inUse && now - it.lastModified() > GRACE_MS }
            .count { it.deleteRecursively() }
    }

    private fun ownedFile(attachment: Attachment): File? {
        val file = File(attachment.path)
        val base = root.canonicalPath + File.separator
        return file.takeIf { runCatching { it.canonicalPath.startsWith(base) }.getOrDefault(false) }
    }

    /** Copies up to the size limit; returns the byte count, or -1 if the file turned out to be larger. */
    private fun copyLimited(input: java.io.InputStream, output: java.io.OutputStream): Long {
        val buffer = ByteArray(BUFFER)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return total
            total += read
            if (total > Attachment.MAX_FILE_BYTES) return -1
            output.write(buffer, 0, read)
        }
    }

    private fun safeName(raw: String): String {
        val cleaned = raw.substringAfterLast('/').substringAfterLast('\\').replace(Regex("[\\u0000-\\u001f:*?\"<>|]"), "_").trim()
        return cleaned.ifEmpty { "file" }.take(MAX_NAME)
    }

    companion object {
        const val DIR = "attachments"
        const val AUTHORITY_SUFFIX = ".attachments"
        private const val MAX_MB = Attachment.MAX_FILE_BYTES / (1024 * 1024)
        private const val BUFFER = 64 * 1024
        private const val MAX_NAME = 120
        private const val GRACE_MS = 24L * 60 * 60 * 1000
        /** Messages whose files may still be needed: anything not finally done (Failed can still be retried). */
        private val FINISHED = setOf(MessageStatus.SUBMITTED, MessageStatus.DELIVERED, MessageStatus.CANCELLED, MessageStatus.EXPIRED, MessageStatus.SKIPPED)
    }
}
