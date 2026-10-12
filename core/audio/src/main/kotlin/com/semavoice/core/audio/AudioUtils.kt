package com.semavoice.core.audio

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Linear-interpolation resampler. Good enough for 44.1/48 kHz -> 16 kHz speech. */
object Resampler {
    fun resample(input: FloatArray, fromRate: Int, toRate: Int): FloatArray {
        if (fromRate == toRate || input.isEmpty()) return input
        val ratio = fromRate.toDouble() / toRate
        val outLen = (input.size / ratio).toInt()
        val out = FloatArray(outLen)
        for (i in 0 until outLen) {
            val pos = i * ratio
            val idx = pos.toInt()
            val frac = (pos - idx).toFloat()
            val a = input[idx]
            val b = if (idx + 1 < input.size) input[idx + 1] else a
            out[i] = a + (b - a) * frac
        }
        return out
    }
}

object PcmConversions {
    fun shortsToFloats(src: ShortArray, count: Int, gain: Float = 1f): FloatArray {
        val out = FloatArray(count)
        for (i in 0 until count) out[i] = (src[i] / 32768f * gain).coerceIn(-1f, 1f)
        return out
    }

    fun floatsToShorts(src: FloatArray): ShortArray {
        val out = ShortArray(src.size)
        for (i in src.indices) out[i] = (src[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
        return out
    }
}

/** Minimal 16-bit PCM WAV reader/writer used for voice notes and test fixtures. */
object WavCodec {
    fun write(file: File, samples: FloatArray, sampleRate: Int) {
        val pcm = PcmConversions.floatsToShorts(samples)
        val dataLen = pcm.size * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(36 + dataLen).put("WAVE".toByteArray())
        header.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
        header.putInt(sampleRate).putInt(sampleRate * 2).putShort(2).putShort(16)
        header.put("data".toByteArray()).putInt(dataLen)
        val body = ByteBuffer.allocate(dataLen).order(ByteOrder.LITTLE_ENDIAN)
        for (s in pcm) body.putShort(s)
        file.outputStream().use { it.write(header.array()); it.write(body.array()) }
    }

    data class Wav(val samples: FloatArray, val sampleRate: Int)

    fun read(file: File): Wav {
        RandomAccessFile(file, "r").use { raf ->
            val head = ByteArray(12)
            raf.readFully(head)
            require(String(head, 0, 4) == "RIFF" && String(head, 8, 4) == "WAVE") { "Not a WAV file" }
            var sampleRate = 16000
            var channels = 1
            var bits = 16
            while (true) {
                val chunk = ByteArray(8)
                if (raf.read(chunk) < 8) error("WAV without data chunk")
                val id = String(chunk, 0, 4)
                val size = ByteBuffer.wrap(chunk, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
                if (id == "fmt ") {
                    val fmt = ByteArray(size)
                    raf.readFully(fmt)
                    val bb = ByteBuffer.wrap(fmt).order(ByteOrder.LITTLE_ENDIAN)
                    bb.short // format
                    channels = bb.short.toInt()
                    sampleRate = bb.int
                    bb.int; bb.short
                    bits = bb.short.toInt()
                } else if (id == "data") {
                    val data = ByteArray(size)
                    raf.readFully(data)
                    val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
                    val frameCount = size / (channels * bits / 8)
                    val out = FloatArray(frameCount)
                    for (i in 0 until frameCount) {
                        var acc = 0f
                        for (c in 0 until channels) {
                            acc += when (bits) {
                                16 -> bb.short / 32768f
                                32 -> bb.float
                                8 -> (bb.get().toInt() and 0xFF) / 128f - 1f
                                else -> error("Unsupported WAV bit depth $bits")
                            }
                        }
                        out[i] = acc / channels
                    }
                    return Wav(out, sampleRate)
                } else {
                    raf.seek(raf.filePointer + size + (size and 1))
                }
            }
        }
    }
}
