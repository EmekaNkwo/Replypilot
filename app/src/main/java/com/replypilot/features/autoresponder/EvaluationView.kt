package com.replypilot.features.autoresponder

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.replypilot.core.design.LocalStatusColors
import com.replypilot.core.design.Notice
import com.replypilot.core.design.NoticeTone
import com.replypilot.core.design.Pill
import com.replypilot.core.design.Spacing
import com.replypilot.domain.rules.CheckTrace
import com.replypilot.domain.rules.Evaluation
import com.replypilot.domain.rules.RuleTrace

/**
 * Explains an evaluation: the outcome first, then the safeguards and each rule's checks in evaluation order.
 * Everything shown comes from the domain [Evaluation]; nothing is recomputed here.
 */
@Composable
fun EvaluationView(evaluation: Evaluation, modifier: Modifier = Modifier, bannerText: String = "Simulation only — nothing is sent") {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Notice(bannerText, tone = NoticeTone.SIMULATED, title = "Simulated result")
        OutcomeCard(evaluation)
        if (evaluation.optOutDetected) {
            Notice("This message is an opt-out keyword. In real use the sender is marked as opted out and their sequences stop.", tone = NoticeTone.WARNING)
        }
        SafeguardList(evaluation.globalChecks)
        if (evaluation.traces.isEmpty()) {
            Text("No enabled rules apply to this channel.", style = MaterialTheme.typography.bodyMedium)
        }
        evaluation.traces.forEachIndexed { index, trace -> TraceCard(index + 1, trace) }
    }
}

@Composable
private fun OutcomeCard(evaluation: Evaluation) {
    val colors = LocalStatusColors.current
    val suppression = evaluation.blockingSuppression
    val first = evaluation.firstMatch
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(Spacing.md).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text("Matched rule", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(first?.rule?.name ?: "None", style = MaterialTheme.typography.titleMedium)
            }
            when {
                evaluation.wouldSend -> {
                    Pill("Would send", colors.success)
                    evaluation.replies.forEach { reply ->
                        Text("${reply.rule.name} · ${reply.mode.label}${if (reply.delaySeconds > 0) " · after ${reply.delaySeconds}s" else ""}", style = MaterialTheme.typography.labelMedium)
                        Text("“${reply.text}”", style = MaterialTheme.typography.bodyLarge)
                    }
                }
                suppression != null -> {
                    Pill("Would not send", colors.failed)
                    Text("${suppression.reason.label}: ${suppression.detail}", style = MaterialTheme.typography.bodyMedium)
                    first?.response?.let { Text("Reply that was held back: “$it”", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                else -> {
                    Pill("No reply", colors.paused)
                    Text("No rule matched this message.", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun SafeguardList(checks: List<CheckTrace>) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val failed = checks.filter { !it.passed }
    Column {
        TextButton(onClick = { expanded = !expanded }) {
            Text(
                if (failed.isEmpty()) "Safeguards: all ${checks.size} passed" else "Safeguards: ${failed.size} blocking of ${checks.size}",
                modifier = Modifier.weight(1f, fill = false),
            )
            Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = if (expanded) "Hide safeguards" else "Show safeguards")
        }
        (if (expanded) checks else failed).forEach { CheckRow(it) }
    }
}

@Composable
private fun TraceCard(position: Int, trace: RuleTrace) {
    val colors = LocalStatusColors.current
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text("#$position", style = MaterialTheme.typography.labelLarge, color = colors.autoReply)
                Text(trace.rule.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                when {
                    trace.matched && trace.suppression == null -> Pill("Matched", colors.success)
                    trace.matched -> Pill("Matched · held back", colors.review)
                    else -> Pill("No match", colors.paused)
                }
            }
            trace.checks.forEach { CheckRow(it) }
            trace.suppression?.let { Text("${it.reason.label}: ${it.detail}", color = colors.review, style = MaterialTheme.typography.bodySmall) }
            if (trace.matched && trace.rule.stopProcessing) {
                Text("Stops here: later rules aren't checked.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun CheckRow(check: CheckTrace) {
    val colors = LocalStatusColors.current
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(Spacing.sm), modifier = Modifier.padding(vertical = 2.dp)) {
        Icon(
            if (check.passed) Icons.Filled.CheckCircle else Icons.Filled.Cancel,
            contentDescription = if (check.passed) "Passed" else "Failed",
            tint = if (check.passed) colors.success else colors.failed,
            modifier = Modifier.size(18.dp),
        )
        Column {
            Text(check.label, style = MaterialTheme.typography.bodyMedium)
            if (check.detail.isNotBlank()) Text(check.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
