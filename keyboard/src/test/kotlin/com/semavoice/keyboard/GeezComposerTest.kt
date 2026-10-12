package com.semavoice.keyboard

import org.junit.Assert.assertEquals
import org.junit.Test

class GeezComposerTest {
    private fun run(input: String): String {
        val c = GeezComposer()
        val sb = StringBuilder()
        for (ch in input) {
            val e = c.type(ch)
            repeat(e.deleteBefore) { sb.deleteCharAt(sb.length - 1) }
            sb.append(e.insert)
        }
        return sb.toString()
    }

    @Test fun bareConsonantThenVowelReplaces() {
        assertEquals("ስ", run("s"))
        assertEquals("ሰ", run("se"))
        assertEquals("ሳ", run("sa"))
        assertEquals("ሱ", run("su"))
        assertEquals("ሶ", run("so"))
    }

    @Test fun wordSelam() {
        assertEquals("ሰላም", run("selam"))
    }

    @Test fun digraphSh() {
        assertEquals("ሽ", run("sh"))
        assertEquals("ሻ", run("sha"))
        assertEquals("ቻይ", run("chay"))
    }

    @Test fun standaloneVowelsUseGlottalFamily() {
        assertEquals("አ", run("e"))
        assertEquals("ኣ", run("a"))
        assertEquals("ኢ", run("i"))
    }

    @Test fun amharicWord() {
        assertEquals("አማርኛ", run("emarNa"))
    }

    @Test fun nonLettersPassThroughAndResetComposition() {
        assertEquals("ስ ሰ", run("s se"))
        assertEquals("ስ።", run("s።"))
    }
}
