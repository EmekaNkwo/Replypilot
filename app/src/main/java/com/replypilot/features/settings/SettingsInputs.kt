package com.replypilot.features.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.replypilot.core.design.Spacing
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale

/** A settings row that navigates somewhere. */
@Composable
fun NavRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClick = onClick).padding(vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
    }
}

@Composable
fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit, subtitle: String? = null, enabled: Boolean = true) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .clickable(enabled = enabled, role = Role.Switch) { onChange(!checked) }
            .padding(vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = Spacing.md)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/** Integer field that only commits values inside [range]; shows why otherwise. Blank commits null when [allowBlank]. */
@Composable
fun NumberField(
    label: String,
    value: Int?,
    range: IntRange,
    onCommit: (Int?) -> Unit,
    modifier: Modifier = Modifier,
    allowBlank: Boolean = false,
    suffix: String? = null,
) {
    var text by remember(value) { mutableStateOf(value?.toString().orEmpty()) }
    val parsed = text.trim().toIntOrNull()
    val error = when {
        text.isBlank() && allowBlank -> null
        parsed == null -> "Enter a number"
        parsed !in range -> "Between ${range.first} and ${range.last}"
        else -> null
    }
    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            text = input.filter { it.isDigit() }.take(6)
            val number = text.toIntOrNull()
            when {
                text.isBlank() && allowBlank -> onCommit(null)
                number != null && number in range -> onCommit(number)
            }
        },
        label = { Text(label) },
        suffix = suffix?.let { { Text(it) } },
        isError = error != null,
        supportingText = error?.let { { Text(it) } } ?: if (allowBlank) ({ Text("Leave empty for no limit") }) else null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = modifier.fillMaxWidth(),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeButton(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, modifier = modifier) { Text("$label  $value") }
    if (open) {
        val initial = runCatching { LocalTime.parse(value) }.getOrDefault(LocalTime.of(9, 0))
        val pickerState = rememberTimePickerState(initial.hour, initial.minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(label) },
            text = { TimePicker(state = pickerState) },
            confirmButton = {
                TextButton(onClick = {
                    onChange("%02d:%02d".format(pickerState.hour, pickerState.minute))
                    open = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WeekdayChips(selected: Set<Int>, onChange: (Set<Int>) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        (1..7).forEach { day ->
            val name = DayOfWeek.of(day).getDisplayName(TextStyle.SHORT, Locale.getDefault())
            FilterChip(
                selected = day in selected,
                onClick = { onChange(if (day in selected) selected - day else selected + day) },
                label = { Text(name) },
            )
        }
    }
}

/** Editable list of short strings (keywords, phrases, senders). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChipListEditor(label: String, values: List<String>, onChange: (List<String>) -> Unit, placeholder: String) {
    var draft by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            values.forEach { value ->
                InputChip(
                    selected = false,
                    onClick = { onChange(values - value) },
                    label = { Text(value) },
                    trailingIcon = { Icon(Icons.Filled.Close, contentDescription = "Remove $value") },
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = draft, onValueChange = { draft = it.take(80) }, placeholder = { Text(placeholder) },
                singleLine = true, modifier = Modifier.weight(1f),
                // Keywords and phrases are matched literally; don't let the keyboard rewrite them.
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
            )
            TextButton(
                onClick = {
                    val item = draft.trim()
                    if (item.isNotEmpty() && values.none { it.equals(item, ignoreCase = true) }) onChange(values + item)
                    draft = ""
                },
                enabled = draft.isNotBlank(),
                modifier = Modifier.width(72.dp),
            ) { Text("Add") }
        }
    }
}

/** Runs [block] on every ON_RESUME of the hosting lifecycle (to re-check permissions and status). */
@Composable
fun OnResume(block: () -> Unit) {
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        block()
        onPauseOrDispose { }
    }
}
