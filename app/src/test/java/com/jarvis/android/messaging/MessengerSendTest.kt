package com.jarvis.android.messaging

import com.jarvis.android.device.ScreenElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessengerSendTest {
    // A "send to" list as the accessibility service reads it: a name and an "Envoyer" button on each row, 150 px apart.
    private fun row(i: Int, name: String, firstIndex: Int, button: String = "Envoyer"): List<ScreenElement> {
        val top = 400 + i * 150
        return listOf(
            ScreenElement(firstIndex, name, "texte", left = 180, top = top + 40, right = 700, bottom = top + 90),
            ScreenElement(firstIndex + 1, button, "bouton", clickable = true, left = 900, top = top + 30, right = 1200, bottom = top + 110),
        )
    }

    private val screen = listOf(
        ScreenElement(0, "Rechercher", "champ", editable = true, left = 40, top = 250, right = 1300, bottom = 350),
    ) + row(0, "Alice Martin", 1) + row(1, "Paul Durand", 3) + row(2, "Marie Dupont", 5)

    @Test
    fun `the button on the named person's row is the one pressed, not the first on the screen`() {
        val pick = pickMessengerSend(screen, "paul durand")
        assertEquals(MessengerPick.Found(4, "Paul Durand"), pick)
    }

    @Test
    fun `a first name alone is enough when only one person has it`() {
        assertEquals(MessengerPick.Found(6, "Marie Dupont"), pickMessengerSend(screen, "Marie"))
    }

    @Test
    fun `two people answering to the name are never guessed between`() {
        val two = screen + row(3, "Paul Martin", 7)
        val pick = pickMessengerSend(two, "Paul")
        assertTrue(pick is MessengerPick.Ambiguous)
        assertEquals(listOf("Paul Durand", "Paul Martin"), (pick as MessengerPick.Ambiguous).names.sorted())
        // …unless one of them is exactly the name said.
        assertEquals(MessengerPick.Found(4, "Paul Durand"), pickMessengerSend(two, "Paul Durand"))
    }

    @Test
    fun `nobody matching says who was there, and the search field is not mistaken for a name`() {
        val pick = pickMessengerSend(screen, "Zoé")
        assertTrue(pick is MessengerPick.None)
        assertEquals(listOf("Alice Martin", "Paul Durand", "Marie Dupont"), (pick as MessengerPick.None).visible)
    }

    @Test
    fun `a button that names the person itself counts`() {
        val labelled = listOf(ScreenElement(0, "Envoyer à Paul Durand", "bouton", clickable = true, top = 400, bottom = 480))
        assertEquals(MessengerPick.Found(0, "Envoyer à Paul Durand"), pickMessengerSend(labelled, "Paul"))
    }

    @Test
    fun `sent is only believed when Messenger marks that row`() {
        val after = screen.map { if (it.index == 4) it.copy(label = "Envoyé") else it }
        assertTrue(messengerConfirmsSent(after, "Paul Durand"))
        assertFalse(messengerConfirmsSent(screen, "Paul Durand"))
        // "Envoyé" on someone else's row is not proof for Paul.
        val other = screen.map { if (it.index == 2) it.copy(label = "Envoyé") else it }
        assertFalse(messengerConfirmsSent(other, "Paul Durand"))
    }
}
