package com.jarvis.android.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Retrouver le bon élément à partir du texte que le modèle annonce. Chaque échec ici coûte un aller-retour
 * visible en conversation vocale : le modèle relit l'écran, réessaie, et l'utilisateur attend.
 */
class ScreenMatchTest {

    private fun el(
        index: Int,
        label: String,
        clickable: Boolean = true,
        password: Boolean = false,
        editable: Boolean = false,
        left: Int = 0,
        top: Int = 0,
        right: Int = 0,
        bottom: Int = 0,
    ) = ScreenElement(
        index, label, if (editable) "champ" else "bouton",
        clickable = clickable, password = password, editable = editable,
        left = left, top = top, right = right, bottom = bottom,
    )

    private fun found(match: ElementMatch): ScreenElement {
        assertTrue("attendu Found, reçu $match", match is ElementMatch.Found)
        return (match as ElementMatch.Found).element
    }

    @Test
    fun `a request longer than the label still finds the button`() {
        // Le défaut le plus coûteux de l'ancienne règle : elle ne regardait que « le libellé contient la
        // demande », donc une demande plus longue que le libellé ne trouvait jamais rien.
        val list = listOf(el(0, "Envoyer"), el(1, "Brouillon"))
        assertEquals(0, found(findByText(list, "Envoyer le message")).index)
        assertEquals(0, found(findByText(list, "le bouton Envoyer")).index)
        assertEquals(0, found(findByText(list, "appuie sur envoyer")).index)
    }

    @Test
    fun `the app speaks English while the user speaks French`() {
        assertEquals(0, found(findByText(listOf(el(0, "Send")), "envoyer")).index)
        assertEquals(0, found(findByText(listOf(el(0, "Settings")), "paramètres")).index)
        assertEquals(0, found(findByText(listOf(el(0, "Search")), "rechercher")).index)
        assertEquals(0, found(findByText(listOf(el(0, "OK")), "valider")).index)
        assertEquals(0, found(findByText(listOf(el(0, "Annuler")), "cancel")).index)
    }

    @Test
    fun `a dictation slip does not lose the button`() {
        assertEquals(0, found(findByText(listOf(el(0, "Paramètres")), "paramettres")).index)
        assertEquals(0, found(findByText(listOf(el(0, "Contacts")), "contact")).index)
    }

    @Test
    fun `a short word is not forgiven a letter`() {
        // Sous cinq lettres, une lettre change le mot : « Nom » ne doit pas appuyer sur « Non ».
        assertEquals(ElementMatch.None, findByText(listOf(el(0, "Non")), "Nom"))
    }

    @Test
    fun `word order does not matter`() {
        assertEquals(0, found(findByText(listOf(el(0, "Envoyer un message")), "message envoyer")).index)
    }

    @Test
    fun `filler words are dropped from the request, never from the label`() {
        assertEquals(0, found(findByText(listOf(el(0, "Appuyer pour continuer")), "continuer")).index)
        // Une demande faite uniquement de mots creux ne doit pas tout attraper.
        assertEquals(ElementMatch.None, findByText(listOf(el(0, "Envoyer")), "le bouton"))
    }

    @Test
    fun `an unrelated word finds nothing`() {
        assertEquals(ElementMatch.None, findByText(listOf(el(0, "Envoyer"), el(1, "Brouillon")), "supprimer"))
        // Les groupes d'équivalents ne se chaînent pas : « envoyer » et « supprimer » restent étrangers.
        assertEquals(ElementMatch.None, findByText(listOf(el(0, "Send")), "delete"))
    }

    @Test
    fun `the same label on an item and its container is not an ambiguity`() {
        // Une ligne de liste et le texte qu'elle contient portent le même libellé : avant, cela suffisait à
        // rendre l'appui impossible sans numéro.
        val container = el(0, "Album Vacances", left = 0, top = 100, right = 1000, bottom = 200)
        val inner = el(1, "Album Vacances", left = 40, top = 130, right = 600, bottom = 170)
        assertEquals(1, found(findByText(listOf(container, inner), "Album Vacances")).index)
    }

    @Test
    fun `a real ambiguity is still handed back`() {
        val list = listOf(
            el(0, "Télécharger", left = 0, top = 0, right = 500, bottom = 100),
            el(1, "Télécharger", left = 0, top = 400, right = 500, bottom = 500),
        )
        val match = findByText(list, "télécharger")
        assertTrue(match is ElementMatch.Ambiguous)
        assertEquals(2, (match as ElementMatch.Ambiguous).candidates.size)
    }

    @Test
    fun `exact text beats a partial match`() {
        val list = listOf(el(0, "Photos"), el(1, "Photos récentes"))
        assertEquals(0, found(findByText(list, "photos")).index)
    }

    @Test
    fun `two equally partial candidates are handed back`() {
        val list = listOf(el(0, "Photo 1"), el(1, "Photo 2"))
        val match = findByText(list, "photo")
        assertTrue(match is ElementMatch.Ambiguous)
        assertEquals(2, (match as ElementMatch.Ambiguous).candidates.size)
    }

    @Test
    fun `blank queries and password fields are never matched`() {
        val list = listOf(el(0, "Envoyer"), el(1, "secret", password = true, editable = true))
        assertEquals(ElementMatch.None, findByText(list, "  "))
        assertEquals(ElementMatch.None, findByText(list, "secret"))
    }

    @Test
    fun `plain text that cannot be tapped is skipped unless asked`() {
        val list = listOf(el(0, "Bonjour", clickable = false))
        assertEquals(ElementMatch.None, findByText(list, "bonjour"))
        assertTrue(findByText(list, "bonjour", onlyActionable = false) is ElementMatch.Found)
    }

    @Test
    fun `an editable field is reachable by its hint`() {
        val list = listOf(el(0, "Rechercher", clickable = false, editable = true))
        assertEquals(0, found(findByText(list, "champ de recherche")).index)
    }

    @Test
    fun `the dead end lists what can be tapped instead`() {
        val list = listOf(el(0, "Envoyer"), el(1, "Brouillon"), el(2, "Texte", clickable = false))
        val summary = tappableSummary(list)
        assertTrue(summary.contains("« Envoyer »"))
        assertTrue(summary.contains("« Brouillon »"))
        assertFalse(summary.contains("Texte"))
    }

    @Test
    fun `the dead end says so when nothing can be tapped`() {
        assertTrue(tappableSummary(listOf(el(0, "Texte", clickable = false))).contains("Rien de cliquable"))
    }

    @Test
    fun `the summary does not repeat a label twice`() {
        val list = listOf(el(0, "Envoyer"), el(1, "Envoyer"))
        assertEquals(1, tappableSummary(list).split("« Envoyer »").size - 1)
    }

    @Test
    fun `edit distance stops counting past the budget`() {
        assertTrue(withinScreenEditDistance("parametres", "paramettres", 2))
        assertTrue(withinScreenEditDistance("contacts", "contact", 1))
        assertFalse(withinScreenEditDistance("envoyer", "supprimer", 2))
        assertFalse(withinScreenEditDistance("non", "nom", 0))
    }
}
