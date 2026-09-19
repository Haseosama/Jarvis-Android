package com.jarvis.android.rest

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RestChatTest {
    private fun obj(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun textResponse(text: String) =
        obj("""{"candidates":[{"content":{"role":"model","parts":[{"text":"$text"}]},"finishReason":"STOP"}]}""")

    private fun callResponse(name: String, args: String = "{}") = obj(
        """{"candidates":[{"content":{"role":"model","parts":[{"functionCall":{"name":"$name","args":$args,"id":"c1"},"thoughtSignature":"sig"}]}}]}"""
    )

    private class FakeTransport(private val replies: MutableList<() -> JsonObject>) : GenerateTransport {
        val requests = mutableListOf<JsonObject>()
        override suspend fun generate(model: String, request: JsonObject): JsonObject {
            requests += request
            return replies.removeAt(0)()
        }
    }

    private fun session(transport: GenerateTransport, tool: (String, JsonObject) -> String = { _, _ -> "ok" }) =
        RestChatSession(
            transport = transport,
            model = { "models/test" },
            systemInstruction = { "system" },
            toolDeclarations = { emptyList() },
            runTool = { name, args -> tool(name, args) },
        )

    private fun failure(block: () -> Unit): String {
        try {
            block()
        } catch (e: RestChatException) {
            return e.message.orEmpty()
        }
        fail("RestChatException attendue")
        return ""
    }

    @Test
    fun `plain answer is returned and kept in the history`() = runBlocking {
        val s = session(FakeTransport(mutableListOf({ textResponse("Bonjour") })))
        assertEquals("Bonjour", s.send("Salut"))
        assertEquals(listOf("user", "model"), s.snapshot().map { it["role"]!!.jsonPrimitive.content })
    }

    @Test
    fun `blank draft is rejected without touching the history`() {
        val s = session(FakeTransport(mutableListOf()))
        assertEquals(ERROR_EMPTY_DRAFT, failure { runBlocking { s.send("   ") } })
        assertTrue(s.snapshot().isEmpty())
    }

    @Test
    fun `tool call is run then its result is sent back and the model turn is echoed verbatim`() = runBlocking {
        val transport = FakeTransport(mutableListOf({ callResponse("battery") }, { textResponse("80 %") }))
        val ran = mutableListOf<String>()
        val s = session(transport) { name, _ -> ran += name; "batterie 80" }
        assertEquals("80 %", s.send("Batterie ?"))
        assertEquals(listOf("battery"), ran)
        val second = transport.requests[1]["contents"]!!.jsonArray.map { it.jsonObject }
        assertEquals(3, second.size)
        assertNotNull(second[1]["parts"]!!.jsonArray[0].jsonObject["thoughtSignature"])
        val response = second[2]["parts"]!!.jsonArray[0].jsonObject["functionResponse"]!!.jsonObject
        assertEquals("battery", response["name"]!!.jsonPrimitive.content)
        assertEquals("c1", response["id"]!!.jsonPrimitive.content)
        assertEquals("batterie 80", response["response"]!!.jsonObject["result"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a failure rolls the history back`() {
        val s = session(FakeTransport(mutableListOf({ textResponse("un") }, { throw RestChatException("boom") })))
        runBlocking { s.send("a") }
        assertEquals("boom", failure { runBlocking { s.send("b") } })
        assertEquals(2, s.snapshot().size)
    }

    @Test
    fun `endless tool calls stop after the round limit and roll back`() {
        val replies = MutableList(20) { { callResponse("loop") } }
        val s = session(FakeTransport(replies))
        assertEquals(ERROR_TOO_MANY_TOOLS, failure { runBlocking { s.send("go") } })
        assertTrue(s.snapshot().isEmpty())
    }

    @Test
    fun `reset clears the history`() = runBlocking {
        val s = session(FakeTransport(mutableListOf({ textResponse("x") })))
        s.send("a")
        s.reset()
        assertTrue(s.snapshot().isEmpty())
    }

    @Test
    fun `blocked prompt and safety finish give the blocked message`() {
        assertEquals(ERROR_BLOCKED, failure { parseGenerateResponse(obj("""{"promptFeedback":{"blockReason":"SAFETY"}}""")) })
        val safety = obj("""{"candidates":[{"finishReason":"SAFETY"}]}""")
        assertEquals(ERROR_BLOCKED, failure { parseGenerateResponse(safety) })
    }

    @Test
    fun `empty or malformed responses give readable errors`() {
        assertEquals(ERROR_EMPTY, failure { parseGenerateResponse(obj("{}")) })
        assertEquals(ERROR_MALFORMED, failure { parseGenerateResponse(obj("""{"candidates":["x"]}""")) })
    }

    @Test
    fun `thought parts are not shown`() {
        val reply = parseGenerateResponse(
            obj("""{"candidates":[{"content":{"parts":[{"text":"secret","thought":true},{"text":"Salut"}]}}]}""")
        ) as RestReply.Text
        assertEquals("Salut", reply.text)
    }

    @Test
    fun `model turn gets a role when missing`() {
        val turn = modelTurn(obj("""{"parts":[{"text":"x"}]}"""))
        assertEquals("model", turn["role"]!!.jsonPrimitive.content)
        assertFalse(modelTurn(obj("""{"role":"model","parts":[]}""")).isEmpty())
    }

    @Test
    fun `http codes map to specific messages`() {
        assertTrue(httpErrorMessage(429).contains("429"))
        assertTrue(httpErrorMessage(404).contains("Modèle"))
        assertTrue(httpErrorMessage(503).contains("indisponible"))
        assertTrue(httpErrorMessage(403).contains("Clé"))
    }

    @Test
    fun `request carries system instruction tools and contents`() {
        val request = buildGenerateRequest("sys", listOf(userTurn("hi")), listOf(obj("""{"name":"t"}""")))
        assertEquals(1, request["contents"]!!.jsonArray.size)
        assertEquals(1, request["tools"]!!.jsonArray[0].jsonObject["functionDeclarations"]!!.jsonArray.size)
        assertFalse(buildGenerateRequest("sys", emptyList(), emptyList()).containsKey("tools"))
    }
}
