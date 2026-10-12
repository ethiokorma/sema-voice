package com.semavoice.core.writing

import com.semavoice.core.common.Language
import com.semavoice.core.common.Outcome

/**
 * Writing actions from section 10. [Availability] is decided per engine so the UI can
 * disable or label actions honestly. Nothing here pretends to be a language model.
 */
enum class WritingAction {
    CORRECT_SPELLING, IMPROVE_PUNCTUATION, MAKE_PROFESSIONAL, MAKE_FRIENDLY, MAKE_SHORTER,
    MAKE_LONGER, SIMPLIFY, INFORMAL_TO_FORMAL, REMOVE_REPETITION, CLEAN_DISFLUENCIES, PRESERVE_MEANING
}

enum class Availability {
    /** Implemented with deterministic rules; safe to use. */
    RULE_BASED,
    /** Implemented by a local model (future). */
    LOCAL_MODEL,
    /** Not implemented; UI must disable it. */
    UNAVAILABLE,
}

data class WritingResult(val text: String, val changed: Boolean, val explanation: String)

interface WritingAssistantEngine {
    val name: String
    fun availability(action: WritingAction, language: Language): Availability
    suspend fun apply(action: WritingAction, text: String, language: Language): Outcome<WritingResult>
}
