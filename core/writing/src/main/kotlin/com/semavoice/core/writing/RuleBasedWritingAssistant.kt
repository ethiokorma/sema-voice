package com.semavoice.core.writing

import com.semavoice.core.common.AppError
import com.semavoice.core.common.Language
import com.semavoice.core.common.Outcome
import com.semavoice.core.common.Script
import com.semavoice.core.common.TextNormalizer

/**
 * Deterministic text transformations. Every rule is listed in docs/ARCHITECTURE.md.
 * Style rewrites that need language understanding (professional, friendly, longer,
 * simplify, informal-to-formal) are reported UNAVAILABLE until a local model exists.
 */
class RuleBasedWritingAssistant : WritingAssistantEngine {
    override val name = "rules"

    override fun availability(action: WritingAction, language: Language): Availability = when (action) {
        WritingAction.IMPROVE_PUNCTUATION, WritingAction.REMOVE_REPETITION, WritingAction.CLEAN_DISFLUENCIES,
        WritingAction.PRESERVE_MEANING, WritingAction.MAKE_SHORTER -> Availability.RULE_BASED
        WritingAction.CORRECT_SPELLING -> if (language == Language.ENGLISH || language == Language.AMHARIC) Availability.RULE_BASED else Availability.UNAVAILABLE
        WritingAction.MAKE_PROFESSIONAL, WritingAction.MAKE_FRIENDLY, WritingAction.MAKE_LONGER,
        WritingAction.SIMPLIFY, WritingAction.INFORMAL_TO_FORMAL -> Availability.UNAVAILABLE
    }

    override suspend fun apply(action: WritingAction, text: String, language: Language): Outcome<WritingResult> {
        if (availability(action, language) == Availability.UNAVAILABLE) {
            return Outcome.Failure(AppError.Unknown(UnsupportedOperationException("${action.name} needs a local model")))
        }
        val out = when (action) {
            WritingAction.IMPROVE_PUNCTUATION -> improvePunctuation(text)
            WritingAction.REMOVE_REPETITION -> removeRepeatedWords(text)
            WritingAction.CLEAN_DISFLUENCIES -> removeDisfluencies(text, language)
            WritingAction.CORRECT_SPELLING -> correctCommonSpelling(text, language)
            WritingAction.MAKE_SHORTER -> makeShorter(text, language)
            WritingAction.PRESERVE_MEANING -> TextNormalizer.normalize(text)
            else -> text
        }
        return Outcome.Success(WritingResult(out, out != text, explanationFor(action)))
    }

    private fun explanationFor(a: WritingAction) = when (a) {
        WritingAction.IMPROVE_PUNCTUATION -> "Fixed spacing, capitalization and sentence-final punctuation."
        WritingAction.REMOVE_REPETITION -> "Removed immediately repeated words."
        WritingAction.CLEAN_DISFLUENCIES -> "Removed fillers such as 'um', 'uh', 'እ...'."
        WritingAction.CORRECT_SPELLING -> "Applied common spelling fixes."
        WritingAction.MAKE_SHORTER -> "Removed fillers, repeats and redundant phrases."
        else -> "Normalized text."
    }

    // ---- rules ----------------------------------------------------------------------

    private val englishFillers = listOf("um", "uh", "uhm", "erm", "hmm", "like,", "you know,", "i mean,")
    private val amharicFillers = listOf("እ", "እም", "ኧ", "ኣ")

    fun removeDisfluencies(text: String, language: Language): String {
        var t = TextNormalizer.normalize(text)
        val fillers = when (language) {
            Language.AMHARIC -> amharicFillers + englishFillers
            Language.AUTO -> amharicFillers + englishFillers
            else -> englishFillers
        }
        for (f in fillers) {
            val pattern = Regex("(?:,\\s*)?(?<![\\p{L}\\p{M}])" + Regex.escape(f) + "(?![\\p{L}\\p{M}])[,\\s]*", RegexOption.IGNORE_CASE)
            t = t.replace(pattern, " ")
        }
        return TextNormalizer.normalize(t.replace(Regex("\\s+([,.;:?!።፣፤፥፧])"), "$1"))
    }

    fun removeRepeatedWords(text: String): String {
        // "the the", "ነገ ነገ" -> single. Keep legitimate doubles like "ቀስ ቀስ"? That is a known
        // Amharic reduplication, so we only collapse 3+ repeats for Ge'ez words and 2+ for Latin.
        val latin = Regex("\\b(\\p{IsLatin}+)(\\s+\\1\\b)+", RegexOption.IGNORE_CASE)
        val geez = Regex("((?<![\\p{L}\\p{M}])[\\p{IsEthiopic}]+)(\\s+\\1(?![\\p{L}\\p{M}])){2,}")
        return TextNormalizer.normalize(text.replace(latin, "$1").replace(geez, "$1"))
    }

    fun improvePunctuation(text: String): String {
        var t = TextNormalizer.normalize(text)
        if (t.isEmpty()) return t
        t = t.replace(Regex("\\s+([,.;:?!።፣፤፥፧])"), "$1")
        t = t.replace(Regex("([,.;:?!።፣፤፥፧])(?=[\\p{L}\\p{M}])"), "$1 ")
        t = t.replace(Regex("([.!?።፧]){2,}")) { it.groupValues[1] }
        t = TextNormalizer.localizePunctuation(t)
        // Capitalize sentence starts for Latin text only.
        val sb = StringBuilder(t.length)
        var capitalizeNext = true
        for (c in t) {
            val out = if (capitalizeNext && c.isLetter() && !TextNormalizer.isGeez(c) && !TextNormalizer.isArabic(c)) c.uppercaseChar() else c
            if (c.isLetter()) capitalizeNext = false
            if (c == '.' || c == '!' || c == '?' || c == '\n' || c == TextNormalizer.ETHIOPIC_FULL_STOP || c == TextNormalizer.ETHIOPIC_QUESTION) capitalizeNext = true
            sb.append(out)
        }
        t = sb.toString()
        val last = t.last()
        if (last.isLetterOrDigit()) {
            t += if (TextNormalizer.dominantScript(t) == Script.GEEZ) TextNormalizer.ETHIOPIC_FULL_STOP else '.'
        }
        return t
    }

    private val englishFixes = mapOf(
        "i" to "I", "im" to "I'm", "dont" to "don't", "cant" to "can't", "wont" to "won't", "didnt" to "didn't",
        "isnt" to "isn't", "doesnt" to "doesn't", "ive" to "I've", "id" to "I'd", "ill" to "I'll", "thats" to "that's",
        "whats" to "what's", "lets" to "let's", "teh" to "the", "recieve" to "receive", "tommorow" to "tomorrow",
        "tomorow" to "tomorrow", "wich" to "which", "alot" to "a lot", "occured" to "occurred", "definately" to "definitely",
    )

    /** Amharic: normalize common homophone variants to the standard orthography. */
    private val amharicFixes = mapOf(
        "ሀገር" to "ሀገር", "ኣማርኛ" to "አማርኛ", "ሠላም" to "ሰላም", "ዐማርኛ" to "አማርኛ", "ዓለም" to "ዓለም",
    )

    fun correctCommonSpelling(text: String, language: Language): String {
        val fixes = when (language) { Language.ENGLISH -> englishFixes; Language.AMHARIC -> amharicFixes; else -> englishFixes + amharicFixes }
        return text.split(Regex("(?<=\\s)|(?=\\s)")).joinToString("") { token ->
            val core = token.trim { !it.isLetterOrDigit() }
            if (core.isEmpty()) token else {
                val fix = fixes[core.lowercase()] ?: fixes[core]
                if (fix == null) token else {
                    val keepCase = if (core.first().isUpperCase() && fix.first().isLowerCase()) fix.replaceFirstChar { it.uppercaseChar() } else fix
                    token.replace(core, keepCase)
                }
            }
        }
    }

    fun makeShorter(text: String, language: Language): String {
        var t = removeDisfluencies(text, language)
        t = removeRepeatedWords(t)
        val redundant = listOf("in order to" to "to", "at this point in time" to "now", "due to the fact that" to "because", "basically " to "", "actually " to "", "really " to "")
        for ((a, b) in redundant) t = t.replace(Regex("(?i)\\b" + Regex.escape(a)), b)
        return improvePunctuation(TextNormalizer.normalize(t))
    }
}
