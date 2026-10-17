package com.jarvis.android.rest

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RoutingTransportTest {
    private fun obj(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject
    private val question = obj("""{"contents":[{"role":"user","parts":[{"text":"x"}]}]}""")
    private val grounded = obj("""{"contents":[{"role":"user","parts":[{"text":"x"}]}],"tools":[{"google_search":{}}]}""")
    private fun said(who: String) = obj("""{"who":"$who"}""")

    private class Fake(val name: String, var failure: RestChatException? = null) : GenerateTransport {
        var calls = 0
        override suspend fun generate(model: String, request: JsonObject): JsonObject {
            calls++
            failure?.let { throw it }
            return Json.parseToJsonElement("""{"who":"$name"}""").jsonObject
        }
    }

    private fun routing(gemini: Fake, other: Fake, use: Int, configured: Boolean = true, key: Boolean = true) = RoutingTransport(
        gemini, other,
        settings = { OtherModelConfig(if (configured) "http://127.0.0.1:11434/v1" else "", "qwen3", null, use, false) },
        hasGeminiKey = { key },
    )

    @Test
    fun `off or not configured, gemini answers everything`() = runBlocking {
        assertEquals(said("gemini"), routing(Fake("gemini"), Fake("other"), OTHER_MODEL_OFF).generate("gemini-3.5-flash", question))
        assertEquals(said("gemini"), routing(Fake("gemini"), Fake("other"), OTHER_MODEL_ALWAYS, configured = false).generate("gemini-3.5-flash", question))
    }

    @Test
    fun `in fallback the other model answers only when gemini cannot`() = runBlocking {
        val gemini = Fake("gemini")
        val other = Fake("other")
        val r = routing(gemini, other, OTHER_MODEL_FALLBACK)
        assertEquals(said("gemini"), r.generate("gemini-3.5-flash", question))
        gemini.failure = RestChatException("quota", 429)
        assertEquals(said("other"), r.generate("gemini-3.5-flash", question))
        gemini.failure = RestChatException(ERROR_BLOCKED)
        try { r.generate("gemini-3.5-flash", question); fail() } catch (e: RestChatException) { assertEquals(ERROR_BLOCKED, e.message) }
        assertEquals(1, other.calls)
    }

    @Test
    fun `both failing shows gemini's error with the other's reason`() = runBlocking {
        val r = routing(Fake("gemini", RestChatException("quota", 429)), Fake("other", RestChatException("PC éteint", 503)), OTHER_MODEL_FALLBACK)
        try { r.generate("gemini-3.5-flash", question); fail() } catch (e: RestChatException) {
            assertEquals("quota (PC éteint)", e.message)
            assertEquals(429, e.httpCode)
        }
        // no network: left as it is, so the chat can still hand it to the phone's own model
        val offline = routing(Fake("gemini", RestChatException(ERROR_NETWORK)), Fake("other", RestChatException("x", 503)), OTHER_MODEL_FALLBACK)
        try { offline.generate("gemini-3.5-flash", question); fail() } catch (e: RestChatException) { assertEquals(ERROR_NETWORK, e.message) }
    }

    @Test
    fun `instead of gemini, with gemini taking over when the other is down`() = runBlocking {
        val gemini = Fake("gemini")
        val other = Fake("other")
        val r = routing(gemini, other, OTHER_MODEL_ALWAYS)
        assertEquals(said("other"), r.generate("gemini-3.5-flash", question))
        other.failure = RestChatException(ERROR_OTHER_UNREACHABLE, 503)
        assertEquals(said("gemini"), r.generate("gemini-3.5-flash", question))
        // without a Gemini key there is nobody to take over
        try { routing(Fake("gemini"), other, OTHER_MODEL_ALWAYS, key = false).generate("m", question); fail() } catch (e: RestChatException) {
            assertEquals(ERROR_OTHER_UNREACHABLE, e.message)
        }
    }

    @Test
    fun `no gemini key sends the other model first even in fallback`() = runBlocking {
        val gemini = Fake("gemini")
        assertEquals(said("other"), routing(gemini, Fake("other"), OTHER_MODEL_FALLBACK, key = false).generate("gemini-3.5-flash", question))
        assertEquals(0, gemini.calls)
    }

    @Test
    fun `what only gemini can do stays with gemini`() = runBlocking {
        val r = routing(Fake("gemini"), Fake("other"), OTHER_MODEL_ALWAYS)
        assertEquals(said("gemini"), r.generate("gemini-3.5-flash", grounded))
        assertEquals(said("gemini"), r.generate("gemini-2.5-flash-preview-tts", question))
    }

    @Test
    fun `a stream moves to the other model only before anything was handed on`() = runBlocking {
        val events = mutableListOf<JsonObject>()
        val r = routing(Fake("gemini", RestChatException("quota", 429)), Fake("other"), OTHER_MODEL_FALLBACK)
        r.stream("gemini-3.5-flash", question) { events += it }
        assertEquals(listOf(said("other")), events)
        assertTrue(geminiCannotAnswer(RestChatException(ERROR_NO_KEY)))
    }
}
