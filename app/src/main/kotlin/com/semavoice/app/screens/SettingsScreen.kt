package com.semavoice.app.screens

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.semavoice.app.BuildConfig
import com.semavoice.app.R
import com.semavoice.app.Routes
import com.semavoice.app.ui.Format
import com.semavoice.app.ui.KeyboardStatus
import com.semavoice.app.ui.SectionTitle
import com.semavoice.app.ui.SemaCard
import com.semavoice.app.ui.SemaTopBar
import com.semavoice.core.common.Language
import com.semavoice.core.database.CryptoBox
import com.semavoice.core.models.ModelKind
import com.semavoice.core.runtime.SemaGraph
import com.semavoice.core.settings.AppUiLanguage
import com.semavoice.core.settings.AudioRetention
import com.semavoice.core.settings.DictationMode
import com.semavoice.core.settings.KeyboardHeight
import com.semavoice.core.settings.SettingsRepository
import com.semavoice.core.settings.ThemeMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by SemaGraph.settings.settings.collectAsState(initial = null)
    val models by SemaGraph.modelManager.models.collectAsState()
    var confirmClear by remember { mutableStateOf(false) }
    val s = settings ?: return
    val repo: SettingsRepository = SemaGraph.settings
    fun set(block: suspend SettingsRepository.() -> Unit) = scope.launch { repo.block() }

    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        text = { Text(stringResource(R.string.settings_clear_all_confirm)) },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    SemaGraph.notes.deleteAll(); SemaGraph.history.deleteAll(); SemaGraph.dictionary.deleteAll()
                    CryptoBox().destroyKey(); repo.clearAll(); repo.setOnboardingComplete(true)
                }
                confirmClear = false
            }) { Text(stringResource(R.string.settings_clear_all)) }
        },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.action_cancel)) } },
    )

    Column(Modifier.fillMaxSize()) {
        SemaTopBar(stringResource(R.string.settings_title))
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            SectionTitle(stringResource(R.string.settings_language))
            SemaCard {
                Text(stringResource(R.string.settings_input_language), style = MaterialTheme.typography.labelLarge)
                LanguagePicker(s.inputLanguage, Language.inputCandidates) { l -> set { setInputLanguage(l); setAutoDetect(l == Language.AUTO) } }
                Text(stringResource(R.string.settings_output_language), style = MaterialTheme.typography.labelLarge)
                LanguagePicker(s.outputLanguage, Language.outputCandidates) { l -> set { setOutputLanguage(l) } }
                SwitchRow(stringResource(R.string.settings_auto_detect), s.autoDetectLanguage) { v -> set { setAutoDetect(v) } }
                SwitchRow(stringResource(R.string.settings_mixed), s.preferMixedLanguage) { v -> set { setPreferMixed(v) } }
            }

            SectionTitle(stringResource(R.string.settings_voice))
            SemaCard {
                SwitchRow(stringResource(R.string.settings_auto_stop), s.autoStopOnSilence) { v -> set { setAutoStopOnSilence(v) } }
                Text("${stringResource(R.string.settings_silence)}: ${s.silenceTimeoutMs / 1000f} s", style = MaterialTheme.typography.labelLarge)
                Slider(value = s.silenceTimeoutMs.toFloat(), onValueChange = { v -> set { setSilenceTimeoutMs(v.toInt()) } }, valueRange = 1000f..8000f, steps = 13)
                Text("${stringResource(R.string.settings_max_duration)}: ${s.maxRecordingSeconds} s", style = MaterialTheme.typography.labelLarge)
                Slider(value = s.maxRecordingSeconds.toFloat(), onValueChange = { v -> set { setMaxRecordingSeconds(v.toInt()) } }, valueRange = 15f..600f, steps = 38)
                Text("${stringResource(R.string.settings_mic_gain)}: ${"%.1f".format(s.micGain)}x", style = MaterialTheme.typography.labelLarge)
                Slider(value = s.micGain, onValueChange = { v -> set { setMicGain(v) } }, valueRange = 0.5f..3f, steps = 4)
                Text("${stringResource(R.string.settings_threads)}: ${if (s.threads == 0) stringResource(R.string.settings_auto) else s.threads}", style = MaterialTheme.typography.labelLarge)
                Slider(value = s.threads.toFloat(), onValueChange = { v -> set { setThreads(v.toInt()) } }, valueRange = 0f..8f, steps = 7)
                Text(stringResource(R.string.settings_speech_model), style = MaterialTheme.typography.labelLarge)
                ChipRow(models.filter { it.info.kind == ModelKind.SPEECH && it.isInstalled }.map { it.info.id to it.info.displayName }, s.preferredSpeechModelId) { id -> set { setPreferredSpeechModel(id) } }
            }

            SectionTitle(stringResource(R.string.settings_translation))
            SemaCard {
                SwitchRow(stringResource(R.string.settings_translation_enabled), s.translationEnabled) { v -> set { setTranslationEnabled(v) } }
                SwitchRow(stringResource(R.string.settings_preserve_original), s.preserveOriginal) { v -> set { setPreserveOriginal(v) } }
                Text(stringResource(R.string.settings_translation_model), style = MaterialTheme.typography.labelLarge)
                ChipRow(models.filter { it.info.kind == ModelKind.TRANSLATION && it.isInstalled }.map { it.info.id to it.info.displayName }, s.preferredTranslationModelId) { id -> set { setPreferredTranslationModel(id) } }
            }

            SectionTitle(stringResource(R.string.settings_keyboard))
            SemaCard {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = { KeyboardStatus.openEnableSettings(ctx) }) { Text(stringResource(R.string.settings_enable_keyboard)) }
                    TextButton(onClick = { KeyboardStatus.showPicker(ctx) }) { Text(stringResource(R.string.settings_choose_keyboard)) }
                }
                Text(stringResource(R.string.settings_theme), style = MaterialTheme.typography.labelLarge)
                ChipRow(listOf(ThemeMode.SYSTEM.name to stringResource(R.string.theme_system), ThemeMode.LIGHT.name to stringResource(R.string.theme_light), ThemeMode.DARK.name to stringResource(R.string.theme_dark)), s.theme.name) { v -> set { setTheme(ThemeMode.valueOf(v)) } }
                Text(stringResource(R.string.settings_height), style = MaterialTheme.typography.labelLarge)
                ChipRow(listOf(KeyboardHeight.COMPACT.name to stringResource(R.string.height_compact), KeyboardHeight.NORMAL.name to stringResource(R.string.height_normal), KeyboardHeight.TALL.name to stringResource(R.string.height_tall)), s.keyboardHeight.name) { v -> set { setKeyboardHeight(KeyboardHeight.valueOf(v)) } }
                Text(stringResource(R.string.settings_default_mode), style = MaterialTheme.typography.labelLarge)
                ChipRow(listOf(DictationMode.EXACT.name to stringResource(R.string.mode_exact), DictationMode.CLEAN.name to stringResource(R.string.mode_clean), DictationMode.TRANSLATE.name to stringResource(R.string.mode_translate)), s.dictationMode.name) { v -> set { setDictationMode(DictationMode.valueOf(v)) } }
                SwitchRow(stringResource(R.string.settings_haptic), s.hapticFeedback) { v -> set { setHaptic(v) } }
                SwitchRow(stringResource(R.string.settings_sound), s.soundFeedback) { v -> set { setSound(v) } }
                SwitchRow(stringResource(R.string.settings_auto_insert), s.autoInsert) { v -> set { setAutoInsert(v) } }
                SwitchRow(stringResource(R.string.settings_suggest), s.suggestCorrections) { v -> set { setSuggestCorrections(v) } }
                SwitchRow(stringResource(R.string.settings_show_translate), s.showTranslateToggle) { v -> set { setShowTranslateToggle(v) } }
            }

            SectionTitle(stringResource(R.string.settings_privacy))
            SemaCard {
                SwitchRow(stringResource(R.string.settings_save_history), s.saveHistory) { v -> set { setSaveHistory(v) } }
                Text(stringResource(R.string.settings_audio_retention), style = MaterialTheme.typography.labelLarge)
                ChipRow(listOf(AudioRetention.NEVER.name to stringResource(R.string.retention_never), AudioRetention.ASK.name to stringResource(R.string.retention_ask), AudioRetention.ALWAYS.name to stringResource(R.string.retention_always)), s.audioRetention.name) { v -> set { setAudioRetention(AudioRetention.valueOf(v)) } }
                SwitchRow(stringResource(R.string.settings_diagnostics), s.diagnosticsLogging) { v -> set { setDiagnosticsLogging(v) }; com.semavoice.core.common.SemaLog.enabled = v || BuildConfig.DEBUG }
                NavRow(stringResource(R.string.settings_privacy_policy)) { nav.navigate(Routes.PRIVACY) }
                NavRow(stringResource(R.string.settings_export)) {
                    scope.launch {
                        val notes = SemaGraph.notes.observeAll().first()
                        val history = SemaGraph.history.observeAll().first()
                        val dict = SemaGraph.dictionary.exportTsv()
                        val text = buildString {
                            append("# Sema Voice export\n\n## Notes\n")
                            notes.forEach { n -> append("### ${n.title}\n${n.transcript}\n"); n.translation?.let { append("> $it\n") }; append("\n") }
                            append("## History\n")
                            history.forEach { h -> append("- [${h.sourceLanguage.code}${h.targetLanguage?.let { "->" + it.code } ?: ""}] ${h.original}"); h.result?.let { append(" => $it") }; append("\n") }
                            append("\n## Dictionary (TSV)\n"); append(dict); append("\n")
                        }
                        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }, null))
                    }
                }
                NavRow(stringResource(R.string.settings_clear_all), danger = true) { confirmClear = true }
            }

            SectionTitle(stringResource(R.string.settings_models))
            SemaCard {
                NavRow(stringResource(R.string.settings_models_manage)) { nav.navigate(Routes.MODELS) }
                Text(stringResource(R.string.settings_storage_used, Format.bytes(SemaGraph.modelManager.storageUsedBytes())), style = MaterialTheme.typography.labelSmall)
                SwitchRow(stringResource(R.string.settings_wifi_only), s.wifiOnlyDownloads) { v -> set { setWifiOnlyDownloads(v) } }
                var sourceUrl by remember(s.modelSourceUrl) { mutableStateOf(s.modelSourceUrl) }
                androidx.compose.material3.OutlinedTextField(
                    value = sourceUrl, onValueChange = { sourceUrl = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.settings_model_source)) },
                    trailingIcon = { TextButton(onClick = { set { setModelSourceUrl(sourceUrl) } }) { Text(stringResource(R.string.action_done)) } },
                )
                NavRow(stringResource(R.string.settings_dictionary)) { nav.navigate(Routes.DICTIONARY) }
                NavRow(stringResource(R.string.settings_studio)) { nav.navigate(Routes.STUDIO) }
            }

            SectionTitle(stringResource(R.string.settings_general))
            SemaCard {
                Text(stringResource(R.string.settings_app_language), style = MaterialTheme.typography.labelLarge)
                ChipRow(listOf(AppUiLanguage.SYSTEM.name to stringResource(R.string.theme_system), AppUiLanguage.ENGLISH.name to "English", AppUiLanguage.AMHARIC.name to "አማርኛ"), s.uiLanguage.name) { v -> set { setUiLanguage(AppUiLanguage.valueOf(v)) } }
                Text(stringResource(R.string.settings_accessibility), style = MaterialTheme.typography.labelLarge)
                SwitchRow(stringResource(R.string.settings_large_mic), s.largeMicTarget) { v -> set { setLargeMicTarget(v) } }
                SwitchRow(stringResource(R.string.settings_high_contrast), s.highContrast) { v -> set { setHighContrast(v) } }
                NavRow(stringResource(R.string.settings_about)) { nav.navigate(Routes.ABOUT) }
                Text(stringResource(R.string.settings_version, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            androidx.compose.foundation.layout.Spacer(Modifier.padding(24.dp))
        }
    }
}

@Composable
fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun NavRow(label: String, danger: Boolean = false, onClick: () -> Unit) {
    Text(
        label, modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
fun ChipRow(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (id, label) -> FilterChip(selected = selected == id, onClick = { onSelect(id) }, label = { Text(label) }) }
    }
}
