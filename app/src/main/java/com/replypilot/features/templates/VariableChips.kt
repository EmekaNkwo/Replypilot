package com.replypilot.features.templates

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.replypilot.core.design.Notice
import com.replypilot.core.design.NoticeTone
import com.replypilot.core.design.Spacing
import com.replypilot.domain.templates.KnownVariables

/** Tappable placeholders that insert `{{name}}` into a message body. Shared by templates and sequences. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VariableChips(customFields: Set<String>, onInsert: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Text("Insert a variable", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            KnownVariables.all.forEach { variable ->
                AssistChip(
                    onClick = { onInsert(variable.name) },
                    label = { Text("{{${variable.name}}}") },
                    modifier = Modifier.semantics { contentDescription = "Insert ${variable.description}" },
                )
            }
            customFields.filter { it !in KnownVariables.names }.forEach { field ->
                AssistChip(
                    onClick = { onInsert(field) },
                    label = { Text("{{$field}}") },
                    modifier = Modifier.semantics { contentDescription = "Insert contact field $field" },
                )
            }
        }
    }
}

/** Explains unknown placeholders and placeholders without a fallback before anything is saved. */
@Composable
fun VariableWarnings(usages: List<VariableUsage>, modifier: Modifier = Modifier) {
    val unknown = usages.filter { !it.known }
    val noFallback = usages.filter { it.known && !it.hasFallback && it.name !in ALWAYS_AVAILABLE }
    if (unknown.isEmpty() && noFallback.isEmpty()) return
    val text = buildString {
        if (unknown.isNotEmpty()) {
            append("Unknown: ${unknown.joinToString { "{{${it.name}}}" }}. ReplyPilot has no value for these. ")
        }
        if (noFallback.isNotEmpty()) {
            append("No fallback: ${noFallback.joinToString { "{{${it.name}}}" }}. ")
        }
        append("A message with a missing value won't be sent until a value is available — add a fallback like {{firstName|there}}.")
    }
    Notice(text, modifier, tone = NoticeTone.WARNING, title = "Check your variables")
}

private val ALWAYS_AVAILABLE = setOf("senderName")
