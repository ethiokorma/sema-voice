package com.semavoice.core.translation

import java.text.Normalizer

/**
 * Pure-Kotlin SentencePiece encoder/decoder supporting both model types used by the
 * bundled translators: unigram (Marian / OPUS-MT) and BPE (M2M100).
 *
 * Normalization implements the parts of "nmt_nfkc" that matter for text typed or dictated
 * by users: NFKC, control-character removal, whitespace unification, dummy prefix and the
 * U+2581 whitespace escape. The precompiled charsmap in the model is NOT replayed; for
 * Ge'ez, Latin and Arabic text the difference is limited to a few exotic compatibility
 * characters. docs/MODEL_COMPATIBILITY.md records this deviation.
 */
class SentencePieceTokenizer(val model: SentencePieceModel) {
    companion object {
        const val SPACE_PIECE = '▁'
        private val controlChars = Regex("[\\p{Cc}\\p{Cf}&&[^\\n\\t]]")
        private val wsRun = Regex("[\\s\\u00A0\\u2000-\\u200A\\u202F\\u205F\\u3000]+")
    }

    fun normalize(text: String): String {
        var t = Normalizer.normalize(text, Normalizer.Form.NFKC)
        t = t.replace(controlChars, "")
        t = t.replace(wsRun, " ")
        if (model.removeExtraWhitespaces) t = t.trim()
        if (t.isEmpty()) return t
        if (model.addDummyPrefix) t = " $t"
        if (model.escapeWhitespaces) t = t.replace(' ', SPACE_PIECE)
        return t
    }

    /** Encodes to piece strings (the ids inside the .spm are NOT what the ONNX models expect). */
    fun encodeToPieces(text: String): List<String> {
        val n = normalize(text)
        if (n.isEmpty()) return emptyList()
        return when (model.modelType) {
            SentencePieceModel.ModelType.UNIGRAM -> unigramEncode(n)
            SentencePieceModel.ModelType.BPE -> bpeEncode(n)
        }
    }

    fun decodePieces(pieces: List<String>): String {
        val sb = StringBuilder()
        for (p in pieces) {
            val piece = model.pieceToId[p]?.let { model.pieces[it] }
            if (piece != null && (piece.type == SentencePieceModel.PieceType.CONTROL || piece.type == SentencePieceModel.PieceType.UNKNOWN)) {
                if (piece.type == SentencePieceModel.PieceType.UNKNOWN) sb.append(" ⁇ ")
                continue
            }
            sb.append(p)
        }
        return sb.toString().replace(SPACE_PIECE, ' ').trim().replace(Regex(" +"), " ")
    }

    // ---- unigram: Viterbi over the lattice of known pieces ----------------------------

    private fun unigramEncode(text: String): List<String> {
        val cps = text.codePoints().toArray()
        val n = cps.size
        val charOffsets = IntArray(n + 1)
        val sb = StringBuilder()
        for (i in 0 until n) { charOffsets[i] = sb.length; sb.appendCodePoint(cps[i]) }
        charOffsets[n] = sb.length
        val s = sb.toString()
        val unkPenalty = model.minScore - 10f
        val best = FloatArray(n + 1) { Float.NEGATIVE_INFINITY }
        val backPiece = arrayOfNulls<String>(n + 1)
        val backFrom = IntArray(n + 1)
        best[0] = 0f
        val maxPieceChars = 24
        for (i in 0 until n) {
            if (best[i] == Float.NEGATIVE_INFINITY) continue
            var matched = false
            for (j in i + 1..minOf(n, i + maxPieceChars)) {
                val sub = s.substring(charOffsets[i], charOffsets[j])
                val id = model.pieceToId[sub] ?: continue
                val piece = model.pieces[id]
                if (piece.type == SentencePieceModel.PieceType.CONTROL || piece.type == SentencePieceModel.PieceType.UNKNOWN) continue
                matched = true
                val score = best[i] + piece.score
                if (score > best[j]) { best[j] = score; backPiece[j] = sub; backFrom[j] = i }
            }
            if (!matched || best[i + 1] == Float.NEGATIVE_INFINITY) {
                // single unknown code point
                val sub = s.substring(charOffsets[i], charOffsets[i + 1])
                val score = best[i] + unkPenalty
                if (score > best[i + 1]) { best[i + 1] = score; backPiece[i + 1] = sub; backFrom[i + 1] = i }
            }
        }
        val out = ArrayList<String>()
        var pos = n
        while (pos > 0) { out.add(backPiece[pos]!!); pos = backFrom[pos] }
        out.reverse()
        return mergeUnknownRuns(out)
    }

    /**
     * SentencePiece reports a run of consecutive unknown characters as ONE <unk> piece whose
     * surface is the raw text (e.g. Ge'ez text against an English-only vocab). Callers map the
     * surface to the unk id, so one run becomes one unk token exactly like the reference.
     */
    private fun mergeUnknownRuns(pieces: List<String>): List<String> {
        val out = ArrayList<String>(pieces.size)
        val run = StringBuilder()
        for (p in pieces) {
            if (model.pieceToId.containsKey(p)) {
                if (run.isNotEmpty()) { out.add(run.toString()); run.setLength(0) }
                out.add(p)
            } else run.append(p)
        }
        if (run.isNotEmpty()) out.add(run.toString())
        return out
    }

    // ---- BPE: greedy merge by best piece score (score == -merge rank) -------------------

    private fun bpeEncode(text: String): List<String> {
        val symbols = ArrayList<String>()
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val len = Character.charCount(cp)
            symbols.add(text.substring(i, i + len))
            i += len
        }
        if (symbols.size <= 1) return symbols
        while (true) {
            var bestScore = Float.NEGATIVE_INFINITY
            var bestIdx = -1
            for (k in 0 until symbols.size - 1) {
                val merged = symbols[k] + symbols[k + 1]
                val id = model.pieceToId[merged] ?: continue
                val p = model.pieces[id]
                if (p.type != SentencePieceModel.PieceType.NORMAL) continue
                if (p.score > bestScore) { bestScore = p.score; bestIdx = k }
            }
            if (bestIdx < 0) break
            symbols[bestIdx] = symbols[bestIdx] + symbols[bestIdx + 1]
            symbols.removeAt(bestIdx + 1)
        }
        return symbols
    }
}
