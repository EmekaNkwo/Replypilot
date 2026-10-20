package com.replypilot

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.compose.rememberNavController
import com.replypilot.core.datastore.AppPreferences
import com.replypilot.core.datastore.AppPreferencesRepository
import com.replypilot.core.design.ReplyPilotTheme
import com.replypilot.navigation.AppNavHost
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

@HiltViewModel
class MainViewModel @Inject constructor(preferences: AppPreferencesRepository) : ViewModel() {
    /** Null until preferences load, so the first frame never flashes the wrong start screen. */
    val preferences: StateFlow<AppPreferences?> = preferences.preferences
        .map<AppPreferences, AppPreferences?> { it }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            // Created before preferences finish loading, so a screen Android rebuilt gets its saved back stack back
            // (instead of starting again on Home).
            val controller = rememberNavController()
            val viewModel: MainViewModel = hiltViewModel()
            val prefs by viewModel.preferences.collectAsStateWithLifecycle()
            val loaded = prefs
            ReplyPilotTheme(mode = loaded?.themeMode ?: com.replypilot.core.datastore.ThemeMode.SYSTEM) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    if (loaded != null) AppNavHost(controller, startOnboarding = !loaded.onboardingComplete)
                }
            }
        }
    }
}
