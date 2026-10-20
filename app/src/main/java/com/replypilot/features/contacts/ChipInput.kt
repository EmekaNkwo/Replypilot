package com.replypilot.features.contacts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import com.replypilot.core.design.Spacing

/** Free-text chips (tags, groups): type and press add/enter; tap a chip's x to remove it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChipInput(label: String, values: Set<String>, onChange: (Set<String>) -> Unit, modifier: Modifier = Modifier) {
    var text by rememberSaveable(label) { mutableStateOf("") }
    fun commit() {
        val value = text.trim().take(40)
        if (value.isNotEmpty()) onChange(values + value)
        text = ""
    }
    Column(modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text(label) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onDone = { commit() }),
            trailingIcon = { IconButton(onClick = { commit() }, enabled = text.isNotBlank()) { Icon(Icons.Filled.Add, contentDescription = "Add $label") } },
            modifier = Modifier.fillMaxWidth(),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            values.forEach { value ->
                InputChip(
                    selected = false,
                    onClick = { onChange(values - value) },
                    label = { Text(value) },
                    trailingIcon = { Icon(Icons.Filled.Close, contentDescription = "Remove $value") },
                )
            }
        }
    }
}
