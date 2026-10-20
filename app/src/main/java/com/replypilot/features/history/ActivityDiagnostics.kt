package com.replypilot.features.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.replypilot.core.design.Format
import com.replypilot.core.design.Notice
import com.replypilot.core.design.NoticeTone
import com.replypilot.core.design.ResultPill
import com.replypilot.core.design.Spacing
import com.replypilot.domain.model.ActivityEvent
import com.replypilot.domain.model.ActivityType
import com.replypilot.navigation.AppNavigator
import com.replypilot.navigation.Routes

/** Plain-language explanation of one history event and what the user can do next. */
data class Diagnosis(val meaning: String, val nextStep: String?, val actionLabel: String?, val route: String?)

fun diagnose(event: ActivityEvent): Diagnosis {
    val execution = event.executionId?.let(Routes::execution)
    return when (event.type) {
        ActivityType.PERMISSION_UNAVAILABLE -> Diagnosis(
            "ReplyPilot needed a permission that is currently off, so the action couldn't run.",
            "Review permissions. Optional ones are labelled; only grant what the feature needs.",
            "Open permissions", Routes.SETTINGS_PERMISSIONS,
        )
        ActivityType.MANUAL_ACTION_REQUIRED -> Diagnosis(
            "This channel can't send unattended, or sending failed in a way you can finish yourself.",
            "Open the message to send it from the messaging app, then mark it as sent.",
            "Open message".takeIf { execution != null }, execution,
        )
        ActivityType.EXECUTION_FAILED -> Diagnosis(
            "The send did not succeed and won't be retried automatically.",
            "Check the reason, fix the cause (address, configuration, permission), then retry from the message.",
            "Open message".takeIf { execution != null }, execution,
        )
        ActivityType.RETRY_SCHEDULED -> Diagnosis(
            "A temporary problem occurred. ReplyPilot will try again after a short, growing delay, up to the retry limit.",
            null, "Open message".takeIf { execution != null }, execution,
        )
        ActivityType.AUTO_REPLY_SUPPRESSED -> Diagnosis(
            "A reply was not sent because a safeguard or condition blocked it (shown in the reason). This is usually intended.",
            "Use the rule simulator to see every check for a similar message without sending anything.",
            "Open simulator", Routes.simulator(event.ruleId),
        )
        ActivityType.NO_RULE_MATCHED -> Diagnosis(
            "An incoming message was checked against your enabled rules and none applied.",
            "Test the message in the simulator to see which conditions failed.",
            "Open simulator", Routes.simulator(),
        )
        ActivityType.RULE_MATCHED -> Diagnosis(
            "An incoming message satisfied a rule's conditions. The reason lists the checks that passed.",
            null, "Test this rule", Routes.simulator(event.ruleId),
        )
        ActivityType.QUEUE_RECOVERED -> Diagnosis(
            "The app stopped while this message was being sent. ReplyPilot can't tell whether it went out, so it did not resend it.",
            "Check the conversation; if it wasn't sent, retry it from the message.",
            "Open message".takeIf { execution != null }, execution,
        )
        ActivityType.EXECUTION_EXPIRED -> Diagnosis(
            "The message wasn't sent before its expiry time (for example the phone was off or automation was paused), so it was dropped rather than sent late.",
            "Increase the expiry window in the schedule if late sends are acceptable.",
            "Open message".takeIf { execution != null }, execution,
        )
        ActivityType.EXECUTION_SKIPPED -> Diagnosis(
            "The send was skipped on purpose (opt-out, quiet hours, or the source was stopped).",
            null, "Open message".takeIf { execution != null }, execution,
        )
        ActivityType.REPLY_AWAITING_APPROVAL -> Diagnosis(
            "A reply was prepared and is waiting for your approval. Nothing is sent until you approve it.",
            null, "Review".takeIf { execution != null }, execution,
        )
        ActivityType.MESSAGE_SUBMITTED, ActivityType.REPLY_SENT -> Diagnosis(
            if (event.simulated) "This was simulated — no real message was sent." else "The channel accepted the message. That is not proof of delivery.",
            null, "Open message".takeIf { execution != null }, execution,
        )
        ActivityType.DELIVERY_CONFIRMED -> Diagnosis("The channel confirmed delivery.", null, "Open message".takeIf { execution != null }, execution)
        else -> Diagnosis("Recorded for your audit trail.", null, null, null)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityDiagnosticsSheet(event: ActivityEvent, ruleName: String?, nav: AppNavigator, onDismiss: () -> Unit) {
    val diagnosis = diagnose(event)
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.xl).padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(event.type.label, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                ResultPill(event.result)
            }
            if (event.simulated) {
                Notice("This event came from demo mode or the simulator. Nothing was sent to a real person.", tone = NoticeTone.SIMULATED)
            }
            Detail("When", "${Format.dateTime(event.at)} (${Format.relative(event.at)})")
            event.channel?.let { Detail("Channel", it.displayName) }
            event.source?.let { Detail("Source", it.label) }
            ruleName?.let { Detail("Rule", it) }
            event.recipientRef?.let { Detail("Contact", it) }
            event.attempt?.takeIf { it > 0 }?.let { Detail("Attempt", "$it") }
            event.reason?.let { Detail("Reason", it) }
            Spacer(Modifier.height(Spacing.sm))
            Text("What this means", style = MaterialTheme.typography.titleSmall)
            Text(diagnosis.meaning, style = MaterialTheme.typography.bodyMedium)
            diagnosis.nextStep?.let {
                Text("What you can do", style = MaterialTheme.typography.titleSmall)
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
            if (diagnosis.actionLabel != null && diagnosis.route != null) {
                Button(onClick = { onDismiss(); nav.navigate(diagnosis.route) }) { Text(diagnosis.actionLabel) }
            }
            Text(
                "Message text isn't stored in history.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Detail(label: String, value: String) {
    Row {
        Text(label, Modifier.weight(0.35f), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(0.65f), style = MaterialTheme.typography.bodyMedium)
    }
}
