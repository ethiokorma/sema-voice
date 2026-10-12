package com.semavoice.keyboard

/**
 * Phonetic (SERA-style) Ge'ez composer for the QWERTY fallback. Latin keystrokes are turned
 * into Ethiopic syllables as you type: `s` → ስ, then `e` → ሰ, `a` → ሳ; `sh` → ሽ; `h` + `u` → ሁ.
 *
 * Each Ethiopic consonant family occupies 8 code points in vowel order
 * ä, u, i, a, ē, ə (bare), o, (wa). We commit the bare 6th form immediately and *replace* it
 * when a vowel follows, so the user always sees something. Pure Kotlin, unit-tested.
 */
class GeezComposer {
    /** What the keyboard must do: delete [deleteBefore] chars, then commit [insert]. */
    data class Edit(val deleteBefore: Int, val insert: String)

    private var pendingFamily: Int? = null   // base code point of the consonant awaiting a vowel
    private var pendingKey: Char? = null      // the Latin key that produced it (for digraphs)
    private var lastInsertLength = 0

    fun reset() { pendingFamily = null; pendingKey = null; lastInsertLength = 0 }

    fun type(key: Char): Edit {
        // Digraphs: s+h, c+h, k+h, z+h, n+y, t+s
        val digraph = pendingKey?.let { p -> digraphs[p to key] }
        if (digraph != null) {
            pendingFamily = digraph; pendingKey = null
            return replace(syllable(digraph, VOWEL_BARE))
        }
        vowelOffset(key)?.let { v ->
            val fam = pendingFamily
            pendingFamily = null; pendingKey = null
            return if (fam != null) replace(syllable(fam, v)) else insert(syllable(GLOTTAL, v))
        }
        val fam = consonants[key]
        if (fam != null) {
            pendingFamily = fam; pendingKey = key
            return insert(syllable(fam, VOWEL_BARE))
        }
        // Not a letter we map: pass through and stop composing.
        reset()
        return Edit(0, key.toString())
    }

    private fun replace(text: String): Edit { val d = lastInsertLength; lastInsertLength = text.length; return Edit(d, text) }
    private fun insert(text: String): Edit { lastInsertLength = text.length; return Edit(0, text) }

    private fun syllable(family: Int, vowel: Int): String = String(Character.toChars(family + vowel))

    private fun vowelOffset(c: Char): Int? = when (c) {
        'e' -> 0; 'u' -> 1; 'i' -> 2; 'a' -> 3; 'E' -> 4; 'o' -> 6; else -> null
    }

    companion object {
        private const val VOWEL_BARE = 5
        private const val GLOTTAL = 0x12A0 // አ family (standalone vowels)

        /** Latin key -> first code point of the Ethiopic consonant family. */
        val consonants: Map<Char, Int> = mapOf(
            'h' to 0x1200, 'l' to 0x1208, 'H' to 0x1210, 'm' to 0x1218, 's' to 0x1230, 'r' to 0x1228,
            'x' to 0x1238, 'q' to 0x1240, 'b' to 0x1260, 'v' to 0x1268, 't' to 0x1270, 'c' to 0x1278,
            'n' to 0x1290, 'N' to 0x1298, 'k' to 0x12A8, 'K' to 0x12B8, 'w' to 0x12C8, 'z' to 0x12D8,
            'Z' to 0x12E0, 'y' to 0x12E8, 'd' to 0x12F0, 'j' to 0x1300, 'g' to 0x1308, 'T' to 0x1320,
            'C' to 0x1328, 'P' to 0x1330, 'S' to 0x1338, 'f' to 0x1348, 'p' to 0x1350,
        )

        val digraphs: Map<Pair<Char, Char>, Int> = mapOf(
            ('s' to 'h') to 0x1238, // ሸ
            ('c' to 'h') to 0x1278, // ቸ
            ('k' to 'h') to 0x12B8, // ኸ
            ('z' to 'h') to 0x12E0, // ዠ
            ('n' to 'y') to 0x1298, // ኘ
            ('t' to 's') to 0x1338, // ጸ
        )
    }
}
