package com.jarvis.android.actions

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PcBrowserToolTest {
    @Test
    fun `only the arguments given go to the pc, the value as typed and submit as a boolean`() {
        val sent = browserAction(
            buildJsonObject {
                put("action", " Fill "); put("target", " 3 "); put("value", "  mot de passe "); put("submit", "oui"); put("url", "")
                put("question", "pas pour le PC")
            },
        )
        assertEquals(
            buildJsonObject { put("action", "fill"); put("target", "3"); put("value", "  mot de passe "); put("submit", true) },
            sent,
        )
    }

    @Test
    fun `no action means reading the page, and a tab number goes as given`() {
        assertEquals(buildJsonObject { put("action", "read") }, browserAction(buildJsonObject {}))
        assertEquals(JsonPrimitive("2"), browserAction(buildJsonObject { put("action", "tab"); put("index", 2) })["index"])
        assertTrue("submit" !in browserAction(buildJsonObject { put("action", "fill"); put("submit", "non") }))
    }
}
