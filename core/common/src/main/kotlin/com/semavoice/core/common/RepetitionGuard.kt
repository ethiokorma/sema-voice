package com.semavoice.core.common

/**
 * Detects decoder repetition loops ("ዳርቻዎች ናቸው ዳርቻዎች ናቸው ዳርቻዎች ናቸው …") and other
 * pathological outputs, and trims only a clearly pathological *tail*.
 *
 * Rules (all deterministic, unit-tested):
 *  - A loop is the same n-gram (n = 1..6 words) repeated consecutively at least [minRepeats]
 *    times, OR a single character repeated [minCharRun]+ times (ልልልልልልል…).
 *  - Only the repeats beyond the first occurrence are removed, and only when the loop reaches
 *    the end of the text (a looping tail). Repetition in the middle is reported, never removed.
 *  - Output that is implausibly long for the audio duration is flagged.
 *
 * Legitimate Amharic reduplication ("ቀስ ቀስ") is two repeats and therefore never touched with
 * the default minRepeats = 3. The original text is always kept by callers for review/benchmarks.
 */
object RepetitionGuard {
    data class Result(
        val text: String,
        val original: String,
        val trimmed: Boolean,
        val loopDetected: Boolean,
        val tooLongForAudio: Boolean,
        val removedWords: Int,
    ) {
        /** True when the UI must not auto-insert and should ask the user to review/retry. */
        val needsReview: Boolean get() = loopDetected || tooLongForAudio
    }

    fun check(text: String, audioMs: Long = 0, minRepeats: Int = 3, minCharRun: Int = 8): Result {
        val original = text
        var words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        var removed = 0
        var loop = false

        // 0) restart: the decoder transcribed the same audio a second time with small variations.
        // Measured on FLEURS: removes 6/30 duplicated tails, WER 0.812 -> 0.618, no false cuts.
        restartCut(words)?.let { cut -> removed += words.size - cut; words = words.subList(0, cut); loop = true }

        // 1) collapse looping tails, longest n first so phrase loops win over word loops
        var changed = true
        while (changed && words.size >= minRepeats) {
            changed = false
            for (n in minOf(6, words.size / minRepeats) downTo 1) {
                val unit = words.takeLast(n)
                var reps = 1
                var end = words.size - n
                while (end - n >= 0 && words.subList(end - n, end) == unit) { reps++; end -= n }
                if (reps >= minRepeats) {
                    loop = true
                    val keep = end + n // keep first occurrence of the unit
                    removed += words.size - keep
                    words = words.subList(0, keep)
                    changed = true
                    break
                }
            }
        }
        // 2) loops in the middle: report only
        if (!loop) loop = hasInternalLoop(words, minRepeats)

        var out = words.joinToString(" ")
        // 3) character runs inside a word (e.g. "ልልልልልልልል")
        val run = Regex("(.)\\1{${minCharRun - 1},}")
        if (run.containsMatchIn(out)) { loop = true; out = out.replace(run) { it.value.take(2) } }

        // 4) plausibility: Ge'ez read speech is 4.6-9.3 characters/s (FLEURS); > 12/s is duplicated text
        val chars = out.count { !it.isWhitespace() }
        val tooLong = audioMs > 0 && chars > 20 && chars > audioMs / 1000.0 * 12.0

        return Result(out, original, out != original.trim(), loop, tooLong, removed)
    }

    /** Word index where the text starts over, or null. Same algorithm as tests/ai-quality/repetition_guard.py. */
    fun restartCut(words: List<String>, minSim: Double = 0.6): Int? {
        if (words.size < 6) return null
        var bestSim = 0.0
        var bestK: Int? = null
        for (k in maxOf(3, (words.size * 0.25).toInt())..(words.size * 0.75).toInt()) {
            val a = words.subList(0, k).joinToString(" ")
            val b = words.subList(k, words.size).joinToString(" ")
            val n = minOf(a.length, b.length)
            if (n == 0) continue
            val s = 1.0 - levenshtein(a.substring(0, n), b.substring(0, n)).toDouble() / n
            if (s > bestSim) { bestSim = s; bestK = k }
        }
        return if (bestSim >= minSim) bestK else null
    }

    private fun levenshtein(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            prev = cur
        }
        return prev[b.length]
    }

    private fun hasInternalLoop(words: List<String>, minRepeats: Int): Boolean {
        for (n in 1..6) {
            var i = 0
            while (i + n * minRepeats <= words.size) {
                val unit = words.subList(i, i + n)
                var reps = 1
                var j = i + n
                while (j + n <= words.size && words.subList(j, j + n) == unit) { reps++; j += n }
                if (reps >= minRepeats) return true
                i++
            }
        }
        return false
    }
}
