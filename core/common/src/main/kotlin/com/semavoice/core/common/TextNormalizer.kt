package com.semavoice.core.common

import java.text.Normalizer

/**
 * Deterministic text utilities shared by speech post-processing, translation and the
 * writing assistant. Everything here is rule based and documented; nothing invents words.
 */
object TextNormalizer {
    /** Ethiopic punctuation: ። full stop, ፣ comma, ፤ semicolon, ፥ colon, ፧ question mark. */
    const val ETHIOPIC_FULL_STOP = '።'
    const val ETHIOPIC_COMMA = '፣'
    const val ETHIOPIC_SEMICOLON = '፤'
    const val ETHIOPIC_COLON = '፥'
    const val ETHIOPIC_QUESTION = '፧'
    const val ETHIOPIC_WORDSPACE = '፡'

    private val geezRange = 'ሀ'..'፿'
    private val geezSupplement = 'ᎀ'..'᎟'
    private val geezExtended = 'ⶀ'..'⷟'
    private val arabicRange = '؀'..'ۿ'

    fun isGeez(c: Char) = c in geezRange || c in geezSupplement || c in geezExtended
    fun isArabic(c: Char) = c in arabicRange

    /** NFC normalization + whitespace collapse. Safe for all scripts. */
    fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFC)
            .replace(ETHIOPIC_WORDSPACE, ' ')
            .replace(Regex("[ \\t\\u00A0]+"), " ")
            .replace(Regex(" *\\n *"), "\n")
            .trim()

    /** Rough script share of a string; used for mixed-language detection and display. */
    fun scriptProfile(text: String): ScriptProfile {
        var geez = 0
        var latin = 0
        var arabic = 0
        for (c in text) {
            when {
                isGeez(c) -> geez++
                isArabic(c) -> arabic++
                c.isLetter() -> latin++
            }
        }
        return ScriptProfile(geez, latin, arabic)
    }

    fun dominantScript(text: String): Script? {
        val p = scriptProfile(text)
        if (p.total == 0) return null
        return when (maxOf(p.geez, p.latin, p.arabic)) {
            p.geez -> Script.GEEZ
            p.arabic -> Script.ARABIC
            else -> Script.LATIN
        }
    }

    fun isMixedScript(text: String, threshold: Float = 0.15f): Boolean {
        val p = scriptProfile(text)
        if (p.total < 4) return false
        val shares = listOf(p.geez, p.latin, p.arabic).map { it.toFloat() / p.total }
        return shares.count { it >= threshold } >= 2
    }

    /**
     * Normalizes sentence punctuation to the convention of the script immediately
     * preceding the mark. Latin "." after Ge'ez text becomes "።", and the reverse.
     */
    fun localizePunctuation(text: String): String {
        val sb = StringBuilder(text.length)
        var lastScript: Script? = null
        for (c in text) {
            if (c.isLetter()) {
                lastScript = when {
                    isGeez(c) -> Script.GEEZ
                    isArabic(c) -> Script.ARABIC
                    else -> Script.LATIN
                }
            }
            val mapped = when (lastScript) {
                Script.GEEZ -> when (c) {
                    '.' -> ETHIOPIC_FULL_STOP
                    ',' -> ETHIOPIC_COMMA
                    ';' -> ETHIOPIC_SEMICOLON
                    ':' -> ETHIOPIC_COLON
                    '?' -> ETHIOPIC_QUESTION
                    else -> c
                }
                Script.LATIN -> when (c) {
                    ETHIOPIC_FULL_STOP -> '.'
                    ETHIOPIC_COMMA -> ','
                    ETHIOPIC_SEMICOLON -> ';'
                    ETHIOPIC_COLON -> ':'
                    ETHIOPIC_QUESTION -> '?'
                    else -> c
                }
                else -> c
            }
            sb.append(mapped)
        }
        return sb.toString()
    }

    data class ScriptProfile(val geez: Int, val latin: Int, val arabic: Int) {
        val total get() = geez + latin + arabic
    }
}
