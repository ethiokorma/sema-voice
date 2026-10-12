package com.semavoice.core.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.semavoice.core.common.AppError
import com.semavoice.core.common.Language
import com.semavoice.core.common.Outcome
import com.semavoice.core.common.SemaLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Text-to-speech contract. Multiple engines can be registered (system TTS today; a
 * sherpa-onnx or Voice Studio engine later) and the UI lists only languages a given
 * engine actually has a voice for.
 */
interface TtsEngine {
    val id: String
    val displayName: String
    /** Whether processing happens on this device (shown to the user as a privacy indicator). */
    val isLocal: Boolean
    val ready: StateFlow<Boolean>
    suspend fun initialize(): Outcome<Unit>
    fun availableLanguages(): List<Language>
    fun isLanguageAvailable(language: Language): Boolean
    suspend fun speak(text: String, language: Language): Outcome<Unit>
    suspend fun synthesizeToFile(text: String, language: Language, out: File): Outcome<File>
    fun stop()
    fun release()
}

/**
 * Android system TTS. On most phones Google's "Speech Recognition & Synthesis" provides
 * an Amharic (am-ET) voice that can be downloaded for offline use. We never claim a
 * language is available until [TextToSpeech.isLanguageAvailable] says so.
 */
class AndroidSystemTts(private val context: Context) : TtsEngine {
    override val id = "android-system"
    override val displayName = "Android system voices"
    override val isLocal = true
    private val _ready = MutableStateFlow(false)
    override val ready: StateFlow<Boolean> = _ready.asStateFlow()
    private var tts: TextToSpeech? = null
    private var utteranceCounter = 0

    override suspend fun initialize(): Outcome<Unit> {
        if (_ready.value) return Outcome.Success(Unit)
        return suspendCancellableCoroutine { cont ->
            var engine: TextToSpeech? = null
            engine = TextToSpeech(context.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    tts = engine
                    _ready.value = true
                    if (cont.isActive) cont.resume(Outcome.Success(Unit))
                } else {
                    SemaLog.w(TAG, "TTS init failed: $status")
                    if (cont.isActive) cont.resume(Outcome.Failure(AppError.TtsVoiceUnavailable(Language.AUTO)))
                }
            }
            cont.invokeOnCancellation { engine?.shutdown() }
        }
    }

    private fun localeFor(language: Language): Locale = when (language) {
        Language.AMHARIC -> Locale("am", "ET")
        Language.ENGLISH -> Locale.US
        Language.ARABIC -> Locale("ar")
        Language.SOMALI -> Locale("so")
        Language.TIGRINYA -> Locale("ti")
        Language.AFAAN_OROMOO -> Locale("om")
        Language.AUTO -> Locale.getDefault()
    }

    override fun isLanguageAvailable(language: Language): Boolean {
        val t = tts ?: return false
        val r = t.isLanguageAvailable(localeFor(language))
        return r >= TextToSpeech.LANG_AVAILABLE
    }

    override fun availableLanguages(): List<Language> =
        Language.entries.filter { it != Language.AUTO && isLanguageAvailable(it) }

    override suspend fun speak(text: String, language: Language): Outcome<Unit> {
        val t = tts ?: return Outcome.Failure(AppError.TtsVoiceUnavailable(language))
        if (!isLanguageAvailable(language)) return Outcome.Failure(AppError.TtsVoiceUnavailable(language))
        t.language = localeFor(language)
        val id = "sema-${utteranceCounter++}"
        return suspendCancellableCoroutine { cont ->
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) { if (utteranceId == id && cont.isActive) cont.resume(Outcome.Success(Unit)) }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { if (utteranceId == id && cont.isActive) cont.resume(Outcome.Failure(AppError.InferenceFailed())) }
                override fun onError(utteranceId: String?, errorCode: Int) { onError(utteranceId) }
            })
            val rc = t.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
            if (rc != TextToSpeech.SUCCESS && cont.isActive) cont.resume(Outcome.Failure(AppError.InferenceFailed()))
            cont.invokeOnCancellation { t.stop() }
        }
    }

    override suspend fun synthesizeToFile(text: String, language: Language, out: File): Outcome<File> {
        val t = tts ?: return Outcome.Failure(AppError.TtsVoiceUnavailable(language))
        if (!isLanguageAvailable(language)) return Outcome.Failure(AppError.TtsVoiceUnavailable(language))
        t.language = localeFor(language)
        val id = "sema-file-${utteranceCounter++}"
        return suspendCancellableCoroutine { cont ->
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) { if (utteranceId == id && cont.isActive) cont.resume(Outcome.Success(out)) }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { if (utteranceId == id && cont.isActive) cont.resume(Outcome.Failure(AppError.InferenceFailed())) }
                override fun onError(utteranceId: String?, errorCode: Int) { onError(utteranceId) }
            })
            val rc = t.synthesizeToFile(text, null, out, id)
            if (rc != TextToSpeech.SUCCESS && cont.isActive) cont.resume(Outcome.Failure(AppError.InferenceFailed()))
        }
    }

    override fun stop() { tts?.stop() }

    override fun release() {
        tts?.shutdown()
        tts = null
        _ready.value = false
    }

    private companion object { const val TAG = "SystemTts" }
}
