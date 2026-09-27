package com.jarvis.android.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoicesTest {
    @Test fun `all thirty Gemini voices, fourteen of them women's, listed first`() {
        val v = ConfigStore.VOICES
        assertEquals(30, v.map { it.name }.toSet().size)
        assertEquals(14, v.count { it.female })
        assertTrue(v.takeWhile { it.female }.size == 14)
        // the voices of earlier versions are still there, so a saved choice keeps working
        assertTrue(ConfigStore.AVAILABLE_VOICES.containsAll(listOf("Puck", "Charon", "Kore", "Fenrir", "Aoede")))
    }

    @Test fun `a voice reads its gender and character in the interface language`() {
        val kore = ConfigStore.VOICES.first { it.name == "Kore" }
        assertEquals("Kore · féminine, ferme", kore.label(false))
        assertEquals("Kore · female, firm", kore.label(true))
    }
}
