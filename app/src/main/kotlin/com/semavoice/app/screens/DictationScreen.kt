package com.semavoice.app.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.semavoice.app.R
import com.semavoice.app.Routes
import com.semavoice.app.ui.AppErrors
import com.semavoice.app.ui.BigMicButton
import com.semavoice.app.ui.Brand
import com.semavoice.app.ui.Clipboard
import com.semavoice.app.ui.ErrorBanner
import com.semavoice.app.ui.Format
import com.semavoice.app.ui.SemaCard
import com.semavoice.app.ui.SemaTopBar
import com.semavoice.app.ui.VSpace
import com.semavoice.core.audio.AudioRecorder
import com.semavoice.core.audio.RecordingConfig
import com.semavoice.core.audio.RecordingState
import com.semavoice.core.common.AppError
import com.semavoice.core.common.Language
import com.semavoice.core.common.Outcome
import com.semavoice.core.database.HistoryItem
import com.semavoice.core.database.HistoryKind
import com.semavoice.core.database.HistoryRepository
import com.semavoice.core.database.NotesRepository
import com.semavoice.core.database.VoiceNote
import com.semavoice.core.runtime.DictationResult
import com.semavoice.core.runtime.PipelineStage
import com.semavoice.core.runtime.SemaGraph
import com.semavoice.core.settings.DictationMode
import com.semavoice.core.writing.Availability
import com.semavoice.core.writing.WritingAction
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DictationUi(
    val phase: Phase = Phase.Idle,
    val recording: RecordingState = RecordingState.Idle,
    val result: DictationResult? = null,
    val edited: String? = null,
    val error: AppError? = null,
    val stage: PipelineStage = PipelineStage.Idle,
    val progress: Int = 0,
    val mode: DictationMode = DictationMode.CLEAN,
    val saved: Boolean = false,
    val writingBusy: Boolean = false,
) {
    enum class Phase { Idle, Recording, Processing, Review }
    val text: String get() = edited ?: result?.displayText ?: ""
}

class DictationViewModel : ViewModel() {
    private val g = SemaGraph
    private val recorder: AudioRecorder = g.newRecorder()
    private val _ui = MutableStateFlow(DictationUi())
    val ui: StateFlow<DictationUi> = _ui.asStateFlow()
    private var job: Job? = null

    init {
        viewModelScope.launch { g.pipeline.stage.collectLatest { st -> _ui.update { it.copy(stage = st) } } }
        viewModelScope.launch { g.speech.progress.collectLatest { p -> _ui.update { it.copy(progress = p) } } }
        viewModelScope.launch { g.ctc.progress.collectLatest { p -> _ui.update { it.copy(progress = p) } } }
        viewModelScope.launch { g.settings.settings.collectLatest { s -> if (_ui.value.phase == DictationUi.Phase.Idle) _ui.update { it.copy(mode = s.dictationMode) } } }
    }

    fun hasPermission() = recorder.hasPermission()

    fun setMode(m: DictationMode) { _ui.update { it.copy(mode = m) } }

    fun toggleMic() {
        when (_ui.value.phase) {
            DictationUi.Phase.Recording -> stop()
            DictationUi.Phase.Processing -> cancel()
            else -> start()
        }
    }

    fun start() {
        job?.cancel()
        job = viewModelScope.launch {
            val s = g.settings.current()
            _ui.update { it.copy(phase = DictationUi.Phase.Recording, error = null, result = null, edited = null, saved = false) }
            val cfg = RecordingConfig(maxDurationMs = s.maxRecordingSeconds * 1000L, silenceTimeoutMs = s.silenceTimeoutMs.toLong(), autoStopOnSilence = s.autoStopOnSilence, gain = s.micGain)
            when (val r = recorder.start(cfg)) {
                is Outcome.Failure -> { _ui.update { it.copy(phase = DictationUi.Phase.Idle, error = r.error) }; return@launch }
                is Outcome.Success -> Unit
            }
            recorder.state.collectLatest { rs ->
                _ui.update { it.copy(recording = rs) }
                if (rs is RecordingState.Finished && _ui.value.phase == DictationUi.Phase.Recording) recorder.takeNaturalResult()?.let { process(it) }
                if (rs is RecordingState.Failed && _ui.value.phase == DictationUi.Phase.Recording) _ui.update { it.copy(phase = DictationUi.Phase.Idle, error = rs.error) }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = viewModelScope.launch { _ui.update { it.copy(phase = DictationUi.Phase.Processing) }; process(recorder.stop()) }
    }

    fun pauseResume() { if (recorder.state.value is RecordingState.Recording) recorder.pause() else recorder.resume() }

    private suspend fun process(audio: Outcome<com.semavoice.core.common.PcmAudio>) {
        when (audio) {
            is Outcome.Failure -> _ui.update { it.copy(phase = DictationUi.Phase.Idle, error = audio.error, recording = RecordingState.Idle) }
            is Outcome.Success -> {
                _ui.update { it.copy(phase = DictationUi.Phase.Processing, recording = RecordingState.Idle) }
                val s = g.settings.current()
                when (val r = g.pipeline.process(audio.value, s, _ui.value.mode)) {
                    is Outcome.Failure -> _ui.update { it.copy(phase = DictationUi.Phase.Idle, error = r.error) }
                    is Outcome.Success -> _ui.update { it.copy(phase = DictationUi.Phase.Review, result = r.value) }
                }
            }
        }
    }

    fun cancel() {
        job?.cancel()
        g.pipeline.cancelRecognition(); g.translator.cancel()
        viewModelScope.launch { recorder.cancel(); recorder.reset(); g.pipeline.reset(); _ui.update { it.copy(phase = DictationUi.Phase.Idle, recording = RecordingState.Idle) } }
    }

    fun discard() { g.pipeline.reset(); _ui.update { DictationUi(mode = it.mode) } }
    fun edit(text: String) { _ui.update { it.copy(edited = text) } }
    fun applySuggestion(from: String, to: String) { edit(_ui.value.text.replaceFirst(from, to)) }

    fun translate() = viewModelScope.launch {
        val u = _ui.value; val r = u.result ?: return@launch
        val s = g.settings.current()
        val from = r.transcript.language.takeIf { it != Language.AUTO } ?: s.inputLanguage
        _ui.update { it.copy(phase = DictationUi.Phase.Processing) }
        when (val t = g.pipeline.translateText(u.text, from, s.outputLanguage, s)) {
            is Outcome.Failure -> _ui.update { it.copy(phase = DictationUi.Phase.Review, result = r.copy(translationError = t.error)) }
            is Outcome.Success -> _ui.update { it.copy(phase = DictationUi.Phase.Review, result = r.copy(translation = t.value, translationLanguage = s.outputLanguage, translationError = null)) }
        }
    }

    fun applyWriting(action: WritingAction) = viewModelScope.launch {
        val u = _ui.value; val r = u.result ?: return@launch
        _ui.update { it.copy(writingBusy = true) }
        val lang = r.transcript.language.takeIf { it != Language.AUTO } ?: g.settings.current().inputLanguage
        g.pipeline.applyWriting(action, u.text, lang).onSuccess { res -> edit(res.text) }
        _ui.update { it.copy(writingBusy = false) }
    }

    fun saveNote() = viewModelScope.launch {
        val u = _ui.value; val r = u.result ?: return@launch
        val now = System.currentTimeMillis()
        val lang = r.transcript.language.takeIf { it != Language.AUTO } ?: g.settings.current().inputLanguage
        g.notes.save(VoiceNote(NotesRepository.newId(), u.text.take(40), now, now, r.transcript.audioMs, lang, r.translationLanguage, u.text, r.translation, false, null))
        _ui.update { it.copy(saved = true) }
    }

    fun saveHistory() = viewModelScope.launch {
        val u = _ui.value; val r = u.result ?: return@launch
        val lang = r.transcript.language.takeIf { it != Language.AUTO } ?: g.settings.current().inputLanguage
        g.history.save(HistoryItem(HistoryRepository.newId(), System.currentTimeMillis(), if (r.translation != null) HistoryKind.TRANSLATION else HistoryKind.TRANSCRIPTION, lang, r.translationLanguage, u.text, r.translation, false, null))
        _ui.update { it.copy(saved = true) }
    }

    fun speak(text: String, language: Language) = viewModelScope.launch {
        g.tts.initialize().onSuccess { g.tts.speak(text, language).onFailure { e -> _ui.update { it.copy(error = e) } } }
    }

    override fun onCleared() { recorder.release(); super.onCleared() }
}

@Composable
fun DictationScreen(nav: NavHostController, vm: DictationViewModel = viewModel()) {
    val ui by vm.ui.collectAsState()
    val settings by SemaGraph.settings.settings.collectAsState(initial = null)
    val ctx = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) vm.start() }
    val s = settings ?: return

    Column(Modifier.fillMaxSize()) {
        SemaTopBar(stringResource(R.string.dictation_title), onBack = { nav.popBackStack() })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(DictationMode.EXACT to R.string.mode_exact, DictationMode.CLEAN to R.string.mode_clean, DictationMode.TRANSLATE to R.string.mode_translate).forEach { (m, res) ->
                    FilterChip(selected = ui.mode == m, onClick = { vm.setMode(m) }, label = { Text(stringResource(res)) }, enabled = ui.phase == DictationUi.Phase.Idle || ui.phase == DictationUi.Phase.Review)
                }
            }
            VSpace(20)
            BigMicButton(
                recording = ui.phase == DictationUi.Phase.Recording, processing = ui.phase == DictationUi.Phase.Processing,
                enabled = true, large = s.largeMicTarget,
            ) { if (vm.hasPermission()) vm.toggleMic() else permission.launch(Manifest.permission.RECORD_AUDIO) }
            VSpace(12)
            when (ui.phase) {
                DictationUi.Phase.Idle -> Text(stringResource(R.string.dictation_hint), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 12.dp))
                DictationUi.Phase.Recording -> {
                    val rec = ui.recording
                    val level = (rec as? RecordingState.Recording)?.level ?: 0f
                    val elapsed = (rec as? RecordingState.Recording)?.elapsedMs ?: (rec as? RecordingState.Paused)?.elapsedMs ?: 0L
                    Text("${stringResource(R.string.dictation_recording)} ${Format.duration(elapsed)}", color = Brand.Record)
                    LinearProgressIndicator(progress = { level }, modifier = Modifier.fillMaxWidth(0.6f).padding(top = 8.dp), color = Brand.Primary)
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { vm.pauseResume() }) { Text(stringResource(if (rec is RecordingState.Paused) R.string.action_resume else R.string.action_pause)) }
                        TextButton(onClick = { vm.cancel() }) { Text(stringResource(R.string.action_cancel)) }
                    }
                }
                DictationUi.Phase.Processing -> {
                    val label = when (val st = ui.stage) {
                        is PipelineStage.LoadingModel -> st.name
                        is PipelineStage.Translating -> stringResource(R.string.dictation_translating)
                        else -> stringResource(R.string.dictation_processing) + if (ui.progress in 1..99) " ${ui.progress}%" else ""
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.padding(end = 8.dp)); Text(label) }
                    TextButton(onClick = { vm.cancel() }) { Text(stringResource(R.string.action_cancel)) }
                }
                DictationUi.Phase.Review -> ReviewPanel(ui, vm, nav, s.inputLanguage)
            }
            ui.error?.let { e ->
                VSpace(12)
                val action = when (e) {
                    is AppError.ModelNotInstalled, is AppError.UnsupportedLanguage, is AppError.ModelCorrupt -> stringResource(R.string.settings_models) to { nav.navigate(Routes.MODELS) }
                    is AppError.MicrophonePermissionDenied -> stringResource(R.string.action_grant) to { permission.launch(Manifest.permission.RECORD_AUDIO) }
                    else -> stringResource(R.string.action_retry) to { vm.discard() }
                }
                ErrorBanner(e, action.first, action.second)
            }
            VSpace(40)
        }
    }
}

@Composable
private fun ReviewPanel(ui: DictationUi, vm: DictationViewModel, nav: NavHostController, defaultLang: Language) {
    val ctx = LocalContext.current
    val r = ui.result ?: return
    val translation: String? = r.translation
    val lang = r.transcript.language.takeIf { it != Language.AUTO } ?: defaultLang
    SemaCard {
        Text(stringResource(R.string.dictation_result), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(value = ui.text, onValueChange = { vm.edit(it) }, modifier = Modifier.fillMaxWidth(), textStyle = MaterialTheme.typography.bodyLarge, minLines = 2)
        Text(
            stringResource(R.string.inference_stats, r.transcript.inferenceMs, r.transcript.realTimeFactor, r.transcript.modelId) + " · " + stringResource(R.string.processed_locally),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (r.mixedLanguage) Text(stringResource(R.string.mixed_language), style = MaterialTheme.typography.labelSmall, color = Brand.Secondary)
        if (r.appliedCorrections.isNotEmpty()) Text(stringResource(R.string.corrections_applied, r.appliedCorrections.size), style = MaterialTheme.typography.labelSmall, color = Brand.Secondary)
        r.suggestedCorrections.take(3).forEach { sg ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.suggestion_apply, sg.to), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { vm.applySuggestion(sg.from, sg.to) }) { Text(stringResource(R.string.action_apply)) }
            }
        }
        if (translation != null) {
            VSpace(8)
            Text(stringResource(R.string.dictation_translation) + " · " + (r.translationLanguage?.nativeName ?: ""), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(translation, style = MaterialTheme.typography.bodyLarge, color = Brand.Primary)
        }
        r.translationError?.let { Text(AppErrors.message(ctx, it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall) }
        VSpace(8)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = { Clipboard.copy(ctx, ui.text) }) { Text(stringResource(R.string.action_copy)) }
            OutlinedButton(onClick = { Clipboard.share(ctx, ui.text) }) { Text(stringResource(R.string.action_share)) }
            if (translation == null) OutlinedButton(onClick = { vm.translate() }) { Text(stringResource(R.string.action_translate)) }
            else OutlinedButton(onClick = { Clipboard.copy(ctx, translation) }) { Text(stringResource(R.string.translate_copy_translation)) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(onClick = { vm.saveNote() }, enabled = !ui.saved) { Text(stringResource(if (ui.saved) R.string.saved else R.string.action_save_note)) }
            TextButton(onClick = { vm.saveHistory() }, enabled = !ui.saved) { Text(stringResource(R.string.action_save_history)) }
            TextButton(onClick = { vm.speak(ui.text, lang) }) { Text(stringResource(R.string.action_listen)) }
            TextButton(onClick = { vm.discard() }) { Text(stringResource(R.string.action_discard)) }
        }
    }
    VSpace(12)
    Text(stringResource(R.string.writing_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth())
    val actions = listOf(
        WritingAction.IMPROVE_PUNCTUATION to R.string.writing_action_punctuation, WritingAction.CORRECT_SPELLING to R.string.writing_action_spelling,
        WritingAction.REMOVE_REPETITION to R.string.writing_action_repetition, WritingAction.CLEAN_DISFLUENCIES to R.string.writing_action_disfluencies,
        WritingAction.MAKE_SHORTER to R.string.writing_action_shorter, WritingAction.MAKE_PROFESSIONAL to R.string.writing_action_professional,
        WritingAction.MAKE_FRIENDLY to R.string.writing_action_friendly, WritingAction.SIMPLIFY to R.string.writing_action_simplify,
    )
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        actions.forEach { (a, res) ->
            val avail = SemaGraph.writing.availability(a, lang)
            AssistChip(onClick = { vm.applyWriting(a) }, enabled = avail != Availability.UNAVAILABLE && !ui.writingBusy, label = { Text(stringResource(res) + if (avail == Availability.UNAVAILABLE) " · " + stringResource(R.string.writing_unavailable) else "") })
        }
    }
}
