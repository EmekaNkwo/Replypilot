package com.replypilot.features.templates

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.replypilot.core.design.BackTopBar
import com.replypilot.core.design.ConfirmDialog
import com.replypilot.core.design.Notice
import com.replypilot.core.design.SectionHeader
import com.replypilot.core.design.Spacing
import com.replypilot.domain.model.TemplateCategory
import com.replypilot.navigation.AppNavigator

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TemplateEditorScreen(nav: AppNavigator, id: String?) {
    val viewModel: TemplateEditorViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(id) { viewModel.load(id) }
    LaunchedEffect(state.finished) { if (state.finished) nav.back() }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.messageShown()
        }
    }

    Scaffold(
        topBar = {
            BackTopBar(if (state.isNew) "New template" else "Edit template", nav::back) {
                if (!state.isNew) {
                    IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, contentDescription = "Delete template") }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (state.notFound) {
            Notice("This template no longer exists.", Modifier.padding(padding).padding(Spacing.lg))
            return@Scaffold
        }
        if (state.loading) return@Scaffold
        Column(
            Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            OutlinedTextField(
                value = state.name,
                onValueChange = viewModel::setName,
                label = { Text("Name") },
                isError = state.nameError != null,
                supportingText = state.nameError?.let { { Text(it) } },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            CategoryDropdown(state.category, viewModel::setCategory)
            OutlinedTextField(
                value = state.body,
                onValueChange = viewModel::setBody,
                label = { Text("Message") },
                isError = state.bodyError != null,
                supportingText = { Text(state.bodyError ?: "${state.body.length} characters") },
                minLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )
            VariableChips(state.customFields, viewModel::insertVariable)
            VariableWarnings(state.usages)

            SectionHeader("Preview with sample values")
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Text(
                    state.preview.text.ifBlank { "Your message will appear here." },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(Spacing.md),
                )
            }
            if (state.usages.isNotEmpty()) {
                Text(
                    "Uses: " + state.usages.joinToString { "{{${it.name}}}" + if (it.hasFallback) " (has fallback)" else "" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Notice("Templates are shared: scheduled messages, sequences and auto-reply rules that use this template pick up your changes.")
            Button(onClick = viewModel::save, enabled = !state.saving, modifier = Modifier.fillMaxWidth()) {
                Text(if (state.saving) "Saving…" else "Save template")
            }
            Spacer(Modifier.height(Spacing.xl))
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = "Delete template?",
            text = "Rules that reference \"${state.name}\" will fall back to their own response text.",
            confirmLabel = "Delete",
            onConfirm = viewModel::delete,
            onDismiss = { confirmDelete = false },
            destructive = true,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryDropdown(selected: TemplateCategory, onSelect: (TemplateCategory) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected.label,
            onValueChange = {},
            readOnly = true,
            label = { Text("Category") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            TemplateCategory.entries.forEach { category ->
                DropdownMenuItem(text = { Text(category.label) }, onClick = { onSelect(category); expanded = false })
            }
        }
    }
}
