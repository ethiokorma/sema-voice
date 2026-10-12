package com.semavoice.core.audio

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Lightweight energy-based voice activity detector with adaptive noise floor.
 * It is deliberately simple and deterministic: it decides "speech / not speech" per
 * frame so the recorder can show a level meter, auto-stop on silence, and trim leading
 * and trailing silence. It never alters audio content.
 */
class VoiceActivityDetector(
    private val sampleRate: Int,
    private val frameMs: Int = 30,
    /** Speech must exceed the noise floor by this many dB. */
    private val thresholdDb: Float = 9f,
    private val minSpeechFrames: Int = 3,
) {
    val frameSize: Int = sampleRate * frameMs / 1000
    private var noiseFloorDb = -60f
    private var consecutiveSpeech = 0
    private var frames = 0

    data class Frame(val rms: Float, val db: Float, val isSpeech: Boolean)

    fun reset() { noiseFloorDb = -60f; consecutiveSpeech = 0; frames = 0 }

    fun analyze(samples: FloatArray, offset: Int = 0, length: Int = samples.size - offset): Frame {
        var sum = 0.0
        for (i in offset until offset + length) sum += samples[i] * samples[i]
        val rms = if (length == 0) 0f else sqrt(sum / length).toFloat()
        val db = 20f * log10(max(rms, 1e-6f))
        frames++
        // Adapt the noise floor slowly upward, quickly downward, during the first frames and quiet periods.
        val speechCandidate = db > noiseFloorDb + thresholdDb
        if (!speechCandidate || frames < 5) {
            noiseFloorDb = if (db < noiseFloorDb) db * 0.7f + noiseFloorDb * 0.3f else noiseFloorDb * 0.98f + db * 0.02f
        }
        consecutiveSpeech = if (speechCandidate) consecutiveSpeech + 1 else 0
        return Frame(rms, db, consecutiveSpeech >= minSpeechFrames)
    }

    /** Linear level in [0,1] for meters, derived from dB with a floor at -60 dB. */
    fun levelOf(db: Float): Float = ((db + 60f) / 60f).coerceIn(0f, 1f)
}

/**
 * Trims leading/trailing silence using a fresh VAD pass over the whole buffer.
 * A padding margin keeps speech onsets intact. Returns the original when trimming would
 * remove everything (an empty transcript is worse than a slightly longer inference).
 */
object SilenceTrimmer {
    fun trim(samples: FloatArray, sampleRate: Int, paddingMs: Int = 250): FloatArray {
        if (samples.isEmpty()) return samples
        val vad = VoiceActivityDetector(sampleRate, minSpeechFrames = 1)
        val fs = vad.frameSize
        val frameCount = samples.size / fs
        if (frameCount < 4) return samples
        val speech = BooleanArray(frameCount)
        for (f in 0 until frameCount) speech[f] = vad.analyze(samples, f * fs, fs).isSpeech
        val first = speech.indexOfFirst { it }
        val last = speech.indexOfLast { it }
        if (first < 0 || last < 0) return samples
        val pad = sampleRate * paddingMs / 1000
        val start = (first * fs - pad).coerceAtLeast(0)
        val end = ((last + 1) * fs + pad).coerceAtMost(samples.size)
        return samples.copyOfRange(start, end)
    }
}
