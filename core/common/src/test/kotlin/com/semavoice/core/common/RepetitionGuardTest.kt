package com.semavoice.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RepetitionGuardTest {
    @Test fun cleanTextIsUntouched() {
        val r = RepetitionGuard.check("በሴቶች ቡድን ውስጥ አራት የበረዶ ተንሸራታቾች", audioMs = 5000)
        assertFalse(r.trimmed); assertFalse(r.needsReview)
        assertEquals("በሴቶች ቡድን ውስጥ አራት የበረዶ ተንሸራታቾች", r.text)
    }

    @Test fun reduplicationIsLegitimate() {
        val r = RepetitionGuard.check("ቀስ ቀስ ሂድ")
        assertFalse(r.loopDetected); assertEquals("ቀስ ቀስ ሂድ", r.text)
    }

    @Test fun trimsLoopingPhraseTail() {
        val r = RepetitionGuard.check("የቤተሰብ የባህር ዳርቻዎች ናቻው ዳርቻዎች ናቻው ዳርቻዎች ናቻው ዳርቻዎች ናቻው")
        assertTrue(r.loopDetected); assertTrue(r.trimmed); assertTrue(r.needsReview)
        assertEquals("የቤተሰብ የባህር ዳርቻዎች ናቻው", r.text)
        assertEquals(6, r.removedWords)
    }

    @Test fun trimsSingleWordLoop() {
        val r = RepetitionGuard.check("ነገ እንገናኝ እንገናኝ እንገናኝ እንገናኝ እንገናኝ")
        assertEquals("ነገ እንገናኝ", r.text)
    }

    @Test fun internalLoopIsReportedNotRemoved() {
        val t = "ሀ ለ ሀ ለ ሀ ለ መጨረሻ ቃል"
        val r = RepetitionGuard.check(t)
        assertTrue(r.loopDetected); assertEquals(t, r.text)
    }

    @Test fun characterRunIsCollapsed() {
        val r = RepetitionGuard.check("ሰላም ልልልልልልልልልልል")
        assertTrue(r.loopDetected); assertEquals("ሰላም ልል", r.text)
    }

    @Test fun implausiblyLongForAudioIsFlagged() {
        val words = (1..40).joinToString(" ") { "ቃል$it" }
        val r = RepetitionGuard.check(words, audioMs = 2000)
        assertTrue(r.tooLongForAudio); assertTrue(r.needsReview)
    }

    @Test fun nearDuplicateRestartIsCut() {
        // Real tiny-model output pattern from FLEURS clip 001: second pass with spelling variations.
        val first = "እነዚህ አንዳንዴ የሚጨአነቁ የተለያዩ አይነት የባህር ዳርቻውን የከበቡ ሱቆች"
        val again = "እነዚህ አንዳንዴ የሚጨነቁ የትላዩ አይነት የባህር ዳርቻውን የከበቡ ሱቆች"
        val r = RepetitionGuard.check("$first $again", audioMs = 6000)
        assertEquals(first, r.text); assertTrue(r.loopDetected); assertTrue(r.needsReview)
    }

    @Test fun normalSentenceHasNoRestartCut() {
        val t = "በሴቶች ቡድን ውስጥ አራት የበረዶ ተንሸራታቾች ሩጫቸውን መጨረስ አልቻሉም በግዙፉ ስላሎም ካሉት"
        assertEquals(null, RepetitionGuard.restartCut(t.split(" ")))
    }
}
