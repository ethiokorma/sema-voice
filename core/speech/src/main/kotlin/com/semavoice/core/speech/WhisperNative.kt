package com.semavoice.core.speech

import android.os.Build
import com.semavoice.core.common.SemaLog
import java.io.File

/**
 * Thin JNI surface over whisper.cpp. Not thread-safe: callers must serialise access to a
 * given context handle (WhisperEngine does this through the inference dispatcher).
 */
object WhisperNative {
    private const val TAG = "WhisperNative"
    @Volatile private var loaded = false
    @Volatile private var loadError: String? = null

    /**
     * The arm64 library is compiled for ARMv8.2-A with fp16 + dotprod. Loading it on an
     * older core would crash with SIGILL, so we check the kernel-reported CPU features
     * first and refuse gracefully instead.
     */
    fun deviceSupportsNativeLib(): Boolean {
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: return false
        if (abi == "x86_64") return true
        if (abi != "arm64-v8a") return false
        val cpuinfo = runCatching { File("/proc/cpuinfo").readText() }.getOrNull() ?: return true
        val features = cpuinfo.lineSequence().firstOrNull { it.startsWith("Features") } ?: return true
        return features.contains("fphp") && features.contains("asimddp")
    }

    @Synchronized
    fun ensureLoaded(): Boolean {
        if (loaded) return true
        if (loadError != null) return false
        if (!deviceSupportsNativeLib()) {
            loadError = "CPU lacks ARMv8.2 fp16/dotprod"
            return false
        }
        return try {
            System.loadLibrary("sema_whisper")
            loaded = true
            SemaLog.i(TAG, "loaded; ${nativeSystemInfo()}")
            true
        } catch (e: UnsatisfiedLinkError) {
            loadError = e.message
            SemaLog.e(TAG, "load failed", e)
            false
        }
    }

    fun loadErrorMessage(): String? = loadError

    external fun nativeInit(path: String): Long
    external fun nativeFree(handle: Long)
    external fun nativeIsMultilingual(handle: Long): Boolean
    external fun nativeLangId(lang: String): Int
    external fun nativeTranscribe(
        handle: Long, samples: FloatArray, language: String, translateToEnglish: Boolean,
        threads: Int, prompt: String?, singleSegment: Boolean,
    ): Int
    external fun nativeCancel(handle: Long)
    external fun nativeProgress(handle: Long): Int
    external fun nativeSegmentCount(handle: Long): Int
    external fun nativeSegmentText(handle: Long, index: Int): String
    external fun nativeSegmentT0(handle: Long, index: Int): Long
    external fun nativeSegmentT1(handle: Long, index: Int): Long
    external fun nativeDetectedLanguage(handle: Long): String
    external fun nativeSystemInfo(): String
}
