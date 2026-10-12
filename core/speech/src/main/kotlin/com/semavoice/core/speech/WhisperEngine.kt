package com.semavoice.core.speech

import com.semavoice.core.common.AppError
import com.semavoice.core.common.DispatcherProvider
import com.semavoice.core.common.Language
import com.semavoice.core.common.Outcome
import com.semavoice.core.common.PcmAudio
import com.semavoice.core.common.SemaLog
import com.semavoice.core.common.TextNormalizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * whisper.cpp-backed implementation of [SpeechRecognitionEngine].
 *
 * One model is resident at a time. Loading a different model releases the previous one
 * (section 13: never hold several AI models in memory). All native calls run on the
 * single-threaded inference dispatcher.
 */
class WhisperEngine(private val dispatchers: DispatcherProvider) : SpeechRecognitionEngine {
    private val _status = MutableStateFlow<SpeechModelStatus>(SpeechModelStatus.NotLoaded)
    override val status: StateFlow<SpeechModelStatus> = _status.asStateFlow()
    private val _progress = MutableStateFlow(0)
    override val progress: StateFlow<Int> = _progress.asStateFlow()

    @Volatile private var handle: Long = 0
    @Volatile private var loadedModelId: String = ""
    @Volatile private var loadedPath: String = ""
    private val scope = CoroutineScope(SupervisorJob() + dispatchers.default)
    private var progressJob: Job? = null

    val isLoaded: Boolean get() = handle != 0L
    val currentModelId: String get() = loadedModelId

    override suspend fun initialize(modelId: String, modelPath: String): Outcome<Unit> = withContext(dispatchers.inference) {
        if (handle != 0L && loadedModelId == modelId && loadedPath == modelPath) return@withContext Outcome.Success(Unit)
        if (!WhisperNative.ensureLoaded()) {
            val msg = WhisperNative.loadErrorMessage() ?: "native library unavailable"
            _status.value = SpeechModelStatus.Error(modelId, msg)
            return@withContext Outcome.Failure(AppError.UnsupportedDevice(msg))
        }
        val file = File(modelPath)
        if (!file.isFile || file.length() < 1_000_000) {
            _status.value = SpeechModelStatus.Error(modelId, "model file missing")
            return@withContext Outcome.Failure(AppError.ModelNotInstalled(modelId))
        }
        releaseInternal()
        _status.value = SpeechModelStatus.Loading(modelId)
        val t0 = System.currentTimeMillis()
        val h = try { WhisperNative.nativeInit(modelPath) } catch (t: Throwable) { 0L }
        if (h == 0L) {
            _status.value = SpeechModelStatus.Error(modelId, "whisper_init failed")
            return@withContext Outcome.Failure(AppError.ModelLoadFailed(modelId))
        }
        handle = h
        loadedModelId = modelId
        loadedPath = modelPath
        val multilingual = WhisperNative.nativeIsMultilingual(h)
        SemaLog.i(TAG, "model $modelId loaded in ${System.currentTimeMillis() - t0} ms, multilingual=$multilingual")
        _status.value = SpeechModelStatus.Ready(modelId, multilingual)
        Outcome.Success(Unit)
    }

    override suspend fun transcribe(audio: PcmAudio, options: TranscribeOptions): Outcome<Transcription> {
        if (audio.isEmpty || audio.durationMs < 200) return Outcome.Failure(AppError.EmptyRecording())
        if (audio.sampleRate != PcmAudio.WHISPER_SAMPLE_RATE) {
            return Outcome.Failure(AppError.InferenceFailed(IllegalArgumentException("audio must be 16 kHz")))
        }
        val lang = options.language
        if (lang != Language.AUTO && lang.whisperCode == null) return Outcome.Failure(AppError.UnsupportedLanguage(lang))

        return withContext(dispatchers.inference) {
            val h = handle
            if (h == 0L) return@withContext Outcome.Failure(AppError.ModelNotInstalled(loadedModelId.ifEmpty { "speech" }))
            val st = _status.value
            if (st is SpeechModelStatus.Ready && !st.multilingual && lang != Language.ENGLISH && lang != Language.AUTO) {
                return@withContext Outcome.Failure(AppError.UnsupportedLanguage(lang))
            }
            // whisper.cpp needs at least ~1 s of audio; pad short clips with silence.
            val samples = if (audio.samples.size < PcmAudio.WHISPER_SAMPLE_RATE * 11 / 10) {
                audio.samples.copyOf(PcmAudio.WHISPER_SAMPLE_RATE * 11 / 10)
            } else audio.samples
            val threads = if (options.threads > 0) options.threads else defaultThreads()
            _progress.value = 0
            progressJob = scope.launch { while (isActive) { _progress.value = WhisperNative.nativeProgress(h); delay(150) } }
            val t0 = System.currentTimeMillis()
            val rc = try {
                WhisperNative.nativeTranscribe(
                    h, samples, lang.whisperCode ?: "auto", options.translateToEnglish,
                    threads, options.initialPrompt?.takeIf { it.isNotBlank() }, options.singleSegment,
                )
            } catch (t: Throwable) {
                progressJob?.cancel()
                return@withContext Outcome.Failure(AppError.InferenceFailed(t))
            }
            progressJob?.cancel()
            _progress.value = 100
            val inferenceMs = System.currentTimeMillis() - t0
            when {
                rc == 1 -> Outcome.Failure(AppError.Cancelled())
                rc != 0 -> Outcome.Failure(AppError.InferenceFailed(RuntimeException("whisper_full rc=$rc")))
                else -> {
                    val n = WhisperNative.nativeSegmentCount(h)
                    val segments = (0 until n).map { i ->
                        TranscriptSegment(
                            WhisperNative.nativeSegmentT0(h, i), WhisperNative.nativeSegmentT1(h, i),
                            TextNormalizer.normalize(WhisperNative.nativeSegmentText(h, i)),
                        )
                    }.filter { it.text.isNotBlank() }
                    val detected = WhisperNative.nativeDetectedLanguage(h)
                    val text = segments.joinToString(" ") { it.text }.trim()
                    SemaLog.i(TAG, "transcribed ${audio.durationMs} ms in $inferenceMs ms (${segments.size} segments)")
                    Outcome.Success(
                        Transcription(
                            text = text,
                            segments = segments,
                            language = if (lang == Language.AUTO) Language.fromCode(detected) else lang,
                            inferenceMs = inferenceMs,
                            audioMs = audio.durationMs,
                            modelId = loadedModelId,
                        ),
                    )
                }
            }
        }
    }

    override fun cancel() { val h = handle; if (h != 0L) WhisperNative.nativeCancel(h) }

    override suspend fun release() = withContext(dispatchers.inference) { releaseInternal() }

    private fun releaseInternal() {
        val h = handle
        if (h != 0L) {
            WhisperNative.nativeFree(h)
            handle = 0
            loadedModelId = ""
            loadedPath = ""
            SemaLog.i(TAG, "model released")
        }
        _status.value = SpeechModelStatus.NotLoaded
    }

    override fun getSupportedLanguages(): List<Language> {
        val st = _status.value
        return if (st is SpeechModelStatus.Ready && !st.multilingual) listOf(Language.ENGLISH)
        else Language.entries.filter { it.supportsWhisper }
    }

    private fun defaultThreads(): Int = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)

    private companion object { const val TAG = "WhisperEngine" }
}
