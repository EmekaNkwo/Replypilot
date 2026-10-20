package com.replypilot.features.autoresponder

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.replypilot.core.design.BackTopBar
import com.replypilot.navigation.AppNavigator

private enum class LabTab(val title: String) { RULES("Rules"), QUEUE("Queue"), DEMO("Live demo") }

/** The automation test lab. Nothing here can reach a real recipient. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SimulatorScreen(nav: AppNavigator, ruleId: String?) {
    val viewModel: SimulatorViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var selected by rememberSaveable { mutableIntStateOf(0) }

    LaunchedEffect(viewModel) { viewModel.messages.collect { snackbar.showSnackbar(it) } }

    Scaffold(
        topBar = { BackTopBar(if (ruleId != null) "Test lab · ${state.focusedRule?.name.orEmpty()}" else "Test lab", onBack = nav::back) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            PrimaryTabRow(selectedTabIndex = selected) {
                LabTab.entries.forEachIndexed { index, tab ->
                    Tab(selected = selected == index, onClick = { selected = index }, text = { Text(tab.title) })
                }
            }
            when (LabTab.entries[selected]) {
                LabTab.RULES -> RuleTestSection(state, viewModel)
                LabTab.QUEUE -> QueueLabSection(state, viewModel)
                LabTab.DEMO -> DemoPipelineSection(state, viewModel, nav)
            }
        }
    }
}
