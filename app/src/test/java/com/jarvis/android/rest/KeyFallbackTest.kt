package com.jarvis.android.rest

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class KeyFallbackTest {
    private val ok = """{"candidates":[{"content":{"parts":[{"text":"ok"}]},"finishReason":"STOP"}]}"""

    /** Answers each request from [answers] keyed by the API key it carries, recording the order. */
    private class FakeNetwork(private val answers: Map<String, Pair<Int, String>>) : Interceptor {
        val keysSeen = mutableListOf<String>()
        override fun intercept(chain: Interceptor.Chain): Response {
            val key = chain.request().header("x-goog-api-key").orEmpty()
            keysSeen += key
            val (code, body) = answers[key] ?: (500 to "{}")
            return Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("fake")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }
    }

    private fun transport(
        network: FakeNetwork,
        keys: List<String>,
        current: () -> String? = { keys.first() },
    ): OkHttpGenerateTransport {
        var active = 0
        return OkHttpGenerateTransport(
            client = OkHttpClient.Builder().addInterceptor(network).build(),
            apiKey = { keys.getOrNull(active) ?: current() },
            nextKey = { rejected ->
                active = keys.indexOf(rejected) + 1
                keys.getOrNull(active)
            },
        )
    }

    private fun call(t: OkHttpGenerateTransport): JsonObject =
        runBlocking { t.generate("models/test", Json.parseToJsonElement("{}").jsonObject) }

    @Test
    fun `key problems are recognised, other failures are not`() {
        assertTrue(isKeyProblem(429, ""))
        assertTrue(isKeyProblem(401, ""))
        assertTrue(isKeyProblem(403, ""))
        assertTrue(isKeyProblem(400, """{"error":{"details":[{"reason":"API_KEY_INVALID"}]}}"""))
        assertTrue(isKeyProblem(400, "API key not valid. Please pass a valid API key."))
        assertFalse(isKeyProblem(400, """{"error":{"message":"Invalid model"}}"""))
        assertFalse(isKeyProblem(404, ""))
        assertFalse(isKeyProblem(500, ""))
    }

    @Test
    fun `a quota error on the first key falls back to the second`() {
        val net = FakeNetwork(mapOf("k1" to (429 to "{}"), "k2" to (200 to ok)))
        assertNotNull(call(transport(net, listOf("k1", "k2", "k3"))))
        assertEquals(listOf("k1", "k2"), net.keysSeen)
    }

    @Test
    fun `an invalid key then a refused key then a working key`() {
        val net = FakeNetwork(
            mapOf("k1" to (400 to "API_KEY_INVALID"), "k2" to (403 to "{}"), "k3" to (200 to ok))
        )
        call(transport(net, listOf("k1", "k2", "k3")))
        assertEquals(listOf("k1", "k2", "k3"), net.keysSeen)
    }

    @Test
    fun `when every key is refused the last status is reported`() {
        val net = FakeNetwork(mapOf("k1" to (429 to "{}"), "k2" to (429 to "{}")))
        try {
            call(transport(net, listOf("k1", "k2")))
            fail("erreur attendue")
        } catch (e: RestChatException) {
            assertEquals(429, e.httpCode)
            assertTrue(e.message!!.contains("429"))
        }
        assertEquals(listOf("k1", "k2"), net.keysSeen)
    }

    @Test
    fun `a server error does not switch keys`() {
        val net = FakeNetwork(mapOf("k1" to (503 to "{}"), "k2" to (200 to ok)))
        try {
            call(transport(net, listOf("k1", "k2")))
            fail("erreur attendue")
        } catch (e: RestChatException) {
            assertEquals(503, e.httpCode)
        }
        assertEquals(listOf("k1"), net.keysSeen)
    }

    @Test
    fun `a bad request about the model does not switch keys`() {
        val net = FakeNetwork(mapOf("k1" to (400 to """{"error":{"message":"bad model"}}"""), "k2" to (200 to ok)))
        try {
            call(transport(net, listOf("k1", "k2")))
            fail("erreur attendue")
        } catch (e: RestChatException) {
            assertEquals(400, e.httpCode)
        }
        assertEquals(listOf("k1"), net.keysSeen)
    }

    @Test
    fun `a single key is tried once`() {
        val net = FakeNetwork(mapOf("k1" to (429 to "{}")))
        try {
            call(transport(net, listOf("k1")))
            fail("erreur attendue")
        } catch (e: RestChatException) {
            assertEquals(429, e.httpCode)
        }
        assertEquals(listOf("k1"), net.keysSeen)
    }
}
