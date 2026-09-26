package com.jarvis.android.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SendPressTest {
    private fun field(text: String, filled: Boolean, bottom: Int = 2800) =
        ScreenElement(10, text, "champ", editable = true, filled = filled, top = bottom - 120, bottom = bottom)

    @Test
    fun `the draft is the filled field at the bottom, never a hint`() {
        assertEquals("J'arrive", draftInField(listOf(field("Rechercher", false, 300), field("J'arrive", true))))
        assertNull(draftInField(listOf(field("Aa", false)))) // the empty field's hint is not a message
    }

    @Test
    fun `a message still in the field has not gone, an emptied field has`() {
        assertTrue(stillInField(listOf(field("J'arrive dans 10 minutes", true)), "j'arrive dans 10 minutes"))
        assertFalse(stillInField(listOf(field("Aa", false), ScreenElement(3, "J'arrive dans 10 minutes", "texte")), "J'arrive dans 10 minutes"))
    }

    @Test
    fun `the send button is taken only when there is exactly one, and a money button is never one`() {
        val convo = listOf(field("Salut", true), ScreenElement(4, "Envoyer", "bouton", clickable = true))
        assertEquals(4, singleSendButton(convo)?.index)
        val shareList = convo + ScreenElement(5, "Envoyer", "bouton", clickable = true)
        assertNull(singleSendButton(shareList))
        assertNull(singleSendButton(listOf(ScreenElement(6, "Envoyer de l'argent", "bouton", clickable = true))))
    }

    @Test
    fun `a tap asked by the word send, or on a send button, counts as sending a message`() {
        assertTrue(meansSend("Envoyer", null))
        assertTrue(meansSend("J'aime", "envoyer")) // the model asked for "envoyer" while its snapshot still showed the thumbs-up
        assertFalse(meansSend("J'aime", null))
        assertFalse(meansSend("Envoyer de l'argent", null))
    }
}
