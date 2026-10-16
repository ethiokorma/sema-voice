package com.semavoice.core.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.semavoice.core.common.Language
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.semaDataStore: DataStore<Preferences> by preferencesDataStore(name = "sema_settings")

enum class DictationMode { EXACT, CLEAN, TRANSLATE, SMART }
enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class KeyboardHeight { COMPACT, NORMAL, TALL }
enum class AudioRetention { NEVER, ASK, ALWAYS }
enum class AppUiLanguage(val tag: String) { SYSTEM(""), ENGLISH("en"), AMHARIC("am") }

/** Immutable snapshot of every user preference. Defaults match section 20 of the spec. */
data class SemaSettings(
    // Language
    val inputLanguage: Language = Language.AMHARIC,
    val outputLanguage: Language = Language.ENGLISH,
    val autoDetectLanguage: Boolean = false,
    val preferMixedLanguage: Boolean = true,
    // Voice
    val silenceTimeoutMs: Int = 2_500,
    val autoStopOnSilence: Boolean = false,
    val maxRecordingSeconds: Int = 120,
    val preferredSpeechModelId: String = "",
    val micGain: Float = 1.0f,
    val threads: Int = 0, // 0 = auto
    // Translation
    val translationEnabled: Boolean = true,
    val preferredTranslationModelId: String = "",
    val preserveOriginal: Boolean = true,
    // Keyboard
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val keyboardHeight: KeyboardHeight = KeyboardHeight.NORMAL,
    val hapticFeedback: Boolean = true,
    val soundFeedback: Boolean = false,
    val autoInsert: Boolean = false,
    val suggestCorrections: Boolean = true,
    val dictationMode: DictationMode = DictationMode.CLEAN,
    val showTranslateToggle: Boolean = true,
    // Privacy
    val saveHistory: Boolean = false,
    val audioRetention: AudioRetention = AudioRetention.ASK,
    val diagnosticsLogging: Boolean = false,
    // Models
    val wifiOnlyDownloads: Boolean = true,
    /** URL of an optional remote catalog.json (e.g. where the Amharic fine-tunes are hosted). */
    val modelSourceUrl: String = "https://github.com/ethiokorma/sema-voice/releases/download/models-2026.10/catalog.json",
    // General
    val uiLanguage: AppUiLanguage = AppUiLanguage.SYSTEM,
    val onboardingComplete: Boolean = false,
    val largeMicTarget: Boolean = false,
    val highContrast: Boolean = false,
    // Voice Studio (optional, disabled by default)
    val voiceStudioEnabled: Boolean = false,
    val voiceStudioBaseUrl: String = "",
    val voiceStudioConsentGiven: Boolean = false,
)

class SettingsRepository(private val context: Context) {
    private object K {
        val INPUT_LANG = stringPreferencesKey("input_lang")
        val OUTPUT_LANG = stringPreferencesKey("output_lang")
        val AUTO_DETECT = booleanPreferencesKey("auto_detect")
        val MIXED = booleanPreferencesKey("prefer_mixed")
        val SILENCE_MS = intPreferencesKey("silence_ms")
        val AUTO_STOP = booleanPreferencesKey("auto_stop")
        val MAX_SECONDS = intPreferencesKey("max_seconds")
        val SPEECH_MODEL = stringPreferencesKey("speech_model")
        val MIC_GAIN = intPreferencesKey("mic_gain_x100")
        val THREADS = intPreferencesKey("threads")
        val TRANSLATION_ENABLED = booleanPreferencesKey("translation_enabled")
        val TRANSLATION_MODEL = stringPreferencesKey("translation_model")
        val PRESERVE_ORIGINAL = booleanPreferencesKey("preserve_original")
        val THEME = stringPreferencesKey("theme")
        val KB_HEIGHT = stringPreferencesKey("kb_height")
        val HAPTIC = booleanPreferencesKey("haptic")
        val SOUND = booleanPreferencesKey("sound")
        val AUTO_INSERT = booleanPreferencesKey("auto_insert")
        val SUGGEST = booleanPreferencesKey("suggest")
        val MODE = stringPreferencesKey("dictation_mode")
        val SHOW_TRANSLATE_TOGGLE = booleanPreferencesKey("show_translate_toggle")
        val SAVE_HISTORY = booleanPreferencesKey("save_history")
        val AUDIO_RETENTION = stringPreferencesKey("audio_retention")
        val DIAG = booleanPreferencesKey("diag")
        val WIFI_ONLY = booleanPreferencesKey("wifi_only")
        val MODEL_SOURCE = stringPreferencesKey("model_source_url")
        val UI_LANG = stringPreferencesKey("ui_lang")
        val ONBOARDED = booleanPreferencesKey("onboarded")
        val LARGE_MIC = booleanPreferencesKey("large_mic")
        val HIGH_CONTRAST = booleanPreferencesKey("high_contrast")
        val VS_ENABLED = booleanPreferencesKey("vs_enabled")
        val VS_URL = stringPreferencesKey("vs_url")
        val VS_CONSENT = booleanPreferencesKey("vs_consent")
    }

    val settings: Flow<SemaSettings> = context.semaDataStore.data.map { p -> p.toSettings() }

    suspend fun current(): SemaSettings = settings.first()

    private fun Preferences.toSettings(): SemaSettings {
        val d = SemaSettings()
        return SemaSettings(
            inputLanguage = this[K.INPUT_LANG]?.let(Language::fromCode) ?: d.inputLanguage,
            outputLanguage = this[K.OUTPUT_LANG]?.let(Language::fromCode) ?: d.outputLanguage,
            autoDetectLanguage = this[K.AUTO_DETECT] ?: d.autoDetectLanguage,
            preferMixedLanguage = this[K.MIXED] ?: d.preferMixedLanguage,
            silenceTimeoutMs = this[K.SILENCE_MS] ?: d.silenceTimeoutMs,
            autoStopOnSilence = this[K.AUTO_STOP] ?: d.autoStopOnSilence,
            maxRecordingSeconds = this[K.MAX_SECONDS] ?: d.maxRecordingSeconds,
            preferredSpeechModelId = this[K.SPEECH_MODEL] ?: d.preferredSpeechModelId,
            micGain = (this[K.MIC_GAIN] ?: 100) / 100f,
            threads = this[K.THREADS] ?: d.threads,
            translationEnabled = this[K.TRANSLATION_ENABLED] ?: d.translationEnabled,
            preferredTranslationModelId = this[K.TRANSLATION_MODEL] ?: d.preferredTranslationModelId,
            preserveOriginal = this[K.PRESERVE_ORIGINAL] ?: d.preserveOriginal,
            theme = enumOr(this[K.THEME], d.theme),
            keyboardHeight = enumOr(this[K.KB_HEIGHT], d.keyboardHeight),
            hapticFeedback = this[K.HAPTIC] ?: d.hapticFeedback,
            soundFeedback = this[K.SOUND] ?: d.soundFeedback,
            autoInsert = this[K.AUTO_INSERT] ?: d.autoInsert,
            suggestCorrections = this[K.SUGGEST] ?: d.suggestCorrections,
            dictationMode = enumOr(this[K.MODE], d.dictationMode),
            showTranslateToggle = this[K.SHOW_TRANSLATE_TOGGLE] ?: d.showTranslateToggle,
            saveHistory = this[K.SAVE_HISTORY] ?: d.saveHistory,
            audioRetention = enumOr(this[K.AUDIO_RETENTION], d.audioRetention),
            diagnosticsLogging = this[K.DIAG] ?: d.diagnosticsLogging,
            wifiOnlyDownloads = this[K.WIFI_ONLY] ?: d.wifiOnlyDownloads,
            modelSourceUrl = this[K.MODEL_SOURCE] ?: d.modelSourceUrl,
            uiLanguage = enumOr(this[K.UI_LANG], d.uiLanguage),
            onboardingComplete = this[K.ONBOARDED] ?: d.onboardingComplete,
            largeMicTarget = this[K.LARGE_MIC] ?: d.largeMicTarget,
            highContrast = this[K.HIGH_CONTRAST] ?: d.highContrast,
            voiceStudioEnabled = this[K.VS_ENABLED] ?: d.voiceStudioEnabled,
            voiceStudioBaseUrl = this[K.VS_URL] ?: d.voiceStudioBaseUrl,
            voiceStudioConsentGiven = this[K.VS_CONSENT] ?: d.voiceStudioConsentGiven,
        )
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.semaDataStore.edit { block(it) }
    }

    suspend fun setInputLanguage(l: Language) = edit { it[K.INPUT_LANG] = l.code }
    suspend fun setOutputLanguage(l: Language) = edit { it[K.OUTPUT_LANG] = l.code }
    suspend fun swapLanguages() {
        val s = current()
        if (s.inputLanguage == Language.AUTO) return
        edit { it[K.INPUT_LANG] = s.outputLanguage.code; it[K.OUTPUT_LANG] = s.inputLanguage.code }
    }
    suspend fun setAutoDetect(v: Boolean) = edit { it[K.AUTO_DETECT] = v }
    suspend fun setPreferMixed(v: Boolean) = edit { it[K.MIXED] = v }
    suspend fun setSilenceTimeoutMs(v: Int) = edit { it[K.SILENCE_MS] = v.coerceIn(500, 10_000) }
    suspend fun setAutoStopOnSilence(v: Boolean) = edit { it[K.AUTO_STOP] = v }
    suspend fun setMaxRecordingSeconds(v: Int) = edit { it[K.MAX_SECONDS] = v.coerceIn(10, 600) }
    suspend fun setPreferredSpeechModel(id: String) = edit { it[K.SPEECH_MODEL] = id }
    suspend fun setMicGain(v: Float) = edit { it[K.MIC_GAIN] = (v.coerceIn(0.5f, 3f) * 100).toInt() }
    suspend fun setThreads(v: Int) = edit { it[K.THREADS] = v.coerceIn(0, 8) }
    suspend fun setTranslationEnabled(v: Boolean) = edit { it[K.TRANSLATION_ENABLED] = v }
    suspend fun setPreferredTranslationModel(id: String) = edit { it[K.TRANSLATION_MODEL] = id }
    suspend fun setPreserveOriginal(v: Boolean) = edit { it[K.PRESERVE_ORIGINAL] = v }
    suspend fun setTheme(v: ThemeMode) = edit { it[K.THEME] = v.name }
    suspend fun setKeyboardHeight(v: KeyboardHeight) = edit { it[K.KB_HEIGHT] = v.name }
    suspend fun setHaptic(v: Boolean) = edit { it[K.HAPTIC] = v }
    suspend fun setSound(v: Boolean) = edit { it[K.SOUND] = v }
    suspend fun setAutoInsert(v: Boolean) = edit { it[K.AUTO_INSERT] = v }
    suspend fun setSuggestCorrections(v: Boolean) = edit { it[K.SUGGEST] = v }
    suspend fun setDictationMode(v: DictationMode) = edit { it[K.MODE] = v.name }
    suspend fun setShowTranslateToggle(v: Boolean) = edit { it[K.SHOW_TRANSLATE_TOGGLE] = v }
    suspend fun setSaveHistory(v: Boolean) = edit { it[K.SAVE_HISTORY] = v }
    suspend fun setAudioRetention(v: AudioRetention) = edit { it[K.AUDIO_RETENTION] = v.name }
    suspend fun setDiagnosticsLogging(v: Boolean) = edit { it[K.DIAG] = v }
    suspend fun setWifiOnlyDownloads(v: Boolean) = edit { it[K.WIFI_ONLY] = v }
    suspend fun setModelSourceUrl(v: String) = edit { it[K.MODEL_SOURCE] = v.trim() }
    suspend fun setUiLanguage(v: AppUiLanguage) = edit { it[K.UI_LANG] = v.name }
    suspend fun setOnboardingComplete(v: Boolean) = edit { it[K.ONBOARDED] = v }
    suspend fun setLargeMicTarget(v: Boolean) = edit { it[K.LARGE_MIC] = v }
    suspend fun setHighContrast(v: Boolean) = edit { it[K.HIGH_CONTRAST] = v }
    suspend fun setVoiceStudio(enabled: Boolean, baseUrl: String, consent: Boolean) = edit {
        it[K.VS_ENABLED] = enabled; it[K.VS_URL] = baseUrl; it[K.VS_CONSENT] = consent
    }

    /** "Clear all data" privacy action: resets every preference. */
    suspend fun clearAll() = edit { it.clear() }
}
