package com.replypilot.features.scheduler

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.replypilot.core.design.Spacing
import com.replypilot.domain.model.Attachment

/** Files sent with the message. The message text becomes their caption. */
@Composable
fun AttachmentSection(
    attachments: List<Attachment>,
    importing: Boolean,
    supported: Boolean,
    channelName: String,
    onAdd: (List<Uri>) -> Unit,
    onRemove: (String) -> Unit,
) {
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) onAdd(uris)
    }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        attachments.forEach { file ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(iconFor(file), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Column(Modifier.weight(1f).padding(start = Spacing.sm)) {
                    Text(file.fileName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(formatSize(file.sizeBytes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { onRemove(file.id) }) { Icon(Icons.Filled.Close, contentDescription = "Remove ${file.fileName}") }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            OutlinedButton(
                onClick = { pick.launch(arrayOf("*/*")) },
                enabled = supported && !importing && attachments.size < Attachment.MAX_FILES,
            ) {
                Icon(Icons.Filled.AttachFile, contentDescription = null)
                Text(if (attachments.isEmpty()) " Attach files" else " Add more")
            }
            if (importing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
        Text(
            if (supported) "Photos, videos, documents — up to ${Attachment.MAX_FILES} files, ${Attachment.MAX_FILE_BYTES / (1024 * 1024)} MB each. The message becomes the caption."
            else "$channelName can't send attachments from ReplyPilot.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun iconFor(file: Attachment) = when {
    file.mimeType.startsWith("image/") -> Icons.Filled.Image
    file.mimeType.startsWith("video/") -> Icons.Filled.Movie
    else -> Icons.Filled.Description
}

fun formatSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}
