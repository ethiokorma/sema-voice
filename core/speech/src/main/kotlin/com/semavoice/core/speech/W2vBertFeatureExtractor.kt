package com.semavoice.core.speech

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Pure-Kotlin port of Hugging Face `SeamlessM4TFeatureExtractor` (the front-end of w2v-BERT 2.0
 * CTC models such as snapwre/hohe-asr-amharic). Verified against reference features produced by
 * the Python extractor (see W2vBertFeatureExtractorTest).
 *
 * Steps: scale to int16 range → 25 ms frames / 10 ms hop (no centering) → remove DC → pre-emphasis
 * 0.97 → Povey window → 512-pt power spectrum → 80 Kaldi mel bins (20 Hz..8 kHz, triangles in mel
 * space) → log(max(x, 1.1920929e-7)) → per-bin mean/variance normalisation (ddof = 1) → stack
 * every 2 frames into 160-dim vectors (odd trailing frame dropped).
 */
class W2vBertFeatureExtractor(
    private val sampleRate: Int = 16_000,
    private val numMelBins: Int = 80,
    private val stride: Int = 2,
) {
    private val frameLength = 400
    private val hop = 160
    private val nFft = 512
    private val nBins = nFft / 2 + 1
    private val window = DoubleArray(frameLength) { n -> (0.5 - 0.5 * cos(2 * PI * n / (frameLength - 1))).pow(0.85) }
    private val melFilters: Array<DoubleArray> = kaldiMelBanks()

    val featureDim: Int get() = numMelBins * stride

    /** Returns features as a flat row-major [frames × featureDim] array and the frame count. */
    fun extract(samples: FloatArray): Pair<FloatArray, Int> {
        val numFrames = if (samples.size < frameLength) 0 else 1 + (samples.size - frameLength) / hop
        val logMel = Array(numFrames) { DoubleArray(numMelBins) }
        val re = DoubleArray(nFft)
        val im = DoubleArray(nFft)
        val frame = DoubleArray(frameLength)
        for (t in 0 until numFrames) {
            val off = t * hop
            var mean = 0.0
            for (i in 0 until frameLength) { frame[i] = samples[off + i] * 32768.0; mean += frame[i] }
            mean /= frameLength
            for (i in 0 until frameLength) frame[i] -= mean
            for (i in frameLength - 1 downTo 1) frame[i] -= 0.97 * frame[i - 1]
            frame[0] *= 1 - 0.97
            java.util.Arrays.fill(re, 0.0); java.util.Arrays.fill(im, 0.0)
            for (i in 0 until frameLength) re[i] = frame[i] * window[i]
            fft(re, im)
            for (m in 0 until numMelBins) {
                val w = melFilters[m]
                var acc = 0.0
                for (k in 0 until nBins) if (w[k] != 0.0) acc += w[k] * (re[k] * re[k] + im[k] * im[k])
                logMel[t][m] = ln(max(acc, 1.192092955078125e-07))
            }
        }
        // per-mel-bin normalisation over time (ddof = 1)
        if (numFrames > 1) for (m in 0 until numMelBins) {
            var mean = 0.0
            for (t in 0 until numFrames) mean += logMel[t][m]
            mean /= numFrames
            var v = 0.0
            for (t in 0 until numFrames) { val d = logMel[t][m] - mean; v += d * d }
            val std = sqrt(v / (numFrames - 1) + 1e-7)
            for (t in 0 until numFrames) logMel[t][m] = (logMel[t][m] - mean) / std
        }
        val outFrames = numFrames / stride
        val out = FloatArray(outFrames * featureDim)
        for (t in 0 until outFrames) for (s in 0 until stride) for (m in 0 until numMelBins) {
            out[t * featureDim + s * numMelBins + m] = logMel[t * stride + s][m].toFloat()
        }
        return out to outFrames
    }

    private fun kaldiMelBanks(): Array<DoubleArray> {
        fun mel(f: Double) = 1127.0 * ln(1.0 + f / 700.0)
        val low = mel(20.0)
        val high = mel(sampleRate / 2.0)
        val delta = (high - low) / (numMelBins + 1)
        return Array(numMelBins) { m ->
            val left = low + m * delta
            val center = low + (m + 1) * delta
            val right = low + (m + 2) * delta
            DoubleArray(nBins) { k ->
                val f = k * sampleRate.toDouble() / nFft
                val mm = mel(f)
                val up = (mm - left) / (center - left)
                val down = (right - mm) / (right - center)
                max(0.0, minOf(up, down))
            }
        }
    }

    /** In-place iterative radix-2 FFT. */
    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = cos(ang); val wi = kotlin.math.sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until len / 2) {
                    val ur = re[i + k]; val ui = im[i + k]
                    val vr = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                    val vi = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k] = ur + vr; im[i + k] = ui + vi
                    re[i + k + len / 2] = ur - vr; im[i + k + len / 2] = ui - vi
                    val nr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = nr
                }
                i += len
            }
            len = len shl 1
        }
    }
}

/** Greedy CTC decoding for character-vocabulary models (blank = [PAD], "|" = word boundary). */
class CtcDecoder(vocab: Map<String, Int>, private val blankId: Int, private val wordDelimiter: String = "|") {
    private val idToToken: Array<String?> = arrayOfNulls<String>((vocab.values.maxOrNull() ?: 0) + 1).also { a -> vocab.forEach { (k, v) -> a[v] = k } }

    fun decode(logits: FloatArray, frames: Int, vocabSize: Int): String {
        val sb = StringBuilder()
        var prev = -1
        for (t in 0 until frames) {
            var best = 0
            var bestV = Float.NEGATIVE_INFINITY
            val base = t * vocabSize
            for (v in 0 until vocabSize) { val x = logits[base + v]; if (x > bestV) { bestV = x; best = v } }
            if (best != prev && best != blankId) {
                val tok = idToToken.getOrNull(best)
                if (tok != null && !(tok.startsWith("[") && tok.endsWith("]")) && tok != "<s>" && tok != "</s>") {
                    sb.append(if (tok == wordDelimiter) " " else tok)
                }
            }
            prev = best
        }
        return sb.toString().replace(Regex("\\s+"), " ").trim()
    }
}
