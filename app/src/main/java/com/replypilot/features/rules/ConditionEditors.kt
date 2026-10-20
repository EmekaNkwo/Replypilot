package com.replypilot.features.rules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.replypilot.core.accessibility.ChatApp
import com.replypilot.core.design.Spacing
import com.replypilot.domain.model.AutoReplyRule
import com.replypilot.domain.model.AutomationSettings
import com.replypilot.domain.model.Contact
import com.replypilot.domain.model.ConversationKind
import com.replypilot.domain.model.MatchType
import com.replypilot.domain.model.RuleCondition

/** Inputs every condition editor may need. */
data class ConditionContext(
    val contacts: List<Contact>,
    val groups: List<String>,
    val otherRules: List<AutoReplyRule>,
    val settings: AutomationSettings,
    /** Opens the phone's contact picker; the callback receives the saved ReplyPilot contact id. */
    val pickFromPhone: ((String) -> Unit) -> Unit = {},
    /** Messaging apps the rule applies to that support choosing a chat inside the app. */
    val chatApps: List<ChatApp> = emptyList(),
    /** Opens [ChatApp] to choose chats; the callback receives the saved ReplyPilot contact ids. */
    val pickChat: (ChatApp, (List<String>) -> Unit) -> Unit = { _, _ -> },
)

@Composable
fun ConditionCard(
    index: Int,
    condition: RuleCondition,
    context: ConditionContext,
    showErrors: Boolean,
    onChange: (RuleCondition) -> Unit,
    onRemove: () -> Unit,
) {
    val error = RuleValidation.conditionError(condition)
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${index + 1}. ${titleFor(condition)}",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onRemove) { Icon(Icons.Filled.Delete, contentDescription = "Remove condition ${index + 1}") }
            }
            ConditionBody(condition, context, onChange)
            Text(
                "Reads as: ${RuleText.condition(condition, context.contacts.associate { it.id to it.displayName }, context.otherRules.associate { it.id to it.name })}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Regex problems are shown immediately; other gaps only once the user tries to save.
            val regexProblem = (condition as? RuleCondition.MessageMatches)?.takeIf { it.matchType == MatchType.REGEX && it.patterns.any(String::isNotBlank) } != null
            if (error != null && (showErrors || regexProblem)) {
                Text(error.replaceFirstChar { it.uppercase() }, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun titleFor(condition: RuleCondition): String = when (condition) {
    is RuleCondition.MessageMatches -> "Message text"
    is RuleCondition.SenderIsContact -> "Specific contacts"
    is RuleCondition.SenderInGroup -> "Contact group"
    RuleCondition.UnknownSender -> "Unknown sender"
    is RuleCondition.ConversationIs -> "Chat type"
    RuleCondition.WithinWorkingHours -> "During working hours"
    RuleCondition.OutsideWorkingHours -> "Outside working hours"
    is RuleCondition.OnWeekdays -> "Weekdays"
    is RuleCondition.InDateRange -> "Date range"
    is RuleCondition.AfterRuleExecuted -> "After another rule"
    RuleCondition.NoHumanTakeover -> "No human takeover"
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun ConditionBody(condition: RuleCondition, context: ConditionContext, onChange: (RuleCondition) -> Unit) {
    when (condition) {
        is RuleCondition.MessageMatches -> MessageMatchEditor(condition, onChange)
        is RuleCondition.SenderIsContact -> Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            context.chatApps.forEach { app ->
                FilledTonalButton(onClick = { context.pickChat(app) { ids -> onChange(condition.copy(contactIds = condition.contactIds + ids)) } }) {
                    Text("Choose chats in ${app.channel.displayName}")
                }
            }
            OutlinedButton(onClick = { context.pickFromPhone { id -> onChange(condition.copy(contactIds = condition.contactIds + id)) } }) {
                Text("Pick from phone contacts")
            }
            if (context.contacts.isEmpty()) Text("Or save contacts in ReplyPilot's Contacts.", style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                context.contacts.forEach { contact ->
                    val selected = contact.id in condition.contactIds
                    FilterChip(
                        selected = selected,
                        onClick = { onChange(condition.copy(contactIds = if (selected) condition.contactIds - contact.id else condition.contactIds + contact.id)) },
                        label = { Text(contact.displayName) },
                    )
                }
            }
        }
        is RuleCondition.SenderInGroup -> Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            if (context.groups.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    context.groups.forEach { group ->
                        val selected = condition.groups.any { it.equals(group, ignoreCase = true) }
                        FilterChip(
                            selected = selected,
                            onClick = {
                                onChange(condition.copy(groups = if (selected) condition.groups.filterNot { it.equals(group, true) }.toSet() else condition.groups + group))
                            },
                            label = { Text(group) },
                        )
                    }
                }
            }
            ChipsInput("Other group names", condition.groups.filter { g -> context.groups.none { it.equals(g, true) } }, { extra ->
                onChange(condition.copy(groups = condition.groups.filter { g -> context.groups.any { it.equals(g, true) } }.toSet() + extra))
            })
        }
        RuleCondition.UnknownSender -> Text(
            "Matches when the sender can't be matched to a saved contact. Notification-based channels identify people by the name shown.",
            style = MaterialTheme.typography.bodySmall,
        )
        is RuleCondition.ConversationIs -> SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            ConversationKind.entries.forEachIndexed { i, kind ->
                SegmentedButton(
                    selected = condition.kind == kind,
                    onClick = { onChange(RuleCondition.ConversationIs(kind)) },
                    shape = SegmentedButtonDefaults.itemShape(i, ConversationKind.entries.size),
                ) { Text(kind.label) }
            }
        }
        RuleCondition.WithinWorkingHours, RuleCondition.OutsideWorkingHours -> {
            val wh = context.settings.workingHours
            Text(
                "Working hours are ${wh.start}–${wh.end} on ${wh.days.sorted().joinToString(", ") { RuleText.dayShort(it) }}. Change them in Settings › Quiet hours & safeguards.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        is RuleCondition.OnWeekdays -> FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            (1..7).forEach { day ->
                val selected = day in condition.days
                FilterChip(
                    selected = selected,
                    onClick = { onChange(RuleCondition.OnWeekdays(if (selected) condition.days - day else condition.days + day)) },
                    label = { Text(RuleText.dayShort(day)) },
                )
            }
        }
        is RuleCondition.InDateRange -> Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            OutlinedTextField(condition.startDate, { onChange(condition.copy(startDate = it.trim())) }, label = { Text("From (yyyy-mm-dd)") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(condition.endDate, { onChange(condition.copy(endDate = it.trim())) }, label = { Text("To (yyyy-mm-dd)") }, singleLine = true, modifier = Modifier.weight(1f))
        }
        is RuleCondition.AfterRuleExecuted -> Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            if (context.otherRules.isEmpty()) {
                Text("Create another rule first.", style = MaterialTheme.typography.bodySmall)
            } else {
                val selected = context.otherRules.firstOrNull { it.id == condition.ruleId }
                DropdownSelector(
                    label = "Rule that replied",
                    selected = selected,
                    options = context.otherRules,
                    optionLabel = { it?.name ?: "Choose a rule" },
                    onSelect = { rule -> rule?.let { onChange(condition.copy(ruleId = it.id)) } },
                )
            }
            NumberField("Within", condition.withinMinutes, { onChange(condition.copy(withinMinutes = it)) }, min = 1, max = 10_080, suffix = "min")
        }
        RuleCondition.NoHumanTakeover -> Text(
            "Skips the conversation while you've taken it over. ReplyPilot already enforces this for every rule; adding it makes the rule self-explanatory.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun MessageMatchEditor(condition: RuleCondition.MessageMatches, onChange: (RuleCondition) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        DropdownSelector(
            label = "Message",
            selected = condition.matchType,
            options = MatchType.entries,
            optionLabel = { it.label },
            onSelect = { type ->
                val patterns = if (type == MatchType.REGEX) condition.patterns.take(1) else condition.patterns
                onChange(condition.copy(matchType = type, patterns = if (type == MatchType.ANY_MESSAGE) emptyList() else patterns))
            },
        )
        when (condition.matchType) {
            MatchType.ANY_MESSAGE -> Text("Every incoming message matches this condition.", style = MaterialTheme.typography.bodySmall)
            MatchType.REGEX -> {
                val pattern = condition.patterns.firstOrNull().orEmpty()
                OutlinedTextField(
                    value = pattern,
                    onValueChange = { onChange(condition.copy(patterns = listOf(it))) },
                    label = { Text("Pattern") },
                    placeholder = { Text("order\\s*#?\\d{4}") },
                    isError = pattern.isNotBlank() && RuleValidation.regexError(pattern) != null,
                    supportingText = { Text("Advanced. Patterns are checked for safety and time-limited.") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            else -> ChipsInput(
                label = if (condition.matchType == MatchType.ANY_KEYWORD) "Keywords" else "Words or phrases",
                values = condition.patterns,
                onChange = { onChange(condition.copy(patterns = it)) },
                hint = "e.g. price, cost",
            )
        }
        if (condition.matchType != MatchType.ANY_MESSAGE) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = condition.caseSensitive, onCheckedChange = { onChange(condition.copy(caseSensitive = it)) })
                Text("Match upper/lower case exactly", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** "Add condition" button with a menu of every condition type. */
@Composable
fun AddConditionButton(onAdd: (ConditionKind) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Text("Add condition")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ConditionKind.entries.forEach { kind ->
                DropdownMenuItem(text = { Text(kind.label) }, onClick = { open = false; onAdd(kind) })
            }
        }
    }
}
