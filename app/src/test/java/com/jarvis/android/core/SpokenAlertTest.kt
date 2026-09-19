package com.jarvis.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpokenAlertTest {
    @Test
    fun `reminder text prefixes rappel`() {
        assertEquals("Rappel : appeler maman", SpokenAlert.reminderSpokenText("appeler maman"))
    }

    @Test
    fun `reminder text already starting with rappel is kept as is`() {
        assertEquals("Rappel", SpokenAlert.reminderSpokenText("Rappel"))
        assertEquals("rappel dentiste 15h", SpokenAlert.reminderSpokenText("  rappel dentiste 15h  "))
    }

    @Test
    fun `timer text announces end`() {
        assertEquals("Minuteur terminé : pâtes", SpokenAlert.timerSpokenText("pâtes"))
    }

    @Test
    fun `timer label already starting with minuteur is kept as is`() {
        assertEquals("Minuteur", SpokenAlert.timerSpokenText("Minuteur"))
    }

    @Test
    fun `session announcement quotes the alert`() {
        assertEquals(
            "Annonce immédiate à l’utilisateur, à voix haute : « Rappel : appeler maman ».",
            SpokenAlert.sessionAnnouncement("Rappel : appeler maman"),
        )
    }

    @Test
    fun `language directive pins french and overrides`() {
        val directive = buildLanguageDirective()
        assertTrue(directive.contains("French"))
        assertTrue(directive.contains("overrides"))
        assertTrue(directive.contains("Spanish"))
        assertTrue(directive.contains("Android Auto"))
    }
}
