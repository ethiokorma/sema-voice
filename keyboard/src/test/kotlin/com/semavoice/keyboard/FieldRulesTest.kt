package com.semavoice.keyboard

import android.text.InputType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FieldRulesTest {
    @Test fun passwordFieldsAreSecure() {
        assertTrue(FieldRules.isSecure(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD))
        assertTrue(FieldRules.isSecure(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD))
        assertTrue(FieldRules.isSecure(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD))
        assertTrue(FieldRules.isSecure(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD))
    }

    @Test fun ordinaryFieldsAreNotSecure() {
        assertFalse(FieldRules.isSecure(InputType.TYPE_CLASS_TEXT))
        assertFalse(FieldRules.isSecure(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS))
        assertFalse(FieldRules.isSecure(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE))
        assertFalse(FieldRules.isSecure(InputType.TYPE_CLASS_NUMBER))
    }

    @Test fun noFieldWhenTypeNull() {
        assertFalse(FieldRules.hasEditableField(InputType.TYPE_NULL))
        assertTrue(FieldRules.hasEditableField(InputType.TYPE_CLASS_TEXT))
    }

    @Test fun numericDetection() {
        assertTrue(FieldRules.isNumeric(InputType.TYPE_CLASS_PHONE))
        assertTrue(FieldRules.isNumeric(InputType.TYPE_CLASS_NUMBER))
        assertFalse(FieldRules.isNumeric(InputType.TYPE_CLASS_TEXT))
    }

    @Test fun insertionSpacing() {
        assertEquals("hello", FieldRules.joinWithContext("", "hello"))
        assertEquals(" hello", FieldRules.joinWithContext("Hi there", "hello"))
        assertEquals("hello", FieldRules.joinWithContext("Hi there ", "hello"))
        assertEquals("hello", FieldRules.joinWithContext("(", "hello"))
        assertEquals(".", FieldRules.joinWithContext("Hi there", "."))
        assertEquals(" ሰላም", FieldRules.joinWithContext("ነገ", "ሰላም"))
        assertEquals("።", FieldRules.joinWithContext("ነገ", "።"))
    }
}
