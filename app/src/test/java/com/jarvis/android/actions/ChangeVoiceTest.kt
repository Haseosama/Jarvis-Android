package com.jarvis.android.actions

import com.jarvis.android.memory.ConfigStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChangeVoiceTest {
    private val v = ConfigStore.VOICES
    private fun pick(current: String, name: String = "", gender: String = "", style: String = "") = pickVoice(v, current, name, gender, style)?.name

    @Test fun `a named voice, whatever its case or the words around it`() {
        assertEquals("Leda", pick("Puck", name = "leda"))
        assertEquals("Sulafat", pick("Puck", name = "la voix Sulafat"))
    }

    @Test fun `a woman's voice, and asking again gives the next one`() {
        assertEquals("Kore", pick("Puck", gender = "female"))
        assertEquals("Aoede", pick("Kore", gender = "féminine"))
        assertEquals("Kore", pick("Sulafat", gender = "female"))          // round again after the last one
        assertTrue(v.first { it.name == pick("Kore", gender = "male") }.female.not())
    }

    @Test fun `a character, in French or English, a word near enough`() {
        assertEquals("Sulafat", pick("Puck", style = "chaleureux"))
        assertEquals("Despina", pick("Puck", gender = "female", style = "douce"))
        assertEquals("Algieba", pick("Despina", style = "smooth"))
        assertTrue(v.first { it.name == pick("Puck", style = "grave") }.female.not())
    }

    @Test fun `nothing asked is simply the next voice, a wrong one is nothing`() {
        assertEquals(v[v.indexOfFirst { it.name == "Puck" } + 1].name, pick("Puck"))
        assertNull(pick("Puck", style = "robotique"))
    }
}
