package com.jarvis.android.rest

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class OtherModelTransportTest {
    private lateinit var server: MockWebServer

    @Before fun start() { server = MockWebServer().apply { start() } }
    @After fun stop() { server.shutdown() }

    private fun obj(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun transport(key: String? = null, url: String = server.url("/v1").toString()) =
        OtherModelTransport(OkHttpClient(), config = { OtherModelConfig(url, "qwen3:8b", key, OTHER_MODEL_ALWAYS, false) })

    private val question = obj("""{"contents":[{"role":"user","parts":[{"text":"Bonjour"}]}]}""")

    private fun answer(text: String) = MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","content":"$text"}}]}""")

    @Test
    fun `a gemini request goes out as chat completions and comes back as gemini`() = runBlocking {
        server.enqueue(answer("<think>salut</think>Bonjour !"))
        val reply = parseGenerateResponse(transport(key = "sk-test").generate("gemini-3.5-flash", question)) as RestReply.Text
        assertEquals("Bonjour !", reply.text)
        val sent = server.takeRequest()
        assertEquals("/v1/chat/completions", sent.path)
        assertEquals("Bearer sk-test", sent.getHeader("Authorization"))
        val body = obj(sent.body.readUtf8())
        assertEquals("qwen3:8b", body["model"]!!.jsonPrimitive.content)
        assertEquals("Bonjour", body["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `no key, no authorization header`() = runBlocking {
        server.enqueue(answer("ok"))
        transport().generate("m", question)
        assertNull(server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `a model that cannot take tools is asked again without them`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"message":"registry.ollama.ai/library/gemma2 does not support tools"}}"""))
        server.enqueue(answer("Sans outils"))
        val withTools = obj("""{"contents":[{"role":"user","parts":[{"text":"x"}]}],
            "tools":[{"functionDeclarations":[{"name":"battery","description":"b","parameters":{"type":"OBJECT","properties":{}}}]}]}""")
        val reply = parseGenerateResponse(transport().generate("m", withTools)) as RestReply.Text
        assertEquals("Sans outils", reply.text)
        assertTrue(obj(server.takeRequest().body.readUtf8()).containsKey("tools"))
        assertFalse(obj(server.takeRequest().body.readUtf8()).containsKey("tools"))
    }

    @Test
    fun `a model at home is given the tool budget, shortened`() = runBlocking {
        server.enqueue(answer("ok"))
        val many = (1..40).joinToString(",") { """{"name":"t$it","description":"Outil $it. Long texte qui suit.","parameters":{"type":"OBJECT","properties":{}}}""" }
        val request = obj("""{"contents":[{"role":"user","parts":[{"text":"x"}]}],"tools":[{"functionDeclarations":[$many]}]}""")
        // MockWebServer listens on the loopback: a model "at home"
        transport().generate("m", request)
        val tools = obj(server.takeRequest().body.readUtf8())["tools"]!!.jsonArray
        assertEquals(ToolBudget().count, tools.size)
        assertEquals("Outil 1.", tools[0].jsonObject["function"]!!.jsonObject["description"]!!.jsonPrimitive.content)
    }

    @Test
    fun `failures say what to do`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":{"message":"model \"qwen3:8b\" not found, try pulling it first"}}"""))
        try {
            transport().generate("m", question); fail()
        } catch (e: RestChatException) {
            assertTrue(e.message!!.contains("qwen3:8b"))
        }
        val port = server.port
        server.shutdown()
        try {
            transport(url = "http://127.0.0.1:$port/v1").generate("m", question); fail()
        } catch (e: RestChatException) {
            assertEquals(ERROR_OTHER_UNREACHABLE, e.message)
            assertEquals(503, e.httpCode)
        }
        server = MockWebServer().apply { start() }
    }

    @Test
    fun `plain http to the internet is refused before anything is sent`() = runBlocking {
        try {
            transport(url = "http://api.example.com/v1").generate("m", question); fail()
        } catch (e: RestChatException) {
            assertTrue(e.message!!.contains("https://"))
        }
    }

    @Test
    fun `the test and the model list speak in sentences`() = runBlocking {
        server.enqueue(answer("OK"))
        assertTrue(transport().test().contains("« OK »"))
        server.enqueue(MockResponse().setBody("""{"object":"list","data":[{"id":"qwen3:8b"},{"id":"gemma3:4b"}]}"""))
        assertEquals(listOf("gemma3:4b", "qwen3:8b"), transport().models().getOrThrow())
    }
}
