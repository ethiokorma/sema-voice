package com.semavoice.app.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.semavoice.app.R
import com.semavoice.app.ui.BigMicButton
import com.semavoice.app.ui.Brand
import com.semavoice.app.ui.ErrorBanner
import com.semavoice.app.ui.Format
import com.semavoice.app.ui.KeyboardStatus
import com.semavoice.app.ui.SemaCard
import com.semavoice.app.ui.StatusPill
import com.semavoice.app.ui.VSpace
import com.semavoice.core.common.Language
import com.semavoice.core.models.InstallState
import com.semavoice.core.models.ModelCatalog
import com.semavoice.core.models.ModelKind
import com.semavoice.core.runtime.SemaGraph
import kotlinx.coroutines.launch

/**
 * Seven-step first run (section 20). "Success" is only shown when a speech model is
 * installed, the keyboard is enabled and the microphone permission is granted.
 */
@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var step by remember { mutableIntStateOf(0) }
    val settings by SemaGraph.settings.settings.collectAsState(initial = null)
    val models by SemaGraph.modelManager.models.collectAsState()
    var kbEnabled by remember { mutableStateOf(KeyboardStatus.isEnabled(ctx)) }
    var micGranted by remember { mutableStateOf(SemaGraph.newRecorder().hasPermission()) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { micGranted = it }
    val lifecycle = LocalLifecycleOwner.current
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) { kbEnabled = KeyboardStatus.isEnabled(ctx); micGranted = SemaGraph.newRecorder().hasPermission() } }
        lifecycle.lifecycle.addObserver(obs)
        onDispose { lifecycle.lifecycle.removeObserver(obs) }
    }
    val s = settings ?: return
    val speechInstalled = models.any { it.info.kind == ModelKind.SPEECH && it.isUsable }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.Center) {
        LinearProgressIndicator(progress = { (step + 1) / 7f }, modifier = Modifier.fillMaxWidth())
        VSpace(24)
        when (step) {
            0 -> {
                Text(stringResource(R.string.onb_welcome_title), style = MaterialTheme.typography.displaySmall)
                VSpace(8)
                Text(stringResource(R.string.onb_welcome_body), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            1 -> {
                Text(stringResource(R.string.onb_language_title), style = MaterialTheme.typography.headlineMedium)
                Text(stringResource(R.string.onb_language_body), color = MaterialTheme.colorScheme.onSurfaceVariant)
                VSpace(16)
                Text(stringResource(R.string.settings_input_language), style = MaterialTheme.typography.labelLarge)
                LanguagePicker(s.inputLanguage, Language.inputCandidates) { scope.launch { SemaGraph.settings.setInputLanguage(it); SemaGraph.settings.setAutoDetect(it == Language.AUTO) } }
                VSpace(8)
                Text(stringResource(R.string.settings_output_language), style = MaterialTheme.typography.labelLarge)
                LanguagePicker(s.outputLanguage, Language.outputCandidates) { scope.launch { SemaGraph.settings.setOutputLanguage(it) } }
            }
            2 -> {
                Text(stringResource(R.string.onb_model_title), style = MaterialTheme.typography.headlineMedium)
                Text(stringResource(R.string.onb_model_body), color = MaterialTheme.colorScheme.onSurfaceVariant)
                VSpace(16)
                val wantsAmharic = s.inputLanguage == Language.AMHARIC || s.inputLanguage == Language.AUTO
                val candidates = models.filter { it.info.kind == ModelKind.SPEECH && (it.info.recommended || it.info.id == ModelCatalog.whisperBaseQ5.id) }
                    .sortedByDescending { if (wantsAmharic) Language.AMHARIC in it.info.languages else it.info.recommended }
                if (wantsAmharic && candidates.none { Language.AMHARIC in it.info.languages }) {
                    Text(stringResource(R.string.models_amharic_note), color = Brand.Record, style = MaterialTheme.typography.bodyMedium)
                    VSpace(8)
                }
                candidates.take(2).forEach { m ->
                    SemaCard {
                        Text(m.info.displayName, style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.onb_model_size, m.info.languages.joinToString { it.nativeName }, Format.bytes(m.info.totalBytes)), style = MaterialTheme.typography.labelSmall)
                        when (val st = m.install) {
                            is InstallState.Installed -> StatusPill(stringResource(R.string.onb_step_done), true)
                            is InstallState.Downloading -> LinearProgressIndicator(progress = { st.progress }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                            is InstallState.Failed -> ErrorBanner(st.error)
                            else -> Button(onClick = { SemaGraph.modelManager.download(m.info.id, s.wifiOnlyDownloads) }) { Text(stringResource(R.string.models_download)) }
                        }
                    }
                    VSpace(8)
                }
            }
            3 -> {
                Text(stringResource(R.string.onb_keyboard_title), style = MaterialTheme.typography.headlineMedium)
                Text(stringResource(R.string.onb_keyboard_body), color = MaterialTheme.colorScheme.onSurfaceVariant)
                VSpace(16)
                StatusPill(stringResource(if (kbEnabled) R.string.home_keyboard_enabled else R.string.home_keyboard_disabled), kbEnabled)
                VSpace(8)
                if (!kbEnabled) Button(onClick = { KeyboardStatus.openEnableSettings(ctx) }) { Text(stringResource(R.string.settings_enable_keyboard)) }
                else OutlinedButton(onClick = { KeyboardStatus.showPicker(ctx) }) { Text(stringResource(R.string.settings_choose_keyboard)) }
            }
            4 -> {
                Text(stringResource(R.string.onb_mic_title), style = MaterialTheme.typography.headlineMedium)
                Text(stringResource(R.string.onb_mic_body), color = MaterialTheme.colorScheme.onSurfaceVariant)
                VSpace(16)
                StatusPill(stringResource(if (micGranted) R.string.onb_step_done else R.string.onb_step_pending), micGranted)
                VSpace(8)
                if (!micGranted) Button(onClick = { permission.launch(Manifest.permission.RECORD_AUDIO) }) { Text(stringResource(R.string.action_grant)) }
            }
            5 -> {
                Text(stringResource(R.string.onb_try_title), style = MaterialTheme.typography.headlineMedium)
                Text(stringResource(R.string.onb_try_body), color = MaterialTheme.colorScheme.onSurfaceVariant)
                VSpace(16)
                TryDictation(enabled = speechInstalled && micGranted)
            }
            else -> {
                val complete = speechInstalled && kbEnabled && micGranted
                Text(stringResource(if (complete) R.string.onb_done_title else R.string.onb_incomplete), style = MaterialTheme.typography.headlineMedium)
                VSpace(8)
                if (complete) Text(stringResource(R.string.onb_done_body), color = MaterialTheme.colorScheme.onSurfaceVariant)
                VSpace(12)
                StatusPill(stringResource(R.string.onb_model_title), speechInstalled)
                StatusPill(stringResource(R.string.onb_keyboard_title), kbEnabled)
                StatusPill(stringResource(R.string.onb_mic_title), micGranted)
            }
        }
        VSpace(32)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            if (step > 0) TextButton(onClick = { step-- }) { Text(stringResource(R.string.action_back)) } else Spacer(Modifier)
            if (step < 6) Button(onClick = { step++ }) { Text(stringResource(R.string.action_next)) }
            else Button(onClick = { scope.launch { SemaGraph.settings.setOnboardingComplete(true); onDone() } }) { Text(stringResource(R.string.action_done)) }
        }
        if (step in 2..5) TextButton(onClick = { step++ }, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.action_skip)) }
    }
}

@Composable
private fun TryDictation(enabled: Boolean, vm: DictationViewModel = viewModel()) {
    val ui by vm.ui.collectAsState()
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        BigMicButton(recording = ui.phase == DictationUi.Phase.Recording, processing = ui.phase == DictationUi.Phase.Processing, enabled = enabled, large = false) { vm.toggleMic() }
        VSpace(12)
        when (ui.phase) {
            DictationUi.Phase.Recording -> Text(stringResource(R.string.dictation_recording), color = Brand.Record)
            DictationUi.Phase.Processing -> Text(stringResource(R.string.dictation_processing))
            DictationUi.Phase.Review -> Text(ui.text, style = MaterialTheme.typography.bodyLarge)
            else -> if (!enabled) Text(stringResource(R.string.onb_incomplete), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        ui.error?.let { ErrorBanner(it) }
    }
}
