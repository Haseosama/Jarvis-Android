package com.jarvis.android.agent

import com.jarvis.android.actions.ToolRegistry
import com.jarvis.android.rest.RestChatSession
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentPlanTest {
    private fun obj(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    @Test
    fun `the agent cannot start itself or end the session`() {
        val names = agentToolDeclarations(ToolRegistry.declarations()).map { it["name"]!!.jsonPrimitive.content }
        assertFalse("agent_task" in names)
        assertFalse("end_session" in names)
        assertTrue("screen_read" in names && "open_app" in names)
    }

    @Test
    fun `goals are trimmed, cut and must not be empty`() {
        assertEquals("ouvrir Photos", cleanGoal("  ouvrir Photos "))
        assertNull(cleanGoal("   "))
        assertEquals(MAX_GOAL_CHARS, cleanGoal("x".repeat(5_000))!!.length)
    }

    @Test
    fun `the directive keeps the safety rules and the budget`() {
        val text = agentSystemInstruction("base")
        assertTrue(text.startsWith("base"))
        assertTrue(text.contains("ONE action"))
        assertTrue(text.contains("Never claim a step worked unless you saw it"))
        assertTrue(text.contains("$AGENT_MAX_ROUNDS tool rounds"))
    }

    private class Script(private val replies: MutableList<JsonObject>) : com.jarvis.android.rest.GenerateTransport {
        var calls = 0
        override suspend fun generate(model: String, request: JsonObject): JsonObject {
            calls++
            return replies.removeAt(0)
        }
    }

    private fun toolCall() = obj("""{"candidates":[{"content":{"role":"model","parts":[{"functionCall":{"name":"screen_read","args":{}}}]}}]}""")
    private fun text(t: String) = obj("""{"candidates":[{"content":{"role":"model","parts":[{"text":"$t"}]},"finishReason":"STOP"}]}""")

    @Test
    fun `a session with a larger round budget lets a long task finish`() = runBlocking {
        val replies = MutableList(10) { toolCall() } + text("Terminé")
        val transport = Script(replies.toMutableList())
        val session = RestChatSession(transport, { "m" }, { "s" }, { emptyList() }, { _, _ -> "ok" }, maxRounds = AGENT_MAX_ROUNDS)
        assertEquals("Terminé", session.send("Objectif : x"))
        assertEquals(11, transport.calls)
    }

    @Test
    fun `the default budget stops the same task early`() {
        val transport = Script(MutableList(20) { toolCall() })
        val session = RestChatSession(transport, { "m" }, { "s" }, { emptyList() }, { _, _ -> "ok" })
        try {
            runBlocking { session.send("Objectif : x") }
            org.junit.Assert.fail("erreur attendue")
        } catch (_: com.jarvis.android.rest.RestChatException) {
        }
        assertEquals(7, transport.calls)
    }
}
