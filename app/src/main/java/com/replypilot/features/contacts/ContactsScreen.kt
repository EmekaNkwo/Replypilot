package com.replypilot.features.contacts

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.replypilot.core.design.ChannelAvatar
import com.replypilot.core.design.EmptyState
import com.replypilot.core.design.ListCard
import com.replypilot.core.design.LocalStatusColors
import com.replypilot.core.design.Pill
import com.replypilot.core.design.Spacing
import com.replypilot.domain.model.Contact
import com.replypilot.navigation.AppNavigator
import com.replypilot.navigation.ContactsTab
import com.replypilot.navigation.Routes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsScreen(nav: AppNavigator, initialTab: ContactsTab) {
    val viewModel: ContactsViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    var tab by rememberSaveable(initialTab) { mutableStateOf(initialTab) }
    var menu by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.messageShown()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Contacts") },
                actions = {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More options") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Import from CSV") }, onClick = { menu = false; nav.navigate(Routes.CONTACT_IMPORT) })
                    }
                },
            )
        },
        floatingActionButton = {
            if (tab == ContactsTab.CONTACTS) {
                FloatingActionButton(onClick = { nav.navigate(Routes.contactEditor()) }) { Icon(Icons.Filled.Add, contentDescription = "Add contact") }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            PrimaryTabRow(selectedTabIndex = tab.ordinal) {
                ContactsTab.entries.forEach { t -> Tab(selected = tab == t, onClick = { tab = t }, text = { Text(t.title) }) }
            }
            when (tab) {
                ContactsTab.CONTACTS -> ContactList(state, viewModel, nav)
                ContactsTab.GROUPS -> GroupsTab(state, viewModel, nav)
                ContactsTab.PERSONALIZATION -> PersonalizationTab(state, viewModel)
            }
        }
    }
}

@Composable
private fun ContactList(state: ContactsState, viewModel: ContactsViewModel, nav: AppNavigator) {
    if (state.loaded && state.all.isEmpty()) {
        EmptyState(
            icon = Icons.Filled.People,
            title = "No contacts yet",
            body = "Contacts let rules recognise who's writing and personalise messages with their name and details. " +
                "Add them one by one or import a CSV file.",
            action = {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Button(onClick = { nav.navigate(Routes.contactEditor()) }) { Text("Add contact") }
                    Button(onClick = { nav.navigate(Routes.CONTACT_IMPORT) }) { Text("Import CSV") }
                }
            },
        )
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 88.dp),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        item {
            OutlinedTextField(
                value = state.query, onValueChange = viewModel::setQuery,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                placeholder = { Text("Search by name or address") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
        }
        if (state.tags.isNotEmpty()) {
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    FilterChip(selected = state.tagFilter == null, onClick = { viewModel.setTagFilter(null) }, label = { Text("All") })
                    state.tags.forEach { tag ->
                        FilterChip(
                            selected = state.tagFilter == tag,
                            onClick = { viewModel.setTagFilter(if (state.tagFilter == tag) null else tag) },
                            label = { Text(tag) },
                        )
                    }
                }
            }
        }
        if (state.visible.isEmpty()) {
            item { Text("No contacts match.", modifier = Modifier.padding(Spacing.lg)) }
        }
        items(state.visible, key = { it.id }) { contact -> ContactRow(contact) { nav.navigate(Routes.contactEditor(contact.id)) } }
    }
}

@Composable
internal fun ContactRow(contact: Contact, onClick: () -> Unit) {
    ListCard(onClick = onClick) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(contact.displayName.ifBlank { "Unnamed" }, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (contact.optedOut) Pill("Opted out", LocalStatusColors.current.failed)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = Spacing.xs)) {
                contact.channels.forEach { ChannelAvatar(it) }
                if (contact.identities.isEmpty()) {
                    Text("No addresses", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (contact.tags.isNotEmpty()) {
                Row(Modifier.padding(top = Spacing.xs).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    contact.tags.forEach { Pill(it, MaterialTheme.colorScheme.tertiary) }
                }
            }
        }
    }
}
