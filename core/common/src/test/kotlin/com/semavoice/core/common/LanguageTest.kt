package com.semavoice.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguageTest {
    @Test fun fromCodeIsCaseInsensitiveAndFallsBackToAuto() {
        assertEquals(Language.AMHARIC, Language.fromCode("AM"))
        assertEquals(Language.AUTO, Language.fromCode("zz"))
        assertEquals(Language.AUTO, Language.fromCode(null))
    }

    @Test fun tigrinyaAndOromoAreNotWhisperLanguages() {
        assertFalse(Language.TIGRINYA.supportsWhisper)
        assertFalse(Language.AFAAN_OROMOO.supportsWhisper)
        assertTrue(Language.AMHARIC.supportsWhisper)
        assertTrue(Language.ENGLISH.supportsWhisper)
    }

    @Test fun inputCandidatesOnlyContainWhisperLanguagesOrAuto() {
        Language.inputCandidates.filter { it != Language.AUTO }.forEach { assertTrue(it.supportsWhisper) }
    }

    @Test fun outcomeMapsAndCatches() {
        val ok = Outcome.runCatchingApp { 2 }.map { it * 2 }
        assertEquals(4, ok.getOrNull())
        val bad = Outcome.runCatchingApp<Int> { throw AppError.EmptyRecording() }
        assertTrue(bad.errorOrNull() is AppError.EmptyRecording)
    }
}
