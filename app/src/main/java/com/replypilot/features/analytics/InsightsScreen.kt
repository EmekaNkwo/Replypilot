package com.replypilot.features.analytics

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.replypilot.features.history.ActivityTab
import com.replypilot.navigation.AppNavigator
import com.replypilot.navigation.InsightsTab
import com.replypilot.navigation.Routes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InsightsScreen(nav: AppNavigator, initialTab: InsightsTab, filter: String?) {
    val viewModel: InsightsViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(initialTab) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Insights") },
                actions = {
                    IconButton(onClick = { nav.navigate(Routes.SETTINGS) }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            PrimaryTabRow(selectedTabIndex = tab.ordinal) {
                InsightsTab.entries.forEach { entry ->
                    Tab(selected = tab == entry, onClick = { tab = entry }, text = { Text(entry.title) })
                }
            }
            when (tab) {
                InsightsTab.ANALYTICS -> AnalyticsTab(state, onDaysChange = viewModel::setDays)
                InsightsTab.DELIVERY -> DeliveryTab(state, nav)
                InsightsTab.ACTIVITY -> ActivityTab(state.activity, state.ruleNames, initialFilter = filter, loading = state.loading, nav = nav)
            }
        }
    }
}
