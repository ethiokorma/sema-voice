package com.semavoice.core.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

class W2vBertFeatureExtractorTest {
    private fun floats(name: String): FloatArray {
        val bytes = javaClass.classLoader!!.getResourceAsStream("w2vbert/$name")!!.readBytes()
        val fb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(fb.remaining()).also { fb.get(it) }
    }

    /** Reference produced by transformers.SeamlessM4TFeatureExtractor on 1.5 s of FLEURS am clip 000. */
    @Test fun matchesPythonReference() {
        val input = floats("input.f32")
        val expected = floats("features.f32")
        val (got, frames) = W2vBertFeatureExtractor().extract(input)
        assertEquals(74, frames)
        assertEquals(expected.size, got.size)
        var maxErr = 0.0
        var sumErr = 0.0
        for (i in got.indices) { val e = abs(got[i] - expected[i]).toDouble(); sumErr += e; if (e > maxErr) maxErr = e }
        val meanErr = sumErr / got.size
        assertTrue("mean abs error $meanErr", meanErr < 1e-3)
        assertTrue("max abs error $maxErr", maxErr < 2e-2)
    }

    @Test fun ctcCollapsesRepeatsAndBlanks() {
        val vocab = mapOf("|" to 0, "ሰ" to 1, "ላ" to 2, "ም" to 3, "[PAD]" to 4, "[AMH]" to 5)
        val d = CtcDecoder(vocab, blankId = 4)
        // frames: ሰ ሰ PAD ላ ም ም | | ሰ AMH
        val seq = intArrayOf(1, 1, 4, 2, 3, 3, 0, 0, 1, 5)
        val logits = FloatArray(seq.size * 6).also { l -> seq.forEachIndexed { t, id -> l[t * 6 + id] = 1f } }
        assertEquals("ሰላም ሰ", d.decode(logits, seq.size, 6))
    }
}
