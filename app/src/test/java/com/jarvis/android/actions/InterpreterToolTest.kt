package com.jarvis.android.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InterpreterToolTest {
    @Test
    fun `languages are named however they are said`() {
        assertEquals("anglais", languageName("English"))
        assertEquals("espagnol", languageName("l'espagnol"))
        assertEquals("français", languageName("Français"))
        assertEquals("swahili", languageName("swahili")) // unknown to the table: passed through, the model knows it
    }

    @Test
    fun `the rules name both languages, forbid answering or commenting, and say how to stop`() {
        val rules = interpreterRules("français", "anglais")
        assertTrue(rules.contains("en français, tu la répètes à voix haute en anglais"))
        assertTrue(rules.contains("en anglais, tu la répètes en français"))
        assertTrue(rules.contains("ne réponds pas toi-même"))
        assertTrue(rules.contains("action=stop"))
        assertTrue(rules.contains("passe avant la règle LANGUAGE"))
    }
}
