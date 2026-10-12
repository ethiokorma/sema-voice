package com.semavoice.core.writing

import com.semavoice.core.common.TextNormalizer

/**
 * Applies the user's personal dictionary to recognized text (section 11).
 *
 * Two tiers:
 *  - Exact variant matches (a misrecognition the user explicitly recorded) are applied
 *    automatically and reported as [Correction.auto] so the UI can show what changed.
 *  - Fuzzy matches (edit distance within a small budget) are only *suggested*; the UI must
 *    ask the user before applying them. Nothing is retrained; this is post-processing.
 */
class DictionaryPostProcessor(private val terms: List<Term>) {
    data class Term(val term: String, val variants: List<String>)
    data class Correction(val from: String, val to: String, val startIndex: Int, val auto: Boolean)
    data class Result(val text: String, val applied: List<Correction>, val suggestions: List<Correction>)

    private val exact: Map<String, String> = buildMap {
        for (e in terms) for (v in e.variants) if (v.isNotBlank() && v != e.term) put(v.lowercase(), e.term)
    }

    fun process(text: String): Result {
        if (terms.isEmpty() || text.isBlank()) return Result(text, emptyList(), emptyList())
        val applied = ArrayList<Correction>()
        val suggestions = ArrayList<Correction>()
        val sb = StringBuilder()
        val tokenRegex = Regex("[\\p{L}\\p{M}\\p{N}'’]+|[^\\p{L}\\p{M}\\p{N}'’]+")
        for (m in tokenRegex.findAll(text)) {
            val tok = m.value
            if (!tok.first().isLetterOrDigit()) { sb.append(tok); continue }
            val key = tok.lowercase()
            val exactHit = exact[key]
            if (exactHit != null) {
                applied.add(Correction(tok, exactHit, sb.length, auto = true))
                sb.append(exactHit)
                continue
            }
            val fuzzy = fuzzyMatch(tok)
            if (fuzzy != null) suggestions.add(Correction(tok, fuzzy, sb.length, auto = false))
            sb.append(tok)
        }
        return Result(sb.toString(), applied, suggestions)
    }

    private fun fuzzyMatch(token: String): String? {
        if (token.length < 4) return null
        val budget = if (token.length <= 6) 1 else 2
        var best: String? = null
        var bestD = Int.MAX_VALUE
        for (e in terms) {
            if (e.term.equals(token, ignoreCase = true)) return null
            if (kotlin.math.abs(e.term.length - token.length) > budget) continue
            // Only compare within the same script to avoid Latin/Ge'ez nonsense matches.
            if (TextNormalizer.dominantScript(e.term) != TextNormalizer.dominantScript(token)) continue
            val d = levenshtein(token.lowercase(), e.term.lowercase(), budget)
            if (d in 1..budget && d < bestD) { bestD = d; best = e.term }
        }
        return best
    }

    companion object {
        fun levenshtein(a: String, b: String, max: Int): Int {
            if (a == b) return 0
            val prev = IntArray(b.length + 1) { it }
            val cur = IntArray(b.length + 1)
            for (i in 1..a.length) {
                cur[0] = i
                var rowMin = cur[0]
                for (j in 1..b.length) {
                    val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                    cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
                    rowMin = minOf(rowMin, cur[j])
                }
                if (rowMin > max) return max + 1
                System.arraycopy(cur, 0, prev, 0, cur.size)
            }
            return prev[b.length]
        }
    }
}
