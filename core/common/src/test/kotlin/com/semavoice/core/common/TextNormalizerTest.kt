package com.semavoice.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextNormalizerTest {
    @Test fun collapsesWhitespaceAndTrims() {
        assertEquals("ሰላም ነው", TextNormalizer.normalize("  ሰላም   ነው \n"))
    }

    @Test fun ethiopicWordSpaceBecomesSpace() {
        assertEquals("ሰላም ነው", TextNormalizer.normalize("ሰላም፡ነው"))
    }

    @Test fun detectsGeezDominantScript() {
        assertEquals(Script.GEEZ, TextNormalizer.dominantScript("እንዴት ነህ"))
        assertEquals(Script.LATIN, TextNormalizer.dominantScript("hello there"))
    }

    @Test fun detectsMixedAmharicEnglish() {
        assertTrue(TextNormalizer.isMixedScript("ነገ meeting አለኝ ከ manager ጋር"))
        assertFalse(TextNormalizer.isMixedScript("ነገ እንገናኝ ብዬ ነበር"))
    }

    @Test fun localizesPunctuationAfterGeezText() {
        assertEquals("እንዴት ነህ፧ ነገ እንገናኝ።", TextNormalizer.localizePunctuation("እንዴት ነህ? ነገ እንገናኝ."))
    }

    @Test fun keepsLatinPunctuationAfterLatinText() {
        assertEquals("How are you? See you.", TextNormalizer.localizePunctuation("How are you? See you."))
    }

    @Test fun mixedSentenceKeepsPunctuationOfPrecedingScript() {
        assertEquals("ነገ meeting አለኝ።", TextNormalizer.localizePunctuation("ነገ meeting አለኝ."))
    }
}
