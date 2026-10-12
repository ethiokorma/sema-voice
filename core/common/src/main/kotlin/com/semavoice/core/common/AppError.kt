package com.semavoice.core.common

/**
 * Every user-visible failure in the app is one of these. UI layers map each case to a
 * localized explanation and a recovery action (see section 21 of the product spec).
 * No silent failures: engines must surface one of these instead of returning empty results.
 */
sealed class AppError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class MicrophonePermissionDenied : AppError("Microphone permission denied")
    class NoMicrophone : AppError("No microphone available on this device")
    class MicrophoneBusy(cause: Throwable? = null) : AppError("Another app is using the microphone", cause)
    class RecordingFailed(cause: Throwable? = null) : AppError("Recording failed", cause)
    class RecordingInterrupted : AppError("Recording was interrupted")
    class EmptyRecording : AppError("Nothing was recorded")
    class UnsupportedLanguage(val language: Language) : AppError("Language not supported: ${language.code}")
    class ModelNotInstalled(val modelId: String) : AppError("Model not installed: $modelId")
    class ModelCorrupt(val modelId: String) : AppError("Model file is corrupt: $modelId")
    class ModelLoadFailed(val modelId: String, cause: Throwable? = null) : AppError("Could not load model $modelId", cause)
    class InsufficientStorage(val neededBytes: Long, val availableBytes: Long) :
        AppError("Need $neededBytes bytes, only $availableBytes available")
    class OutOfMemoryRisk(val modelId: String, val requiredMb: Int, val availableMb: Int) :
        AppError("Model $modelId needs ~${requiredMb}MB RAM, device has ${availableMb}MB")
    class TranslationUnavailable(val from: Language, val to: Language) :
        AppError("Translation ${from.code}->${to.code} unavailable")
    class KeyboardNotEnabled : AppError("Sema keyboard is not enabled")
    class NoActiveInput : AppError("No text field is focused")
    class SecureField : AppError("Voice input is disabled in password fields")
    class DownloadInterrupted(val modelId: String, cause: Throwable? = null) : AppError("Download interrupted: $modelId", cause)
    class ChecksumMismatch(val modelId: String) : AppError("Checksum mismatch: $modelId")
    class UnsupportedDevice(val reason: String) : AppError("Unsupported device: $reason")
    class NetworkUnavailable : AppError("No network connection")
    class CellularDownloadBlocked : AppError("Download blocked: Wi-Fi only setting is on")
    class InferenceFailed(cause: Throwable? = null) : AppError("Inference failed", cause)
    class TtsVoiceUnavailable(val language: Language) : AppError("No TTS voice installed for ${language.code}")
    class Cancelled : AppError("Cancelled")
    class Unknown(cause: Throwable?) : AppError(cause?.message ?: "Unknown error", cause)

    companion object {
        fun from(t: Throwable): AppError = when (t) {
            is AppError -> t
            is kotlinx.coroutines.CancellationException -> Cancelled()
            is OutOfMemoryError -> InferenceFailed(t)
            else -> Unknown(t)
        }
    }
}
