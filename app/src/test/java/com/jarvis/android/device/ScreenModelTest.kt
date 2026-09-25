package com.jarvis.android.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenModelTest {
    private fun el(index: Int, label: String, clickable: Boolean = true, password: Boolean = false, editable: Boolean = false) =
        ScreenElement(index, label, if (editable) "champ" else "bouton", clickable = clickable, password = password, editable = editable)

    @Test
    fun `labels are compared without case, accents or extra spaces`() {
        assertEquals("photos recentes", normalizeLabel("  Photos   RÉCENTES "))
        assertEquals("ete", normalizeLabel("Été"))
    }

    @Test
    fun `screen listing numbers elements and shows their traits`() {
        val text = formatScreen(
            "Photos", "com.google.android.apps.photos",
            listOf(el(0, "Recherche", editable = true), ScreenElement(1, "Photo du 12 sept.", "image", clickable = true)),
        )
        assertTrue(text.startsWith("Écran de Photos (com.google.android.apps.photos) :"))
        assertTrue(text.contains("[0] champ « Recherche » (cliquable, champ de saisie)"))
        assertTrue(text.contains("[1] image « Photo du 12 sept. » (cliquable)"))
    }

    @Test
    fun `password content is never listed`() {
        val text = formatScreen("App", "p", listOf(el(0, "hunter2", password = true, editable = true)))
        assertFalse(text.contains("hunter2"))
        assertTrue(text.contains("mot de passe"))
    }

    @Test
    fun `long lists are cut and long labels shortened`() {
        val many = (0 until MAX_SCREEN_ELEMENTS + 20).map { el(it, "x".repeat(200)) }
        val text = formatScreen("App", "p", many)
        assertTrue(text.contains("20 autres éléments"))
        assertFalse(text.contains("x".repeat(MAX_LABEL_CHARS + 1)))
    }

    @Test
    fun `an empty screen says so`() {
        assertTrue(formatScreen("App", "p", emptyList()).contains("aucun élément"))
    }

    // Le rapprochement des libellés a déménagé dans ScreenMatch.kt : ses tests sont dans ScreenMatchTest.

    @Test
    fun `buttons that send, pay, delete or grant access are sensitive`() {
        listOf("Envoyer", "Send", "Payer 12,99 €", "Acheter", "Supprimer la photo", "Delete", "Installer", "Autoriser", "J'accepte", "Appeler")
            .forEach { assertTrue(it, isSensitiveLabel(it)) }
    }

    @Test
    fun `ordinary labels are not sensitive`() {
        listOf("Photos", "Recherche", "Album Vacances", "Suivant", "Retour", "Postes", "", "Compost")
            .forEach { assertFalse(it, isSensitiveLabel(it)) }
    }

    @Test
    fun `taps in security screens always need approval`() {
        assertEquals("écran sensible (com.android.settings)", confirmationReason("com.android.settings", el(0, "Wi-Fi")))
        assertNull(confirmationReason("com.google.android.apps.photos", el(0, "Album")))
        assertEquals("action sensible", confirmationReason("com.whatsapp", el(0, "Envoyer")))
    }

    @Test
    fun `the notification shade cannot be tapped without approval`() {
        assertEquals(
            "écran sensible (com.android.systemui)",
            confirmationReason("com.android.systemui", el(0, "Confirmer")),
        )
    }

    @Test
    fun `directions accept both languages`() {
        assertEquals("left", parseDirection("Gauche"))
        assertEquals("down", parseDirection("DOWN"))
        assertNull(parseDirection("diagonale"))
    }

    @Test
    fun `scrolling down is a finger going up`() {
        assertEquals("up", swipeForScroll("down"))
        assertEquals("down", swipeForScroll("up"))
        assertEquals("right", swipeForScroll("left"))
        assertEquals("left", swipeForScroll("right"))
    }
}
