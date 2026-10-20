package com.replypilot.core.handoff

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.replypilot.core.attachments.ShareIntents
import com.replypilot.domain.model.Attachment
import com.replypilot.domain.model.ManualHandoff

/** Executes a manual handoff: opens the right app with the message, falling back to copying it. */
object HandoffLauncher {
    sealed interface Outcome {
        data object Opened : Outcome
        data object CopiedAndOpened : Outcome
        data object Copied : Outcome
        /** The files were handed to the app's share screen (the text was also copied, in case it drops the caption). */
        data object Shared : Outcome
        data class FilesMissing(val names: List<String>) : Outcome
    }

    fun launch(context: Context, handoff: ManualHandoff, uriFor: (Attachment) -> Uri? = { null }): Outcome {
        if (handoff.attachments.isNotEmpty()) return share(context, handoff, uriFor)
        val intent = when (handoff.kind) {
            ManualHandoff.Kind.COMPOSE_SMS -> handoff.uri?.let {
                Intent(Intent.ACTION_SENDTO, Uri.parse(it)).putExtra("sms_body", handoff.text)
            }
            ManualHandoff.Kind.COMPOSE_EMAIL, ManualHandoff.Kind.OPEN_APP_PREFILLED -> handoff.uri?.let { Intent(Intent.ACTION_VIEW, Uri.parse(it)) }
            ManualHandoff.Kind.COPY_TEXT, ManualHandoff.Kind.SHARE_FILES -> null
        }
        if (intent != null && start(context, intent)) return Outcome.Opened

        copy(context, handoff.text)
        val fallback = handoff.uri?.let { Intent(Intent.ACTION_VIEW, Uri.parse(it)) }
            ?: handoff.packageName?.let { context.packageManager.getLaunchIntentForPackage(it) }
        return if (fallback != null && start(context, fallback)) Outcome.CopiedAndOpened else Outcome.Copied
    }

    private fun share(context: Context, handoff: ManualHandoff, uriFor: (Attachment) -> Uri?): Outcome {
        val uris = handoff.attachments.map { it to uriFor(it) }
        val missing = uris.filter { it.second == null }.map { it.first.fileName }
        if (missing.isNotEmpty()) return Outcome.FilesMissing(missing)
        val address = handoff.recipientAddress?.trim()
        val pkg = handoff.packageName
        val target = ShareIntents.Target(
            packageName = pkg,
            whatsAppJid = if (pkg?.startsWith("com.whatsapp") == true) address?.let(ShareIntents::whatsAppJid) else null,
            email = address?.takeIf { pkg == null && it.contains('@') },
            smsNumber = address?.takeIf { pkg != null && !pkg.startsWith("com.whatsapp") && it.any(Char::isDigit) && !it.contains('@') && it.none(Char::isLetter) },
        )
        val intent = ShareIntents.build(uris.mapNotNull { it.second }, handoff.attachments.map { it.mimeType }, handoff.text, target)
        if (handoff.text.isNotBlank()) copy(context, handoff.text)
        val started = start(context, intent) || (pkg != null && start(context, Intent.createChooser(intent.setPackage(null), null)))
        return if (started) Outcome.Shared else Outcome.Copied
    }

    fun copy(context: Context, text: String) {
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("ReplyPilot message", text))
    }

    private fun start(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}
