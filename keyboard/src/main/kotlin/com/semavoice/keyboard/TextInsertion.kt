package com.semavoice.keyboard

import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection

/** Pure decisions about the focused field, unit-testable without Android. */
object FieldRules {
    /** Password / PIN / web-password fields never receive voice input (section 5). */
    fun isSecure(inputType: Int): Boolean {
        val cls = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return when (cls) {
            InputType.TYPE_CLASS_TEXT -> variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
    }

    fun isNumeric(inputType: Int): Boolean {
        val cls = inputType and InputType.TYPE_MASK_CLASS
        return cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE
    }

    /** No class bits at all means no editable field is focused (e.g. a browser chrome). */
    fun hasEditableField(inputType: Int): Boolean = (inputType and InputType.TYPE_MASK_CLASS) != InputType.TYPE_NULL

    /**
     * Decides spacing when inserting dictated text after existing text: adds a leading
     * space unless the cursor is at the start or already after whitespace / an opening bracket.
     */
    fun joinWithContext(textBefore: CharSequence?, insert: String): String {
        if (insert.isEmpty()) return insert
        val before = textBefore?.toString() ?: ""
        if (before.isEmpty()) return insert
        val last = before.last()
        val needsSpace = !last.isWhitespace() && last != '(' && last != '[' && last != '"' && last != '“' && !insert.first().isWhitespace() &&
            !insert.first().let { it == '.' || it == ',' || it == '!' || it == '?' || it == '።' || it == '፣' }
        return if (needsSpace) " $insert" else insert
    }

    /** Enter-key action label for the editor's IME action. */
    fun imeAction(editorInfo: EditorInfo?): Int = (editorInfo?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
}

/** Thin abstraction over InputConnection so the controller can be tested with a fake. */
interface TextSink {
    fun commit(text: String)
    fun deleteBackward(count: Int = 1)
    fun textBeforeCursor(n: Int): CharSequence?
    fun selectedText(): CharSequence?
    fun performEnter()
    fun performEditorAction(action: Int): Boolean
}

class InputConnectionSink(private val ic: InputConnection, private val editorInfo: EditorInfo?) : TextSink {
    override fun commit(text: String) { ic.commitText(text, 1) }
    override fun deleteBackward(count: Int) {
        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) ic.commitText("", 1) else ic.deleteSurroundingText(count, 0)
    }
    override fun textBeforeCursor(n: Int): CharSequence? = ic.getTextBeforeCursor(n, 0)
    override fun selectedText(): CharSequence? = ic.getSelectedText(0)
    override fun performEnter() {
        val action = FieldRules.imeAction(editorInfo)
        val multiline = (editorInfo?.inputType ?: 0) and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0
        if (!multiline && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            ic.performEditorAction(action)
        } else {
            ic.commitText("\n", 1)
        }
    }
    override fun performEditorAction(action: Int): Boolean = ic.performEditorAction(action)
}
