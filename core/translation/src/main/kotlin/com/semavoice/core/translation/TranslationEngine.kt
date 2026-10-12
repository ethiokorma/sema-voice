package com.semavoice.core.translation

import com.semavoice.core.common.Language
import com.semavoice.core.common.Outcome
import kotlinx.coroutines.flow.StateFlow

data class TranslationResult(
    val original: String,
    val translated: String,
    val from: Language,
    val to: Language,
    val inferenceMs: Long,
    val modelId: String,
    /** Always true for the bundled engines; the UI shows it as the privacy indicator. */
    val processedLocally: Boolean = true,
)

sealed class TranslationModelStatus {
    data object NotLoaded : TranslationModelStatus()
    data class Loading(val modelId: String) : TranslationModelStatus()
    data class Ready(val modelId: String) : TranslationModelStatus()
    data class Error(val modelId: String, val message: String) : TranslationModelStatus()
}

/**
 * Model-independent translation contract (section 8). An engine only reports a direction
 * as supported when the loaded model actually covers it.
 */
interface TranslationEngine {
    val status: StateFlow<TranslationModelStatus>
    suspend fun initialize(modelId: String, modelDir: java.io.File): Outcome<Unit>
    fun supports(from: Language, to: Language): Boolean
    suspend fun translate(text: String, from: Language, to: Language): Outcome<TranslationResult>
    fun cancel()
    suspend fun release()
}
