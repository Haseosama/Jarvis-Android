package com.jarvis.android.text

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

class ThinkTest {
    @Test
    fun `the reasoning is taken out and the answer kept`() {
        assertEquals("Bonjour !", stripThink("<think>L'utilisateur me salue, je dois répondre chaleureusement.</think>\nBonjour !"))
        assertEquals("Oui.", stripThink("<THINKING>hmm</THINKING>Oui."))
        assertEquals("Rien à cacher", stripThink("Rien à cacher"))
        assertEquals("a < b et c > d", stripThink("a < b et c > d"))
    }

    @Test
    fun `a server that dropped the opening tag still hides the reasoning`() {
        assertEquals("Il est 10 h.", stripThink("je regarde l'heure…</think> Il est 10 h."))
    }

    @Test
    fun `reasoning never closed hides the rest`() {
        assertEquals("Avant", stripThink("Avant<think>et tout le reste"))
    }

    @Test
    fun `tags split across chunks at any point are still caught`() {
        val text = "<think>je réfléchis longuement à la question</think>Voici la réponse <b>en gras</b>.<think>encore</think> Fin."
        val expected = "Voici la réponse <b>en gras</b>. Fin."
        val random = Random(42)
        repeat(200) {
            val filter = ThinkFilter()
            val out = StringBuilder()
            var i = 0
            while (i < text.length) {
                val n = 1 + random.nextInt(6)
                out.append(filter.feed(text.substring(i, minOf(text.length, i + n))))
                i += n
            }
            out.append(filter.finish())
            assertEquals(expected, out.toString())
        }
    }
}
