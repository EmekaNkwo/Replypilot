package com.replypilot.features.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.replypilot.core.design.Notice
import com.replypilot.core.design.NoticeTone
import com.replypilot.core.design.Spacing
import com.replypilot.features.settings.OnResume

@Composable
fun OnboardingScreen(onFinished: () -> Unit) {
    val viewModel: OnboardingViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    OnResume { viewModel.refresh() }
    val steps = OnboardingStep.entries
    val isLast = state.step == steps.last()

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Column(Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Step ${state.step.ordinal + 1} of ${steps.size}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (!isLast) TextButton(onClick = { viewModel.finish(onFinished) }) { Text("Skip setup") }
            }
            LinearProgressIndicator(progress = { (state.step.ordinal + 1f) / steps.size }, modifier = Modifier.fillMaxWidth())
            Text(state.step.title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = Spacing.md))
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            when (state.step) {
                OnboardingStep.WELCOME -> WelcomeStep()
                OnboardingStep.CHANNELS -> ChannelsStep(state, viewModel::setChannel)
                OnboardingStep.PERMISSIONS -> PermissionsStep(state, viewModel::refresh)
                OnboardingStep.BACKGROUND -> BackgroundStep()
                OnboardingStep.TRY_IT -> TryItStep(state, viewModel::loadDemo, viewModel::scheduleTest)
                OnboardingStep.FIRST_RULE -> FirstRuleStep(state, viewModel::setRuleEnabled, viewModel::createAwayRule)
                OnboardingStep.PRIVACY -> PrivacyStep()
            }
            state.error?.let { Notice(it, tone = NoticeTone.ERROR) }
            Spacer(Modifier.padding(Spacing.sm))
        }
        Row(
            Modifier.fillMaxWidth().padding(Spacing.lg),
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            if (state.step.ordinal > 0) OutlinedButton(onClick = viewModel::back, modifier = Modifier.weight(1f)) { Text("Back") }
            Button(
                onClick = { if (isLast) viewModel.finish(onFinished) else viewModel.next() },
                modifier = Modifier.weight(1f),
                enabled = !state.busy,
            ) { Text(if (isLast) "Finish" else "Next") }
        }
    }
}
