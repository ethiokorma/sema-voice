package com.semavoice.app.screens

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.navigation.NavHostController
import com.semavoice.app.R
import com.semavoice.app.ui.AppErrors
import com.semavoice.app.ui.Brand
import com.semavoice.app.ui.ErrorBanner
import com.semavoice.app.ui.SectionTitle
import com.semavoice.app.ui.SemaCard
import com.semavoice.app.ui.SemaTopBar
import com.semavoice.app.ui.StatusPill
import com.semavoice.app.ui.VSpace
import com.semavoice.core.common.AppError
import com.semavoice.core.common.Language
import com.semavoice.core.common.Outcome
import com.semavoice.core.models.ModelCatalog
import com.semavoice.core.runtime.SemaGraph
import com.semavoice.core.runtime.VoiceStudioClient
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun PrivacyScreen(nav: NavHostController) {
    Column(Modifier.fillMaxSize()) {
        SemaTopBar(stringResource(R.string.privacy_title), onBack = { nav.popBackStack() })
        Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp)) {
            Text(PRIVACY_TEXT, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun AboutScreen(nav: NavHostController) {
    Column(Modifier.fillMaxSize()) {
        SemaTopBar(stringResource(R.string.about_title), onBack = { nav.popBackStack() })
        Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp)) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(R.string.tagline), color = MaterialTheme.colorScheme.onSurfaceVariant)
            VSpace()
            Text(stringResource(R.string.about_body))
            SectionTitle(stringResource(R.string.settings_licenses))
            LICENSES.forEach { (name, lic) ->
                SemaCard { Text(name, style = MaterialTheme.typography.titleMedium); Text(lic, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                VSpace(8)
            }
            SectionTitle(stringResource(R.string.models_title))
            ModelCatalog.builtIn.forEach { m ->
                Text("${m.displayName}: ${m.license}. ${m.attribution}", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(vertical = 4.dp))
            }
        }
    }
}

@Composable
fun VoiceStudioScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by SemaGraph.settings.settings.collectAsState(initial = null)
    var url by remember { mutableStateOf("") }
    var enabled by remember { mutableStateOf(false) }
    var consent by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var text by remember { mutableStateOf("") }
    var language by remember { mutableStateOf(Language.AMHARIC) }
    var error by remember { mutableStateOf<AppError?>(null) }
    var ttsReady by remember { mutableStateOf(false) }
    var availableVoices by remember { mutableStateOf<List<Language>>(emptyList()) }
    var lastFile by remember { mutableStateOf<File?>(null) }
    val s = settings ?: return
    LaunchedEffect(s.voiceStudioBaseUrl) { url = s.voiceStudioBaseUrl; enabled = s.voiceStudioEnabled; consent = s.voiceStudioConsentGiven }
    LaunchedEffect(Unit) { SemaGraph.tts.initialize().onSuccess { ttsReady = true; availableVoices = SemaGraph.tts.availableLanguages() } }

    Column(Modifier.fillMaxSize()) {
        SemaTopBar(stringResource(R.string.studio_title), onBack = { nav.popBackStack() })
        Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp)) {
            SectionTitle(stringResource(R.string.studio_tts))
            SemaCard {
                Text(stringResource(R.string.studio_tts_system), style = MaterialTheme.typography.titleMedium)
                StatusPill(if (ttsReady) availableVoices.joinToString { it.nativeName }.ifBlank { "—" } else "…", ttsReady && availableVoices.isNotEmpty())
                VSpace(8)
                LanguagePicker(language, Language.outputCandidates) { language = it }
                OutlinedTextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth(), minLines = 3, label = { Text(stringResource(R.string.translate_hint)) })
                if (ttsReady && language !in availableVoices) Text(stringResource(R.string.studio_no_voice, language.englishName), color = Brand.Record, style = MaterialTheme.typography.labelSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(onClick = { scope.launch { SemaGraph.tts.speak(text, language).onFailure { error = it } } }, enabled = text.isNotBlank() && ttsReady) { Text(stringResource(R.string.studio_speak)) }
                    OutlinedButton(onClick = {
                        scope.launch {
                            val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
                            val out = File(dir, "sema-tts-${System.currentTimeMillis()}.wav")
                            SemaGraph.tts.synthesizeToFile(text, language, out).onSuccess { lastFile = it }.onFailure { error = it }
                        }
                    }, enabled = text.isNotBlank() && ttsReady) { Text(stringResource(R.string.studio_save_audio)) }
                    lastFile?.let { f ->
                        TextButton(onClick = {
                            val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
                            ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "audio/wav"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }, null))
                        }) { Text(stringResource(R.string.studio_export)) }
                    }
                }
                TextButton(onClick = { ctx.startActivity(Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) { Text(stringResource(R.string.action_open_settings)) }
            }
            VSpace()
            SectionTitle(stringResource(R.string.studio_spoken_translation))
            SemaCard {
                Text(stringResource(R.string.studio_spoken_translation_desc), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = {
                    scope.launch {
                        val st = SemaGraph.settings.current()
                        when (val t = SemaGraph.pipeline.translateText(text, language, st.outputLanguage, st)) {
                            is Outcome.Failure -> error = t.error
                            is Outcome.Success -> SemaGraph.tts.speak(t.value, st.outputLanguage).onFailure { error = it }
                        }
                    }
                }, enabled = text.isNotBlank() && ttsReady) { Text("${stringResource(R.string.action_translate)} → ${s.outputLanguage.nativeName} → ${stringResource(R.string.studio_speak)}") }
            }
            VSpace()
            SectionTitle(stringResource(R.string.studio_tts_remote))
            SemaCard {
                Text(stringResource(R.string.studio_body), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text(stringResource(R.string.studio_url)) }, singleLine = true, modifier = Modifier.fillMaxWidth(), placeholder = { Text("http://192.168.1.10:8880") })
                SwitchRow(stringResource(R.string.studio_enable), enabled) { enabled = it }
                SwitchRow(stringResource(R.string.studio_consent), consent) { consent = it }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(onClick = { scope.launch { SemaGraph.settings.setVoiceStudio(enabled, url.trim(), consent) } }) { Text(stringResource(R.string.action_done)) }
                    OutlinedButton(onClick = {
                        scope.launch {
                            SemaGraph.settings.setVoiceStudio(enabled, url.trim(), consent)
                            when (val r = SemaGraph.voiceStudio.detect()) {
                                is Outcome.Failure -> status = AppErrors.message(ctx, r.error)
                                is Outcome.Success -> status = ctx.getString(R.string.studio_connected, r.value.kind.name)
                            }
                        }
                    }, enabled = enabled && consent && url.isNotBlank()) { Text(stringResource(R.string.studio_test)) }
                    if (enabled && consent) OutlinedButton(onClick = {
                        scope.launch {
                            val out = File(File(ctx.cacheDir, "exports").apply { mkdirs() }, "studio-${System.currentTimeMillis()}.wav")
                            when (val r = SemaGraph.voiceStudio.synthesize(text, language, null, out)) {
                                is Outcome.Failure -> error = r.error
                                is Outcome.Success -> { lastFile = r.value; status = ctx.getString(R.string.processed_remote) }
                            }
                        }
                    }, enabled = text.isNotBlank()) { Text(stringResource(R.string.studio_speak) + " (remote)") }
                }
                status?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = Brand.Secondary) }
                Text(stringResource(R.string.studio_cloning), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            error?.let { VSpace(8); ErrorBanner(it) }
            VSpace(40)
        }
    }
}

private val LICENSES = listOf(
    "whisper.cpp / ggml" to "MIT License. Copyright (c) 2023-2026 The ggml authors.",
    "OpenAI Whisper weights" to "MIT License. Copyright (c) 2022 OpenAI.",
    "ONNX Runtime" to "MIT License. Copyright (c) Microsoft Corporation.",
    "facebook/m2m100_418M" to "MIT License. Meta AI. ONNX export by Xenova.",
    "Helsinki-NLP OPUS-MT" to "Apache License 2.0 (models), CC-BY 4.0 (OPUS-MT project). ONNX export by Xenova.",
    "SentencePiece (format)" to "Apache License 2.0. Google. (Model-file format; tokenizer reimplemented in Kotlin.)",
    "AndroidX, Jetpack Compose, Kotlin, kotlinx.coroutines, kotlinx.serialization" to "Apache License 2.0.",
    "OkHttp" to "Apache License 2.0. Square, Inc.",
)

private const val PRIVACY_TEXT = """Sema Voice is built offline-first.

Microphone: audio is captured only while you tap or hold the microphone, in the keyboard, Voice Typing, or Voice Notes. A recording indicator is always visible. Voice notes use a foreground notification while recording.

Processing: speech recognition (whisper.cpp) and translation (ONNX Runtime) run entirely on this device. No audio or text is sent to any server by default. The app has no analytics, no advertising SDK and no account.

Storage: notes, history and dictionary entries are stored only on this device in an encrypted database (Android Keystore, AES-256-GCM). History is off by default. Note audio is kept only if you choose so and can be deleted separately from the transcript. "Clear all data" wipes everything including the encryption key.

Keyboard input: ordinary typing on the Sema keyboard is never stored, logged or transmitted. Voice input is disabled in password fields.

Network: the only network use is downloading AI models you choose, from the sources listed in the model manager, verified by SHA-256 checksums. The optional Voice Studio integration sends text or audio to a server address that you configure, only after you enable it and consent explicitly; it is off by default and clearly marked as remote processing.

Diagnostics: optional diagnostic logs never contain transcript or translation content.
"""
