package com.jarvis.android.core

import com.jarvis.android.actions.EndSessionTool
import com.jarvis.android.actions.ToolRegistry
import com.jarvis.android.actions.prepareMessageDraft
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EndOfSessionTest {
    @Test
    fun `nothing happens until a request was made`() {
        val e = EndOfSession()
        assertFalse(e.pending)
        e.toolResponseSent()
        assertFalse(e.turnCompleted())
    }

    @Test
    fun `a turn completed before the tool answer went out does not end the session`() {
        val e = EndOfSession()
        e.request()
        assertTrue(e.pending)
        assertFalse(e.turnCompleted())
    }

    @Test
    fun `the first turn completed after the tool answer ends the session`() {
        val e = EndOfSession()
        e.request()
        e.toolResponseSent()
        assertTrue(e.turnCompleted())
    }

    @Test
    fun `reset cancels a pending request`() {
        val e = EndOfSession()
        e.request()
        e.toolResponseSent()
        e.reset()
        assertFalse(e.pending)
        assertFalse(e.turnCompleted())
    }

    @Test
    fun `end_session is registered with a valid declaration`() {
        assertNotNull(ToolRegistry.get("end_session"))
        val declaration = ToolRegistry.declarations().first { it["name"]!!.jsonPrimitive.content == "end_session" }
        assertNotNull(declaration["parameters"]!!.jsonObject["properties"])
        assertEquals("end_session", EndSessionTool.name)
    }

    @Test
    fun `messenger is an accepted message target`() {
        assertEquals("messenger", prepareMessageDraft("Salut", " Messenger ")?.app)
    }
}
