package com.semavoice.core.speech

import com.semavoice.core.common.Language
import com.semavoice.core.common.Outcome
import com.semavoice.core.common.PcmAudio
import kotlinx.coroutines.flow.StateFlow

data class TranscriptSegment(val startMs: Long, val endMs: Long, val text: String)

data class Transcription(
    val text: String,
    val segments: List<TranscriptSegment>,
    /** Language actually used/detected by the model, or AUTO if unknown. */
    val language: Language,
    val inferenceMs: Long,
    val audioMs: Long,
    val modelId: String,
) {
    /** Real-time factor: inference time / audio duration. < 1 means faster than real time. */
    val realTimeFactor: Float get() = if (audioMs == 0L) 0f else inferenceMs.toFloat() / audioMs
}

data class TranscribeOptions(
    val language: Language = Language.AUTO,
    /** Whisper's built-in X->English translation. Only used when the user explicitly asks. */
    val translateToEnglish: Boolean = false,
    val threads: Int = 0,
    /** Optional vocabulary hint (user dictionary terms). Whisper uses it as decoder prompt context. */
    val initialPrompt: String? = null,
    val singleSegment: Boolean = false,
)

sealed class SpeechModelStatus {
    data object NotLoaded : SpeechModelStatus()
    data class Loading(val modelId: String) : SpeechModelStatus()
    data class Ready(val modelId: String, val multilingual: Boolean) : SpeechModelStatus()
    data class Error(val modelId: String, val message: String) : SpeechModelStatus()
}

/**
 * Engine-agnostic speech recognition contract (section 7). Implementations must be safe
 * to call from any thread; heavy work must never run on the main thread.
 */
interface SpeechRecognitionEngine {
    val status: StateFlow<SpeechModelStatus>
    /** 0..100 while a transcription is running. */
    val progress: StateFlow<Int>

    suspend fun initialize(modelId: String, modelPath: String): Outcome<Unit>
    suspend fun transcribe(audio: PcmAudio, options: TranscribeOptions): Outcome<Transcription>
    fun cancel()
    suspend fun release()
    fun getSupportedLanguages(): List<Language>
    fun getModelStatus(): SpeechModelStatus = status.value
}
