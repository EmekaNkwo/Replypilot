package com.replypilot.core.design

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.replypilot.domain.model.ActivityResult
import com.replypilot.domain.model.AvailabilityState
import com.replypilot.domain.model.ChannelId
import com.replypilot.domain.model.MessageStatus

object Spacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val screen = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackTopBar(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}) {
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        },
        actions = { actions() },
    )
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(
        modifier = modifier.fillMaxWidth().padding(top = Spacing.lg, bottom = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        action?.invoke()
    }
}

/** A small coloured pill. Used for every status so colours mean the same thing everywhere. */
@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier, outlined: Boolean = false) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = if (outlined) Color.Transparent else color.copy(alpha = 0.14f),
        border = if (outlined) BorderStroke(1.dp, color) else null,
    ) {
        Text(
            text,
            color = color,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            maxLines = 1,
        )
    }
}

@Composable
fun statusColor(status: MessageStatus): Color {
    val c = LocalStatusColors.current
    return when (status) {
        MessageStatus.SCHEDULED, MessageStatus.DUE, MessageStatus.EXECUTING, MessageStatus.RETRY_SCHEDULED -> c.scheduled
        MessageStatus.SUBMITTED, MessageStatus.DELIVERED -> c.success
        MessageStatus.PAUSED, MessageStatus.CANCELLED, MessageStatus.EXPIRED, MessageStatus.SKIPPED, MessageStatus.DRAFT -> c.paused
        MessageStatus.FAILED -> c.failed
        MessageStatus.NEEDS_ATTENTION, MessageStatus.AWAITING_REVIEW -> c.review
    }
}

@Composable
fun StatusPill(status: MessageStatus, modifier: Modifier = Modifier) = Pill(status.label, statusColor(status), modifier)

@Composable
fun ResultPill(result: ActivityResult, modifier: Modifier = Modifier) {
    val c = LocalStatusColors.current
    val color = when (result) {
        ActivityResult.SUCCESS -> c.success
        ActivityResult.INFO -> c.scheduled
        ActivityResult.SUPPRESSED -> c.paused
        ActivityResult.PENDING -> c.review
        ActivityResult.FAILED -> c.failed
    }
    Pill(result.label, color, modifier)
}

/** Marks anything that did not really happen. Never omit this on simulated content. */
@Composable
fun SimulatedPill(modifier: Modifier = Modifier, text: String = "Simulated") =
    Pill(text, LocalStatusColors.current.simulated, modifier, outlined = true)

@Composable
fun AvailabilityPill(state: AvailabilityState, modifier: Modifier = Modifier) {
    val c = LocalStatusColors.current
    val color = when (state) {
        AvailabilityState.READY -> c.success
        AvailabilityState.MANUAL_ONLY -> c.scheduled
        AvailabilityState.NEEDS_PERMISSION, AvailabilityState.NOT_CONFIGURED -> c.review
        AvailabilityState.APP_NOT_INSTALLED, AvailabilityState.DISABLED, AvailabilityState.PLANNED -> c.paused
    }
    Pill(state.label, color, modifier)
}

fun channelIcon(channel: ChannelId): ImageVector = when (channel) {
    ChannelId.WHATSAPP -> Icons.AutoMirrored.Filled.Chat
    ChannelId.WHATSAPP_BUSINESS -> Icons.Filled.Storefront
    ChannelId.TELEGRAM -> Icons.Filled.Send
    ChannelId.INSTAGRAM -> Icons.Filled.PhotoCamera
    ChannelId.MESSENGER -> Icons.Filled.Forum
    ChannelId.SMS -> Icons.Filled.Sms
    ChannelId.EMAIL -> Icons.Filled.Email
    ChannelId.DEMO -> Icons.Filled.Science
}

@Composable
fun ChannelAvatar(channel: ChannelId, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.size(36.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(channelIcon(channel), contentDescription = channel.displayName, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(20.dp))
    }
}

@Composable
fun MetricTile(label: String, value: String, modifier: Modifier = Modifier, accent: Color = MaterialTheme.colorScheme.primary, onClick: (() -> Unit)? = null) {
    Card(
        modifier = modifier.then(
            if (onClick != null) Modifier.clickable(role = Role.Button, onClickLabel = "Open $label", onClick = onClick) else Modifier,
        ),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(Spacing.md).heightIn(min = 64.dp)) {
            Text(value, style = MetricTextStyle, color = accent)
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        }
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, body: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = Spacing.xl, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(64.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(32.dp))
        }
        Spacer(Modifier.height(Spacing.lg))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(Spacing.xs))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(Spacing.lg))
            action()
        }
    }
}

enum class NoticeTone { INFO, WARNING, ERROR, SIMULATED }

/** Inline explanation box for limitations, warnings and permission requirements. */
@Composable
fun Notice(text: String, modifier: Modifier = Modifier, tone: NoticeTone = NoticeTone.INFO, title: String? = null, action: (@Composable () -> Unit)? = null) {
    val c = LocalStatusColors.current
    val color = when (tone) {
        NoticeTone.INFO -> c.scheduled
        NoticeTone.WARNING -> c.review
        NoticeTone.ERROR -> c.failed
        NoticeTone.SIMULATED -> c.simulated
    }
    OutlinedCard(
        modifier = modifier.fillMaxWidth(),
        border = BorderStroke(1.dp, color.copy(alpha = 0.5f)),
        colors = CardDefaults.outlinedCardColors(containerColor = color.copy(alpha = 0.06f)),
    ) {
        Row(Modifier.padding(Spacing.md), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Icon(if (tone == NoticeTone.INFO) Icons.Filled.Info else Icons.Filled.Warning, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
            Column(Modifier.weight(1f)) {
                if (title != null) Text(title, style = MaterialTheme.typography.titleSmall)
                Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                if (action != null) {
                    Spacer(Modifier.width(Spacing.sm))
                    action()
                }
            }
        }
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
    dismissLabel: String = "Cancel",
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = { onConfirm(); onDismiss() }) {
                Text(confirmLabel, color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(dismissLabel) } },
    )
}

/** Standard list row card used across lists for consistent density and touch targets. */
@Composable
fun ListCard(onClick: (() -> Unit)?, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Box(Modifier.padding(Spacing.md).heightIn(min = 48.dp)) { content() }
    }
}
