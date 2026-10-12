package com.semavoice.core.common

/** PCM audio in the canonical format used by every engine: mono float32 in [-1, 1]. */
class PcmAudio(val samples: FloatArray, val sampleRate: Int) {
    val durationMs: Long get() = if (sampleRate == 0) 0 else samples.size * 1000L / sampleRate
    val isEmpty: Boolean get() = samples.isEmpty()

    companion object {
        const val WHISPER_SAMPLE_RATE = 16_000
    }
}
