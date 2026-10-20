package com.replypilot.features.rules

import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import com.replypilot.core.design.Spacing

/** A labelled button that opens a menu of options. Simpler and more robust than an exposed dropdown. */
@Composable
fun <T> DropdownSelector(
    label: String,
    selected: T,
    options: List<T>,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
                Text(optionLabel(selected), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(Icons.Filled.ArrowDropDown, contentDescription = "Choose $label")
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { option ->
                    DropdownMenuItem(text = { Text(optionLabel(option)) }, onClick = { onSelect(option); open = false })
                }
            }
        }
    }
}

/** Integer field that keeps whatever the user is typing, reporting only valid values upward. */
@Composable
fun NumberField(label: String, value: Int, onChange: (Int) -> Unit, modifier: Modifier = Modifier, min: Int = 0, max: Int = 1_000_000, suffix: String? = null) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    val parsed = text.toIntOrNull()
    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            text = input.filter { it.isDigit() }.take(7)
            text.toIntOrNull()?.takeIf { it in min..max }?.let(onChange)
        },
        label = { Text(label) },
        suffix = suffix?.let { { Text(it) } },
        isError = parsed == null || parsed !in min..max,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

/** Like [NumberField] but blank means "no limit" (null). */
@Composable
fun OptionalNumberField(label: String, value: Int?, onChange: (Int?) -> Unit, modifier: Modifier = Modifier, placeholder: String = "No limit") {
    var text by remember(value) { mutableStateOf(value?.toString().orEmpty()) }
    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            text = input.filter { it.isDigit() }.take(6)
            if (text.isEmpty()) onChange(null) else text.toIntOrNull()?.takeIf { it >= 1 }?.let(onChange)
        },
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

/** Entry field plus removable chips; commas split several values at once. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChipsInput(label: String, values: List<String>, onChange: (List<String>) -> Unit, modifier: Modifier = Modifier, hint: String = "Type and press Add") {
    var entry by remember { mutableStateOf("") }
    fun commit() {
        val added = entry.split(',').map { it.trim() }.filter { it.isNotEmpty() && it !in values }
        if (added.isNotEmpty()) onChange(values + added)
        entry = ""
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = entry,
                onValueChange = { entry = it },
                label = { Text(label) },
                placeholder = { Text(hint) },
                singleLine = true,
                // Keywords are matched literally, so the keyboard must not "correct" them (Gboard turned "rptest" into "forest").
                // Gboard ignores the autocorrect hint on plain text fields but honours it for URI fields.
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done,
                    autoCorrectEnabled = false, capitalization = KeyboardCapitalization.None,
                ),
                keyboardActions = KeyboardActions(onDone = { commit() }),
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { commit() }, enabled = entry.isNotBlank()) { Text("Add") }
        }
        if (values.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                values.forEach { value ->
                    InputChip(
                        selected = false,
                        onClick = { onChange(values - value) },
                        label = { Text(value) },
                        trailingIcon = { Icon(Icons.Filled.Close, contentDescription = "Remove $value", tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                        colors = InputChipDefaults.inputChipColors(),
                    )
                }
            }
        }
    }
}

/** A full-width row with a label, optional explanation and a switch; the whole row is the touch target. */
@Composable
fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier, detail: String? = null) {
    Row(
        modifier = modifier.fillMaxWidth().toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}
