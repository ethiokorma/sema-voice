package com.semavoice.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

class AudioUtilsTest {
    private fun tone(rate: Int, seconds: Float, freq: Float, amp: Float): FloatArray =
        FloatArray((rate * seconds).toInt()) { i -> (amp * sin(2 * PI * freq * i / rate)).toFloat() }

    @Test fun resampleHalvesLength() {
        val input = tone(32_000, 1f, 440f, 0.5f)
        val out = Resampler.resample(input, 32_000, 16_000)
        assertEquals(16_000, out.size)
        assertEquals(input[0], out[0], 1e-6f)
    }

    @Test fun resampleSameRateIsIdentity() {
        val input = tone(16_000, 0.1f, 440f, 0.5f)
        assertTrue(Resampler.resample(input, 16_000, 16_000) === input)
    }

    @Test fun vadDetectsSpeechOverSilence() {
        val rate = 16_000
        val vad = VoiceActivityDetector(rate)
        val silence = FloatArray(vad.frameSize) { 0.0005f }
        repeat(10) { assertFalse(vad.analyze(silence).isSpeech) }
        val loud = tone(rate, 0.03f, 300f, 0.4f)
        var speech = false
        repeat(5) { speech = vad.analyze(loud).isSpeech }
        assertTrue(speech)
    }

    @Test fun silenceTrimmerRemovesLeadingAndTrailingSilence() {
        val rate = 16_000
        val quiet = FloatArray(rate) { 0.0003f }
        val speech = tone(rate, 1f, 250f, 0.5f)
        val all = quiet + speech + quiet
        val trimmed = SilenceTrimmer.trim(all, rate)
        assertTrue(trimmed.size < all.size)
        assertTrue(trimmed.size >= speech.size)
    }

    @Test fun silenceTrimmerKeepsAllSilenceInput() {
        val quiet = FloatArray(16_000) { 0.0003f }
        assertEquals(quiet.size, SilenceTrimmer.trim(quiet, 16_000).size)
    }

    @Test fun wavRoundTrip() {
        val f = File.createTempFile("sema", ".wav")
        try {
            val samples = tone(16_000, 0.25f, 440f, 0.8f)
            WavCodec.write(f, samples, 16_000)
            val back = WavCodec.read(f)
            assertEquals(16_000, back.sampleRate)
            assertEquals(samples.size, back.samples.size)
            assertEquals(samples[100], back.samples[100], 1e-3f)
        } finally { f.delete() }
    }
}
