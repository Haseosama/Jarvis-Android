package com.jarvis.android.core

import com.jarvis.android.rest.ModelLadder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import com.jarvis.android.engine.LiveModels

class LiveModelsTest {
    @Test fun `a session refused for quota or a missing model moves to the next Live model`() {
        assertEquals(ModelLadder.Failure.QUOTA, LiveModels.failure("Session fermée (1011) : RESOURCE_EXHAUSTED: You exceeded your current quota"))
        assertEquals(ModelLadder.Failure.QUOTA, LiveModels.failure("Erreur réseau. (HTTP 429)"))
        assertEquals(ModelLadder.Failure.GONE, LiveModels.failure("Erreur réseau. (HTTP 404)"))
        assertEquals(ModelLadder.Failure.GONE,
            LiveModels.failure("Session fermée (1008) : models/gemini-9-live is not found for API version v1beta, or is not supported for bidiGenerateContent"))
    }

    @Test fun `a network drop or a refused key is not the model's fault`() {
        assertNull(LiveModels.failure("Erreur réseau."))
        assertNull(LiveModels.failure("Délai de connexion dépassé."))
        assertNull(LiveModels.failure("Session fermée (1007) : API key not valid. Please pass a valid API key."))
        assertNull(LiveModels.failure("Erreur réseau. (HTTP 403)"))
    }

    @Test fun `the Live ladder keeps Live models and steps over a resting one`() {
        var clock = 0L
        val l = ModelLadder(LiveModels.FALLBACKS, now = { clock }, textOnly = false)
        val chosen = "models/gemini-3.8-live"
        assertEquals("gemini-3.8-live", l.candidates(chosen).first())
        l.rest(chosen, ModelLadder.Failure.QUOTA)
        assertEquals("gemini-3.1-flash-live-preview", l.candidates(chosen).first())
        clock += 5 * 60_000L + 1
        assertEquals("gemini-3.8-live", l.candidates(chosen).first())
    }
}
