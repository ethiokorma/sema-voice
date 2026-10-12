package com.semavoice.keyboard

import android.content.Context
import com.semavoice.core.common.AppError

/** Maps every [AppError] to a localized explanation plus an optional recovery action (section 21). */
object ErrorMessages {
    data class Presentation(val message: String, val actionLabel: String?, val actionRoute: String?)

    fun present(context: Context, e: AppError): Presentation {
        val r = context.resources
        return when (e) {
            is AppError.MicrophonePermissionDenied -> Presentation(r.getString(R.string.err_mic_permission), r.getString(R.string.err_mic_permission_action), "permission")
            is AppError.NoMicrophone -> Presentation(r.getString(R.string.err_no_mic), null, null)
            is AppError.MicrophoneBusy -> Presentation(r.getString(R.string.err_mic_busy), r.getString(R.string.kb_retry), "retry")
            is AppError.EmptyRecording -> Presentation(r.getString(R.string.err_empty), r.getString(R.string.kb_retry), "retry")
            is AppError.ModelNotInstalled -> Presentation(r.getString(R.string.err_model_missing), r.getString(R.string.err_model_missing_action), "models")
            is AppError.ModelCorrupt -> Presentation(r.getString(R.string.err_model_corrupt), r.getString(R.string.err_model_missing_action), "models")
            is AppError.ModelLoadFailed -> Presentation(r.getString(R.string.err_model_corrupt), r.getString(R.string.err_model_missing_action), "models")
            is AppError.UnsupportedLanguage -> Presentation(r.getString(R.string.err_unsupported_language, e.language.englishName), r.getString(R.string.err_model_missing_action), "models")
            is AppError.TranslationUnavailable -> Presentation(r.getString(R.string.err_translation_unavailable, e.from.englishName, e.to.englishName), r.getString(R.string.err_model_missing_action), "models")
            is AppError.SecureField -> Presentation(r.getString(R.string.err_secure_field), null, null)
            is AppError.NoActiveInput -> Presentation(r.getString(R.string.err_no_field), null, null)
            is AppError.RecordingInterrupted -> Presentation(r.getString(R.string.err_interrupted), r.getString(R.string.kb_retry), "retry")
            is AppError.RecordingFailed -> Presentation(r.getString(R.string.err_interrupted), r.getString(R.string.kb_retry), "retry")
            is AppError.UnsupportedDevice -> Presentation(r.getString(R.string.err_device), null, null)
            is AppError.OutOfMemoryRisk -> Presentation(r.getString(R.string.err_memory), r.getString(R.string.err_model_missing_action), "models")
            is AppError.Cancelled -> Presentation(r.getString(R.string.err_cancelled), null, null)
            else -> Presentation(r.getString(R.string.err_generic), r.getString(R.string.kb_retry), "retry")
        }
    }
}
