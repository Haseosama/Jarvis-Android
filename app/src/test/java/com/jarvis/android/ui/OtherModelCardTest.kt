package com.jarvis.android.ui

import com.jarvis.android.rest.OTHER_MODEL_PRESETS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OtherModelCardTest {
    @Test
    fun `a preset for the pc takes the paired pc's address`() {
        val ollama = OTHER_MODEL_PRESETS.first { it.label == "Ollama (PC)" }
        assertEquals("http://100.80.1.2:11434/v1", presetAddress(ollama, "100.80.1.2"))
        assertTrue(presetAddress(ollama, null).contains("ADRESSE-DU-PC"))
        val groq = OTHER_MODEL_PRESETS.first { it.label == "Groq" }
        assertEquals("https://api.groq.com/openai/v1", presetAddress(groq, "100.80.1.2"))
    }

    @Test
    fun `the other model's card has its place in the settings`() {
        assertEquals(SettingsGroup.AI, CARD_GROUP["Autre modèle (Ollama, Groq…)"])
        assertTrue(SettingsFilter(query = "ollama").matches { it }.contains("Autre modèle (Ollama, Groq…)"))
    }
}
