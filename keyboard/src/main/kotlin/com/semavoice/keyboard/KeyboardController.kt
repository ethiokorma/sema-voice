package com.semavoice.keyboard

import com.semavoice.core.audio.AudioRecorder
import com.semavoice.core.audio.RecordingConfig
import com.semavoice.core.audio.RecordingState
import com.semavoice.core.common.AppError
import com.semavoice.core.common.Language
import com.semavoice.core.common.Outcome
import com.semavoice.core.common.SemaLog
import com.semavoice.core.runtime.DictationResult
import com.semavoice.core.runtime.PipelineStage
import com.semavoice.core.runtime.SemaGraph
import com.semavoice.core.settings.DictationMode
import com.semavoice.core.settings.SemaSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Everything the keyboard UI renders. */
data class KeyboardUiState(
    val settings: SemaSettings = SemaSettings(),
    val phase: Phase = Phase.Idle,
    val recording: RecordingState = RecordingState.Idle,
    val result: DictationResult? = null,
    val editedText: String? = null,
    val error: AppError? = null,
    val fieldSecure: Boolean = false,
    val fieldPresent: Boolean = true,
    val fieldNumeric: Boolean = false,
    val showTranslation: Boolean = false,
    val shift: Boolean = false,
    val symbols: Boolean = false,
    /** Phonetic Ge'ez composition on the QWERTY keys (on by default for Ge'ez-script input languages). */
    val geezInput: Boolean = true,
    val modelReady: Boolean = false,
    val loadingLabel: String? = null,
    val transcribeProgress: Int = 0,
) {
    sealed class Phase {
        data object Idle : Phase()
        data object Recording : Phase()
        data object Processing : Phase()
        data object Review : Phase()
        data object Error : Phase()
    }
    val effectiveText: String get() = editedText ?: result?.displayText ?: ""
    val micEnabled: Boolean get() = !fieldSecure && fieldPresent
}

/**
 * Drives the keyboard: recording, inference, review and insertion. UI-framework agnostic
 * so the same controller could back a Compose IME view, a test, or a future desktop shell.
 */
class KeyboardController(
    private val graph: SemaGraph,
    private val sinkProvider: () -> TextSink?,
    private val onRequestPermission: () -> Unit,
    private val onOpenApp: (String) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + graph.dispatchers.main)
    private val _state = MutableStateFlow(KeyboardUiState())
    val state: StateFlow<KeyboardUiState> = _state.asStateFlow()
    private var recorder: AudioRecorder? = null
    private var job: Job? = null

    init {
        scope.launch { graph.settings.settings.collectLatest { s -> _state.update { it.copy(settings = s, showTranslation = it.showTranslation || s.dictationMode == DictationMode.TRANSLATE) } } }
        scope.launch { graph.pipeline.stage.collectLatest { st ->
            when (st) {
                is PipelineStage.LoadingModel -> _state.update { it.copy(loadingLabel = st.name) }
                is PipelineStage.Transcribing -> _state.update { it.copy(loadingLabel = null, transcribeProgress = st.progress) }
                else -> _state.update { it.copy(loadingLabel = null) }
            }
        } }
        scope.launch { graph.speech.progress.collectLatest { p -> _state.update { it.copy(transcribeProgress = p) } } }
        scope.launch { graph.modelManager.models.collectLatest { _state.update { it.copy(modelReady = graph.pipeline.hasAnySpeechModel()) } } }
    }

    fun onFieldChanged(inputType: Int) {
        _state.update {
            it.copy(
                fieldSecure = FieldRules.isSecure(inputType),
                fieldPresent = FieldRules.hasEditableField(inputType),
                fieldNumeric = FieldRules.isNumeric(inputType),
                symbols = FieldRules.isNumeric(inputType),
            )
        }
    }

    fun toggleMic() {
        when (_state.value.phase) {
            KeyboardUiState.Phase.Recording -> stopAndProcess()
            KeyboardUiState.Phase.Processing -> cancelProcessing()
            else -> startRecording()
        }
    }

    fun startRecording() {
        val s = _state.value
        if (s.fieldSecure) { fail(AppError.SecureField()); return }
        if (!s.fieldPresent) { fail(AppError.NoActiveInput()); return }
        if (!graph.pipeline.hasAnySpeechModel()) { fail(AppError.ModelNotInstalled("speech")); return }
        val rec = recorder ?: graph.newRecorder().also { recorder = it }
        if (!rec.hasPermission()) { fail(AppError.MicrophonePermissionDenied()); onRequestPermission(); return }
        job?.cancel()
        job = scope.launch {
            _state.update { it.copy(phase = KeyboardUiState.Phase.Recording, error = null, result = null, editedText = null) }
            val cfg = RecordingConfig(
                maxDurationMs = s.settings.maxRecordingSeconds * 1000L,
                silenceTimeoutMs = s.settings.silenceTimeoutMs.toLong(),
                autoStopOnSilence = s.settings.autoStopOnSilence,
                gain = s.settings.micGain,
            )
            when (val r = rec.start(cfg)) {
                is Outcome.Failure -> { fail(r.error); return@launch }
                is Outcome.Success -> Unit
            }
            // Mirror recorder state; when it finishes on its own (silence/max), process.
            rec.state.collectLatest { rs ->
                _state.update { it.copy(recording = rs) }
                if (rs is RecordingState.Finished && _state.value.phase == KeyboardUiState.Phase.Recording) {
                    rec.takeNaturalResult()?.let { process(it) }
                }
                if (rs is RecordingState.Failed && _state.value.phase == KeyboardUiState.Phase.Recording) fail(rs.error)
            }
        }
    }

    fun stopAndProcess() {
        val rec = recorder ?: return
        job?.cancel()
        job = scope.launch {
            _state.update { it.copy(phase = KeyboardUiState.Phase.Processing) }
            process(rec.stop())
        }
    }

    private suspend fun process(audio: Outcome<com.semavoice.core.common.PcmAudio>) {
        when (audio) {
            is Outcome.Failure -> fail(audio.error)
            is Outcome.Success -> {
                _state.update { it.copy(phase = KeyboardUiState.Phase.Processing, recording = RecordingState.Idle) }
                val s = _state.value
                val mode = if (s.showTranslation && s.settings.translationEnabled) DictationMode.TRANSLATE else s.settings.dictationMode.takeIf { it != DictationMode.TRANSLATE } ?: DictationMode.CLEAN
                when (val r = graph.pipeline.process(audio.value, s.settings, mode)) {
                    is Outcome.Failure -> fail(r.error)
                    is Outcome.Success -> {
                        _state.update { it.copy(phase = KeyboardUiState.Phase.Review, result = r.value, editedText = null) }
                        if (s.settings.autoInsert) insert(useTranslation = s.showTranslation && r.value.translation != null)
                    }
                }
            }
        }
    }

    fun cancelRecording() {
        val rec = recorder ?: return
        job?.cancel()
        scope.launch { rec.cancel(); rec.reset(); _state.update { it.copy(phase = KeyboardUiState.Phase.Idle, recording = RecordingState.Idle) } }
    }

    fun cancelProcessing() {
        graph.speech.cancel(); graph.translator.cancel()
        job?.cancel()
        graph.pipeline.reset()
        _state.update { it.copy(phase = KeyboardUiState.Phase.Idle, loadingLabel = null) }
    }

    fun pauseOrResume() {
        val rec = recorder ?: return
        if (rec.state.value is RecordingState.Recording) rec.pause() else rec.resume()
    }

    fun setEditedText(text: String) { _state.update { it.copy(editedText = text) } }

    fun applySuggestion(from: String, to: String) {
        val cur = _state.value.effectiveText
        setEditedText(cur.replaceFirst(from, to))
    }

    fun toggleTranslation() { _state.update { it.copy(showTranslation = !it.showTranslation) } }

    fun insert(useTranslation: Boolean = false) {
        val s = _state.value
        val text = if (useTranslation) s.result?.translation ?: s.effectiveText else s.effectiveText
        if (text.isBlank()) return
        val sink = sinkProvider() ?: run { fail(AppError.NoActiveInput()); return }
        if (s.fieldSecure) { fail(AppError.SecureField()); return }
        sink.commit(FieldRules.joinWithContext(sink.textBeforeCursor(2), text))
        discard()
    }

    fun discard() {
        graph.pipeline.reset()
        _state.update { it.copy(phase = KeyboardUiState.Phase.Idle, result = null, editedText = null, error = null, recording = RecordingState.Idle) }
    }

    fun retry() { discard(); startRecording() }

    // ---- plain typing -----------------------------------------------------------------

    private val geez = GeezComposer()

    private fun geezActive(s: KeyboardUiState): Boolean =
        s.geezInput && !s.symbols && s.settings.inputLanguage.script == com.semavoice.core.common.Script.GEEZ

    fun typeKey(label: String) {
        val sink = sinkProvider() ?: return
        val s = _state.value
        val text = if (s.shift && !s.symbols) label.uppercase() else label
        if (geezActive(s) && text.length == 1) {
            val edit = geez.type(text[0])
            if (edit.deleteBefore > 0) sink.deleteBackward(edit.deleteBefore)
            sink.commit(edit.insert)
        } else {
            geez.reset()
            sink.commit(text)
        }
        if (s.shift) _state.update { it.copy(shift = false) }
    }

    fun space() { geez.reset(); sinkProvider()?.commit(" ") }
    fun backspace() { geez.reset(); sinkProvider()?.deleteBackward() }
    fun enter() { geez.reset(); sinkProvider()?.performEnter() }
    fun toggleShift() { _state.update { it.copy(shift = !it.shift) } }
    fun toggleSymbols() { geez.reset(); _state.update { it.copy(symbols = !it.symbols) } }
    fun toggleGeezInput() { geez.reset(); _state.update { it.copy(geezInput = !it.geezInput) } }

    fun cycleInputLanguage() {
        val s = _state.value.settings
        val list = Language.inputCandidates
        val next = list[(list.indexOf(s.inputLanguage) + 1) % list.size]
        scope.launch { graph.settings.setInputLanguage(next); graph.settings.setAutoDetect(next == Language.AUTO) }
    }

    fun cycleOutputLanguage() {
        val s = _state.value.settings
        val list = Language.outputCandidates
        val next = list[(list.indexOf(s.outputLanguage) + 1) % list.size]
        scope.launch { graph.settings.setOutputLanguage(next) }
    }

    fun setMode(mode: DictationMode) {
        scope.launch { graph.settings.setDictationMode(mode) }
        _state.update { it.copy(showTranslation = mode == DictationMode.TRANSLATE) }
    }

    fun openApp(route: String) = onOpenApp(route)

    private fun fail(e: AppError) {
        SemaLog.w(TAG, "keyboard error: ${e.javaClass.simpleName}")
        _state.update { it.copy(phase = KeyboardUiState.Phase.Error, error = e, recording = RecordingState.Idle, loadingLabel = null) }
    }

    /** Called from InputMethodService.onFinishInputView / onDestroy: stop everything, free mic. */
    fun onHidden() {
        if (_state.value.phase == KeyboardUiState.Phase.Recording) cancelRecording()
    }

    fun destroy() {
        job?.cancel()
        recorder?.release()
        recorder = null
        scope.cancel()
    }

    private companion object { const val TAG = "KeyboardController" }
}
