package com.semavoice.core.writing

import com.semavoice.core.common.Language
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WritingAssistantTest {
    private val engine = RuleBasedWritingAssistant()

    @Test fun styleRewritesAreHonestlyUnavailable() {
        assertEquals(Availability.UNAVAILABLE, engine.availability(WritingAction.MAKE_PROFESSIONAL, Language.ENGLISH))
        assertEquals(Availability.UNAVAILABLE, engine.availability(WritingAction.MAKE_LONGER, Language.AMHARIC))
        assertEquals(Availability.RULE_BASED, engine.availability(WritingAction.IMPROVE_PUNCTUATION, Language.AMHARIC))
    }

    @Test fun unavailableActionFailsInsteadOfPretending() = runBlocking {
        val r = engine.apply(WritingAction.MAKE_FRIENDLY, "hello", Language.ENGLISH)
        assertFalse(r.isSuccess)
    }

    @Test fun removesEnglishFillers() {
        assertEquals("I will call you tomorrow", engine.removeDisfluencies("um I will uh call you, like, tomorrow", Language.ENGLISH))
    }

    @Test fun removesAmharicFillers() {
        assertEquals("ነገ እንገናኝ", engine.removeDisfluencies("እ ነገ እም እንገናኝ", Language.AMHARIC))
    }

    @Test fun collapsesRepeatedLatinWords() {
        assertEquals("I want the car", engine.removeRepeatedWords("I want the the the car"))
    }

    @Test fun keepsAmharicReduplicationButCollapsesTriples() {
        assertEquals("ቀስ ቀስ ሂድ", engine.removeRepeatedWords("ቀስ ቀስ ሂድ"))
        assertEquals("ነገ እንገናኝ", engine.removeRepeatedWords("ነገ ነገ ነገ እንገናኝ"))
    }

    @Test fun punctuationEnglish() {
        assertEquals("Hello there. How are you? Fine.", engine.improvePunctuation("hello there . how are you ?fine"))
    }

    @Test fun punctuationAmharicUsesEthiopicMarks() {
        assertEquals("እንዴት ነህ፧ ነገ እንገናኝ።", engine.improvePunctuation("እንዴት ነህ? ነገ እንገናኝ"))
    }

    @Test fun spellingFixesKeepCase() {
        assertEquals("I don't know, Tomorrow I'm busy", engine.correctCommonSpelling("i dont know, Tommorow im busy", Language.ENGLISH))
    }

    @Test fun shorterRemovesRedundantPhrases() {
        assertEquals("We need to leave now.", engine.makeShorter("um we basically need to leave at this point in time", Language.ENGLISH))
    }
}

class DictionaryPostProcessorTest {
    private val dict = DictionaryPostProcessor(
        listOf(
            DictionaryPostProcessor.Term("Tewodros", listOf("Theodoros", "Tedros")),
            DictionaryPostProcessor.Term("Addis Ababa", listOf("Adis Abeba")),
            DictionaryPostProcessor.Term("ቦሌ", listOf("ቦሊ")),
            DictionaryPostProcessor.Term("Lemlem", emptyList()),
        ),
    )

    @Test fun exactVariantIsAutoApplied() {
        val r = dict.process("Call Tedros at ቦሊ tomorrow")
        assertEquals("Call Tewodros at ቦሌ tomorrow", r.text)
        assertEquals(2, r.applied.size)
        assertTrue(r.applied.all { it.auto })
    }

    @Test fun fuzzyMatchIsOnlySuggested() {
        val r = dict.process("Meet Lemlam today")
        assertEquals("Meet Lemlam today", r.text)
        assertEquals(1, r.suggestions.size)
        assertEquals("Lemlem", r.suggestions[0].to)
        assertFalse(r.suggestions[0].auto)
    }

    @Test fun noCrossScriptFuzzyMatches() {
        val r = dict.process("ለምለም")
        assertTrue(r.suggestions.isEmpty())
    }

    @Test fun levenshteinWithBudget() {
        assertEquals(1, DictionaryPostProcessor.levenshtein("kitten", "sitten", 2))
        assertEquals(3, DictionaryPostProcessor.levenshtein("abc", "xyz", 2)) // budget exceeded -> max+1
    }
}
