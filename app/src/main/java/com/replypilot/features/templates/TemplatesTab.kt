package com.replypilot.features.templates

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.widget.Toast
import androidx.compose.runtime.LaunchedEffect
import com.replypilot.core.design.EmptyState
import com.replypilot.core.design.ListCard
import com.replypilot.core.design.Pill
import com.replypilot.core.design.SectionHeader
import com.replypilot.core.design.Spacing
import com.replypilot.domain.model.MessageTemplate
import com.replypilot.navigation.AppNavigator
import com.replypilot.navigation.Routes

/** Content of the Templates tab inside the Automation section (no Scaffold of its own). */
@Composable
fun TemplatesTab(nav: AppNavigator) {
    val viewModel: TemplatesViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(state.message) {
        state.message?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.messageShown()
        }
    }

    Box(Modifier.fillMaxSize()) {
        when {
            !state.loaded -> Unit
            state.total == 0 -> EmptyState(
                icon = Icons.Filled.Description,
                title = "No templates yet",
                body = "Templates are reusable messages with personalisation like {{firstName}}. " +
                    "Use them in scheduled messages, sequences and auto-reply rules.",
                action = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        Button(onClick = viewModel::addStarterTemplates) { Text("Add starter templates") }
                        OutlinedButton(onClick = { nav.navigate(Routes.templateEditor()) }) { Text("New template") }
                    }
                },
            )
            else -> LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                item {
                    OutlinedTextField(
                        value = state.query,
                        onValueChange = viewModel::setQuery,
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        placeholder = { Text("Search templates") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (state.groups.isEmpty()) {
                    item { Text("No templates match \"${state.query}\".", modifier = Modifier.padding(Spacing.lg)) }
                }
                state.groups.forEach { (category, templates) ->
                    item(key = "header-${category.name}") { SectionHeader(category.label) }
                    items(templates, key = { it.id }) { template ->
                        TemplateCard(
                            template = template,
                            onUse = { nav.navigate(Routes.composer(templateId = template.id)) },
                            onEdit = { nav.navigate(Routes.templateEditor(template.id)) },
                        )
                    }
                }
            }
        }
        if (state.total > 0) {
            ExtendedFloatingActionButton(
                onClick = { nav.navigate(Routes.templateEditor()) },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("New template") },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            )
        }
    }
}

@Composable
private fun TemplateCard(template: MessageTemplate, onUse: () -> Unit, onEdit: () -> Unit) {
    ListCard(onClick = onEdit) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(template.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Pill(template.category.label, MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(Spacing.xs))
            Text(
                template.body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onEdit) { Text("Edit") }
                TextButton(onClick = onUse) { Text("Use in message") }
            }
        }
    }
}
