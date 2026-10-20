package com.replypilot.features.autoresponder

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Science
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import com.replypilot.features.rules.RulesTab
import com.replypilot.features.rules.RulesViewModel
import com.replypilot.features.sequences.SequencesTab
import com.replypilot.features.templates.TemplatesTab
import com.replypilot.navigation.AppNavigator
import com.replypilot.navigation.AutomationTab
import com.replypilot.navigation.Routes
import kotlinx.coroutines.flow.merge

/** Automation hub: auto-reply overview, rules, sequences and templates. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutomationScreen(nav: AppNavigator, initialTab: AutomationTab) {
    var selected by rememberSaveable(initialTab) { mutableIntStateOf(initialTab.ordinal) }
    val tab = AutomationTab.entries[selected]
    val responder: ResponderViewModel = hiltViewModel()
    val rules: RulesViewModel = hiltViewModel()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(responder, rules) {
        merge(responder.messages, rules.messages).collect { snackbar.showSnackbar(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Automation") },
                actions = {
                    IconButton(onClick = { nav.navigate(Routes.simulator()) }) {
                        Icon(Icons.Filled.Science, contentDescription = "Open test lab")
                    }
                },
            )
        },
        floatingActionButton = {
            if (tab == AutomationTab.RULES) {
                ExtendedFloatingActionButton(
                    onClick = { nav.navigate(Routes.ruleEditor()) },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("New rule") },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            PrimaryTabRow(selectedTabIndex = selected) {
                AutomationTab.entries.forEachIndexed { index, entry ->
                    Tab(selected = selected == index, onClick = { selected = index }, text = { Text(entry.title, maxLines = 1) })
                }
            }
            Box(Modifier.fillMaxSize()) {
                when (tab) {
                    AutomationTab.RESPONDER -> ResponderTab(nav, responder) { selected = it.ordinal }
                    AutomationTab.RULES -> RulesTab(nav, rules)
                    AutomationTab.SEQUENCES -> SequencesTab(nav)
                    AutomationTab.TEMPLATES -> TemplatesTab(nav)
                }
            }
        }
    }
}
