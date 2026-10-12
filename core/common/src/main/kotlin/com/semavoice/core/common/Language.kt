package com.semavoice.core.common

import kotlinx.serialization.Serializable

/**
 * Languages the product knows about. A language being listed here does NOT mean
 * speech recognition or translation is available for it: capability is decided at
 * runtime by the installed models (see core:models ModelCatalog).
 *
 * [whisperCode] is null for languages that Whisper's multilingual models do not cover
 * (Tigrinya and Afaan Oromoo are not in Whisper's 99-language list).
 */
@Serializable
enum class Language(
    val code: String,
    val englishName: String,
    val nativeName: String,
    val script: Script,
    val whisperCode: String?,
    val isRtl: Boolean = false,
) {
    AUTO("auto", "Auto-detect", "Auto", Script.LATIN, null),
    AMHARIC("am", "Amharic", "አማርኛ", Script.GEEZ, "am"),
    ENGLISH("en", "English", "English", Script.LATIN, "en"),
    AFAAN_OROMOO("om", "Afaan Oromoo", "Afaan Oromoo", Script.LATIN, null),
    TIGRINYA("ti", "Tigrinya", "ትግርኛ", Script.GEEZ, null),
    SOMALI("so", "Somali", "Soomaali", Script.LATIN, "so"),
    ARABIC("ar", "Arabic", "العربية", Script.ARABIC, "ar", isRtl = true);

    val supportsWhisper: Boolean get() = whisperCode != null

    companion object {
        fun fromCode(code: String?): Language =
            entries.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: AUTO

        /**
         * Languages a user may pick as a *spoken* input language. Somali is excluded until a
         * model with measured quality exists (stock Whisper scores >100% WER on Somali).
         */
        val inputCandidates: List<Language> = listOf(AUTO, AMHARIC, ENGLISH, ARABIC)

        /** Languages a user may pick as a translation target. */
        val outputCandidates: List<Language> = listOf(AMHARIC, ENGLISH, AFAAN_OROMOO, TIGRINYA, SOMALI, ARABIC)
    }
}

@Serializable
enum class Script { LATIN, GEEZ, ARABIC }
