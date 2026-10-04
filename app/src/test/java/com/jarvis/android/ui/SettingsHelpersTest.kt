package com.jarvis.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsHelpersTest {
    @Test
    fun `plugins are found by name or description, whatever the case, and all are shown for an empty search`() {
        assertTrue(pluginMatches("", "meteo_marine", "Bulletin côtier"))
        assertTrue(pluginMatches("   ", "meteo_marine", "Bulletin côtier"))
        assertTrue(pluginMatches("MARINE", "meteo_marine", "Bulletin côtier"))
        assertTrue(pluginMatches(" côtier ", "meteo_marine", "Bulletin côtier"))
        assertFalse(pluginMatches("bourse", "meteo_marine", "Bulletin côtier"))
    }

    @Test
    fun `the REST model gets its models prefix once and an unsafe name is refused`() {
        assertEquals("models/gemini-2.5-flash", restModelPath("gemini-2.5-flash"))
        assertEquals("models/gemini-2.5-flash", restModelPath("models/gemini-2.5-flash"))
        assertNull(restModelPath(""))
        assertNull(restModelPath("models/"))
        assertNull(restModelPath("gemini flash"))
        assertNull(restModelPath("../v1/files"))
        assertNull(restModelPath("gemini?key=x"))
    }

    @Test
    fun `the Home Assistant test tells a refused token from a wrong address`() {
        assertEquals("Connexion réussie (HTTP 200).", homeAssistantAnswer(200))
        assertEquals("Jeton refusé (HTTP 401).", homeAssistantAnswer(401))
        assertEquals("Jeton refusé (HTTP 403).", homeAssistantAnswer(403))
        assertEquals("Échec (HTTP 404). Vérifiez l’adresse.", homeAssistantAnswer(404))
        assertEquals("Échec (HTTP 500). Vérifiez l’adresse.", homeAssistantAnswer(500))
    }

    @Test
    fun `the model list says when nothing, part or all of it was found`() {
        val none = modelListing(LiveModelsResult.NoneFound(37))
        assertTrue(none.models.isEmpty())
        assertTrue(none.message!!.contains("37 modèles vérifiés"))

        val emptyPages = modelListing(LiveModelsResult.Found(emptyList(), partial = true))
        assertTrue(emptyPages.models.isEmpty())
        assertTrue(emptyPages.message!!.startsWith("Aucun modèle compatible Live"))

        val partial = modelListing(LiveModelsResult.Found(listOf("models/a-live"), partial = true))
        assertEquals(listOf("models/a-live"), partial.models)
        assertTrue(partial.message!!.startsWith("Liste partielle"))

        val full = modelListing(LiveModelsResult.Found(listOf("models/a-live", "models/b-live"), partial = false))
        assertEquals(2, full.models.size)
        assertNull(full.message)
    }
}
