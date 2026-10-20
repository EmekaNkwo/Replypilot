package com.replypilot.features.scheduler

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.PrimaryScrollableTabRow
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.replypilot.domain.model.ExecutionItem
import com.replypilot.navigation.AppNavigator
import com.replypilot.navigation.Filters
import com.replypilot.navigation.MessagesTab
import com.replypilot.navigation.Routes

/** Callbacks shared by every tab that lists executions. */
class ExecutionCallbacks(
    val open: (ExecutionItem) -> Unit,
    val act: (ExecutionItem, ExecutionAction, String?) -> Unit,
    val handoff: (ExecutionItem) -> Unit,
    val clearFailed: () -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessagesScreen(nav: AppNavigator, initialTab: MessagesTab, filter: String?) {
    val viewModel: MessagesViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    var tab by rememberSaveable(initialTab) { mutableStateOf(initialTab) }

    LaunchedEffect(viewModel) { viewModel.messages.collect { snackbar.showSnackbar(it) } }

    val callbacks = remember(viewModel, nav) {
        ExecutionCallbacks(
            open = { nav.navigate(Routes.execution(it.id)) },
            act = { item, action, body -> viewModel.act(item.id, action, body) },
            handoff = { viewModel.manualSend(context, it) },
            clearFailed = viewModel::clearFailed,
        )
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = { TopAppBar(title = { Text("Messages") }) },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { nav.navigate(Routes.composer()) },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Schedule") },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            PrimaryScrollableTabRow(selectedTabIndex = tab.ordinal, edgePadding = 0.dp) {
                MessagesTab.entries.forEach { entry ->
                    Tab(selected = tab == entry, onClick = { tab = entry }, text = { Text(entry.title) })
                }
            }
            when (tab) {
                MessagesTab.SCHEDULED -> ScheduledTab(state, onOpen = { nav.navigate(Routes.schedule(it)) }, onCreate = { nav.navigate(Routes.composer()) })
                MessagesTab.CALENDAR -> CalendarTab(state, callbacks)
                MessagesTab.OUTBOX -> OutboxTab(state, initialFilter = filter, callbacks = callbacks)
                MessagesTab.SENT -> SentTab(state, todayOnly = filter == Filters.TODAY, callbacks = callbacks)
            }
        }
    }
}
