package com.semavoice.core.runtime

import com.semavoice.core.common.AppError
import com.semavoice.core.common.Language
import com.semavoice.core.common.Outcome
import com.semavoice.core.common.PcmAudio
import com.semavoice.core.common.TextNormalizer
import com.semavoice.core.database.HistoryItem
import com.semavoice.core.database.HistoryKind
import com.semavoice.core.database.HistoryRepository
import com.semavoice.core.models.ModelKind
import com.semavoice.core.settings.DictationMode
import com.semavoice.core.settings.SemaSettings
import com.semavoice.core.speech.TranscribeOptions
import com.semavoice.core.speech.Transcription
import com.semavoice.core.writing.DictionaryPostProcessor
import com.semavoice.core.writing.RuleBasedWritingAssistant
import com.semavoice.core.writing.WritingAction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the user gets back after speaking: the exact transcript plus optional derived texts. */
data class DictationResult(
    val transcript: Transcription,
    /** Transcript after the selected mode's deterministic cleanup (same as transcript.text in EXACT mode). */
    val displayText: String,
    val appliedCorrections: List<DictionaryPostProcessor.Correction>,
    val suggestedCorrections: List<DictionaryPostProcessor.Correction>,
    val translation: String?,
    val translationLanguage: Language?,
    val translationError: AppError?,
    val mixedLanguage: Boolean,
    /** Repetition/hallucination check of the raw model output; raw text stays in [transcript]. */
    val repetition: com.semavoice.core.common.RepetitionGuard.Result? = null,
)

sealed class PipelineStage {
    data object Idle : PipelineStage()
    data class LoadingModel(val name: String) : PipelineStage()
    data class Transcribing(val progress: Int) : PipelineStage()
    data object Translating : PipelineStage()
    data class Done(val result: DictationResult) : PipelineStage()
    data class Failed(val error: AppError) : PipelineStage()
}

/**
 * Pure mode logic, separated for unit testing: how each dictation mode transforms the raw
 * transcript. Nothing here changes meaning; CLEAN only fixes punctuation and fillers.
 */
object DictationModes {
    fun apply(mode: DictationMode, text: String, language: Language, writing: RuleBasedWritingAssistant): String = when (mode) {
        DictationMode.EXACT -> TextNormalizer.normalize(text)
        DictationMode.CLEAN, DictationMode.TRANSLATE, DictationMode.SMART ->
            writing.improvePunctuation(writing.removeDisfluencies(text, language))
    }
}

class DictationPipeline(private val g: SemaGraph) {
    /** Engine that served the last loaded model (Whisper or CTC). */
    @Volatile var active: com.semavoice.core.speech.SpeechRecognitionEngine = g.speech
        private set

    private fun engineFor(format: String): com.semavoice.core.speech.SpeechRecognitionEngine =
        if (format == "onnx-ctc") g.ctc else g.speech

    fun cancelRecognition() { g.speech.cancel(); g.ctc.cancel() }
    private val _stage = MutableStateFlow<PipelineStage>(PipelineStage.Idle)
    val stage: StateFlow<PipelineStage> = _stage.asStateFlow()

    /** Ensures the right speech model is resident for [language]. */
    suspend fun ensureSpeechModel(language: Language, settings: SemaSettings): Outcome<String> {
        val model = g.modelManager.bestSpeechModelFor(language, settings.preferredSpeechModelId)
            ?: g.modelManager.bestSpeechModelFor(Language.AUTO, settings.preferredSpeechModelId)
            ?: return Outcome.Failure(AppError.ModelNotInstalled("speech"))
        if (language != Language.AUTO && language !in model.info.languages) {
            return Outcome.Failure(AppError.UnsupportedLanguage(language))
        }
        val engine = engineFor(model.info.format)
        if (engine === g.ctc) { if (g.ctc.currentModelId == model.info.id && g.ctc.isLoaded) { active = g.ctc; return Outcome.Success(model.info.id) } }
        else if (g.speech.currentModelId == model.info.id && g.speech.isLoaded) { active = g.speech; return Outcome.Success(model.info.id) }
        // One large model resident at a time: release the other engine first.
        if (engine === g.ctc) g.speech.release() else g.ctc.release()
        _stage.value = PipelineStage.LoadingModel(model.info.displayName)
        val path = g.modelManager.installedPath(model.info.id, model.info.files.first().name)
            ?: return Outcome.Failure(AppError.ModelNotInstalled(model.info.id))
        active = engine
        return engine.initialize(model.info.id, path).map { model.info.id }
    }

    suspend fun ensureTranslationModel(from: Language, to: Language, settings: SemaSettings): Outcome<String> {
        val model = g.modelManager.translationModelFor(from, to, settings.preferredTranslationModelId)
            ?: return Outcome.Failure(AppError.TranslationUnavailable(from, to))
        if (g.translator.supports(from, to)) return Outcome.Success(model.info.id)
        _stage.value = PipelineStage.LoadingModel(model.info.displayName)
        return g.translator.initialize(model.info.id, g.modelManager.modelDir(model.info.id)).map { model.info.id }
    }

    /**
     * Full flow: transcription -> dictionary post-processing -> mode cleanup -> optional translation.
     * Audio never leaves the process. History is written only if the user enabled it.
     */
    suspend fun process(audio: PcmAudio, settings: SemaSettings, mode: DictationMode = settings.dictationMode, translateTo: Language? = null): Outcome<DictationResult> {
        val language = if (settings.autoDetectLanguage) Language.AUTO else settings.inputLanguage
        when (val m = ensureSpeechModel(language, settings)) {
            is Outcome.Failure -> { _stage.value = PipelineStage.Failed(m.error); return m }
            is Outcome.Success -> Unit
        }
        _stage.value = PipelineStage.Transcribing(0)
        val dictTerms = runCatching { g.dictionary.forLanguage(if (language == Language.AUTO) settings.inputLanguage else language) }.getOrDefault(emptyList())
        val prompt = dictTerms.take(40).joinToString(", ") { it.term }.takeIf { it.isNotBlank() }
        val transcription = when (val r = active.transcribe(audio, TranscribeOptions(language = language, threads = settings.threads, initialPrompt = prompt))) {
            is Outcome.Failure -> { _stage.value = PipelineStage.Failed(r.error); return r }
            is Outcome.Success -> r.value
        }
        if (transcription.text.isBlank()) {
            val e = AppError.EmptyRecording(); _stage.value = PipelineStage.Failed(e); return Outcome.Failure(e)
        }
        val guard = com.semavoice.core.common.RepetitionGuard.check(transcription.text, transcription.audioMs)
        val post = DictionaryPostProcessor(dictTerms.map { DictionaryPostProcessor.Term(it.term, it.variants) }).process(guard.text)
        val detected = transcription.language.takeIf { it != Language.AUTO } ?: settings.inputLanguage
        val display = DictationModes.apply(mode, post.text, detected, g.writing)
        val mixed = TextNormalizer.isMixedScript(display)

        var translation: String? = null
        var translationError: AppError? = null
        val target = translateTo ?: settings.outputLanguage
        val wantTranslation = (mode == DictationMode.TRANSLATE || translateTo != null) && settings.translationEnabled && target != detected
        if (wantTranslation) {
            _stage.value = PipelineStage.Translating
            when (val tm = ensureTranslationModel(detected, target, settings)) {
                is Outcome.Failure -> translationError = tm.error
                is Outcome.Success -> when (val t = g.translator.translate(display, detected, target)) {
                    is Outcome.Failure -> translationError = t.error
                    is Outcome.Success -> translation = t.value.translated
                }
            }
        }
        val result = DictationResult(
            transcript = transcription, displayText = display,
            appliedCorrections = post.applied, suggestedCorrections = post.suggestions,
            translation = translation, translationLanguage = if (translation != null) target else null,
            translationError = translationError, mixedLanguage = mixed, repetition = guard,
        )
        if (settings.saveHistory) runCatching { recordHistory(result, detected, g.history) }
        _stage.value = PipelineStage.Done(result)
        return Outcome.Success(result)
    }

    suspend fun translateText(text: String, from: Language, to: Language, settings: SemaSettings): Outcome<String> {
        if (from == to) return Outcome.Success(text)
        return when (val m = ensureTranslationModel(from, to, settings)) {
            is Outcome.Failure -> m
            is Outcome.Success -> g.translator.translate(text, from, to).map { it.translated }
        }
    }

    suspend fun applyWriting(action: WritingAction, text: String, language: Language) = g.writing.apply(action, text, language)

    fun reset() { _stage.value = PipelineStage.Idle }

    fun hasAnySpeechModel(): Boolean = g.modelManager.models.value.any { it.info.kind == ModelKind.SPEECH && it.isUsable }

    private suspend fun recordHistory(r: DictationResult, from: Language, repo: HistoryRepository) {
        val now = System.currentTimeMillis()
        repo.save(HistoryItem(HistoryRepository.newId(), now, HistoryKind.TRANSCRIPTION, from, null, r.displayText, null, false, null))
        if (r.translation != null && r.translationLanguage != null) {
            repo.save(HistoryItem(HistoryRepository.newId(), now + 1, HistoryKind.TRANSLATION, from, r.translationLanguage, r.displayText, r.translation, false, null))
        }
    }
}
