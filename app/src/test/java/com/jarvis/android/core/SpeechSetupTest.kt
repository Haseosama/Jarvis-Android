package com.jarvis.android.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechSetupTest {
    private fun setup(language: String? = null, tune: Boolean = false): JsonObject = LiveProtocol.buildSetup(
        model = "models/test",
        systemInstruction = "x",
        toolDeclarations = emptyList(),
        voiceName = "Puck",
        resumeHandle = null,
        languageCode = language,
        tuneSpeechDetection = tune,
    )["setup"]!!.jsonObject

    private fun speechConfig(s: JsonObject) = s["generationConfig"]!!.jsonObject["speechConfig"]!!.jsonObject

    @Test
    fun `language is not pinned by default`() {
        assertNull(speechConfig(setup())["languageCode"])
    }

    @Test
    fun `a chosen language is sent and blank is ignored`() {
        assertEquals("fr-FR", speechConfig(setup(language = "fr-FR"))["languageCode"]!!.jsonPrimitive.content)
        assertNull(speechConfig(setup(language = " "))["languageCode"])
    }

    @Test
    fun `speech detection tuning is opt in`() {
        assertFalse(setup().containsKey("realtimeInputConfig"))
        val vad = setup(tune = true)["realtimeInputConfig"]!!.jsonObject["automaticActivityDetection"]!!.jsonObject
        assertEquals("START_SENSITIVITY_LOW", vad["startOfSpeechSensitivity"]!!.jsonPrimitive.content)
        assertTrue(vad["silenceDurationMs"]!!.jsonPrimitive.content.toInt() >= 500)
    }
}
