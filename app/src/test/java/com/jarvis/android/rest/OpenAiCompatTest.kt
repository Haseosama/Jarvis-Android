package com.jarvis.android.rest

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class OpenAiCompatTest {
    private fun obj(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    @Test
    fun `addresses are written the way the servers want them`() {
        assertEquals("http://100.101.102.103:11434/v1", normalizeBaseUrl("100.101.102.103:11434"))
        assertEquals("http://127.0.0.1:11434/v1", normalizeBaseUrl("http://localhost:11434/"))
        assertEquals("https://api.groq.com/openai/v1", normalizeBaseUrl("https://api.groq.com/openai/v1/chat/completions"))
        assertEquals("https://api.mistral.ai/v1", normalizeBaseUrl("api.mistral.ai"))
        assertEquals("http://mon-pc.tail1234.ts.net:1234/v1", normalizeBaseUrl("mon-pc.tail1234.ts.net:1234/v1/"))
        assertEquals("", normalizeBaseUrl("  "))
    }

    @Test
    fun `the user's own machines are told from the internet`() {
        listOf("127.0.0.1", "localhost", "192.168.1.20", "10.0.0.5", "172.20.1.1", "100.64.0.1", "100.127.255.1", "pc.tail.ts.net", "nas.local")
            .forEach { assertTrue(it, isPrivateHost(it)) }
        listOf("8.8.8.8", "100.128.0.1", "172.32.0.1", "api.groq.com", "").forEach { assertFalse(it, isPrivateHost(it)) }
        assertTrue(isPrivateEndpoint("http://100.90.1.2:11434/v1"))
        assertFalse(isPrivateEndpoint("https://openrouter.ai/api/v1"))
    }

    @Test
    fun `gemini schemas become json schemas`() {
        val schema = obj("""{"type":"OBJECT","properties":{"n":{"type":"INTEGER","description":"Un nombre. Suite.","nullable":true},
            "l":{"type":"ARRAY","items":{"type":"STRING"}}},"required":["n"]}""")
        val out = toJsonSchema(schema, brief = true).jsonObject
        assertEquals("object", out["type"]!!.jsonPrimitive.content)
        val n = out["properties"]!!.jsonObject["n"]!!.jsonObject
        assertEquals("integer", n["type"]!!.jsonPrimitive.content)
        assertNull(n["nullable"])
        assertEquals("Un nombre.", n["description"]!!.jsonPrimitive.content)
        assertEquals("string", out["properties"]!!.jsonObject["l"]!!.jsonObject["items"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("n", out["required"]!!.jsonArray[0].jsonPrimitive.content)
    }

    @Test
    fun `a conversation with tool calls is translated, ids matched`() {
        val request = obj("""{
            "systemInstruction":{"parts":[{"text":"Tu es Jarvis."}]},
            "contents":[
              {"role":"user","parts":[{"text":"Batterie et météo ?"}]},
              {"role":"model","parts":[{"text":"","thought":true},{"functionCall":{"name":"battery","args":{}}},{"functionCall":{"name":"weather","args":{"city":"Brest"}}}]},
              {"role":"user","parts":[{"functionResponse":{"name":"weather","response":{"result":"pluie"}}},{"functionResponse":{"name":"battery","response":{"result":"80 %"}}}]}
            ],
            "tools":[{"functionDeclarations":[{"name":"battery","description":"Battery.","parameters":{"type":"OBJECT","properties":{}}}]}],
            "toolConfig":{"functionCallingConfig":{"mode":"NONE"}},
            "generationConfig":{"temperature":0.3,"maxOutputTokens":200}
        }""")
        val out = toOpenAiRequest("qwen3:8b", request, functionDeclarationsOf(request), brief = false)
        assertEquals("qwen3:8b", out["model"]!!.jsonPrimitive.content)
        val messages = out["messages"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("system", "user", "assistant", "tool", "tool"), messages.map { it["role"]!!.jsonPrimitive.content })
        val calls = messages[2]["tool_calls"]!!.jsonArray.map { it.jsonObject }
        assertEquals("""{"city":"Brest"}""", calls[1]["function"]!!.jsonObject["arguments"]!!.jsonPrimitive.content)
        val weatherId = calls[1]["id"]!!.jsonPrimitive.content
        val batteryId = calls[0]["id"]!!.jsonPrimitive.content
        assertEquals(weatherId, messages[3]["tool_call_id"]!!.jsonPrimitive.content)
        assertEquals("pluie", messages[3]["content"]!!.jsonPrimitive.content)
        assertEquals(batteryId, messages[4]["tool_call_id"]!!.jsonPrimitive.content)
        assertEquals("none", out["tool_choice"]!!.jsonPrimitive.content)
        assertEquals(0.3, out["temperature"]!!.jsonPrimitive.content.toDouble(), 1e-9)
        assertEquals("200", out["max_tokens"]!!.jsonPrimitive.content)
        assertEquals("function", out["tools"]!!.jsonArray[0].jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `pictures go along, gemini-only requests are refused`() {
        val picture = obj("""{"contents":[{"role":"user","parts":[{"text":"Qu'est-ce ?"},{"inlineData":{"mimeType":"image/jpeg","data":"QUJD"}}]}]}""")
        val content = toOpenAiRequest("m", picture, emptyList(), false)["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray
        assertEquals("data:image/jpeg;base64,QUJD", content[1].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content)
        assertTrue(portableRequest(picture))

        val refused = listOf(
            """{"contents":[{"role":"user","parts":[{"text":"x"}]}],"tools":[{"google_search":{}}]}""",
            """{"contents":[{"role":"user","parts":[{"inlineData":{"mimeType":"application/pdf","data":"QUJD"}}]}]}""",
            """{"contents":[{"role":"user","parts":[{"text":"x"}]}],"generationConfig":{"responseModalities":["AUDIO"]}}""",
        )
        refused.forEach { assertFalse(it, portableRequest(obj(it))) }
    }

    @Test
    fun `an answer comes back in gemini's shape, without its reasoning`() {
        val text = parseGenerateResponse(fromOpenAiResponse(obj(
            """{"choices":[{"message":{"role":"assistant","content":"<think>hmm</think>Bonjour."},"finish_reason":"stop"}]}"""
        )))
        assertEquals("Bonjour.", (text as RestReply.Text).text)

        val calls = parseGenerateResponse(fromOpenAiResponse(obj(
            """{"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[{"id":"call_9","type":"function","function":{"name":"timer","arguments":"{\"minutes\":5}"}}]}}]}"""
        ))) as RestReply.Calls
        assertEquals("timer", calls.calls[0].name)
        assertEquals("call_9", calls.calls[0].id)
        assertEquals("5", calls.calls[0].args["minutes"]!!.jsonPrimitive.content)

        try {
            parseGenerateResponse(fromOpenAiResponse(obj("""{"choices":[{"message":{"content":"x"},"finish_reason":"content_filter"}]}""")))
            fail("bloqué attendu")
        } catch (e: RestChatException) {
            assertEquals(ERROR_BLOCKED, e.message)
        }
        try {
            fromOpenAiResponse(obj("""{"error":{"message":"model not loaded"}}"""))
            fail("erreur attendue")
        } catch (e: RestChatException) {
            assertTrue(e.message!!.contains("model not loaded"))
        }
    }

    @Test
    fun `a small model at home gets few tools, by count and by size`() {
        val big = (1..30).map { i ->
            obj("""{"name":"t$i","description":"${"x".repeat(if (i == 2) 5000 else 100)}","parameters":{"type":"OBJECT","properties":{}}}""")
        }
        val ordered = listOf(big[5], big[1], big[9])
        val kept = withinBudget(big, ToolBudget(count = 5, chars = 3_000), ordered)
        assertEquals(5, kept.size)
        assertEquals("t6", kept[0]["name"]!!.jsonPrimitive.content)
        // the five thousand characters of t2 are shortened to one sentence, so it fits
        assertEquals(listOf("t6", "t2", "t10", "t1", "t3"), kept.map { it["name"]!!.jsonPrimitive.content })
        val tight = withinBudget(big, ToolBudget(count = 30, chars = 1_000), emptyList())
        assertTrue(tight.size in 1..29)
        assertTrue(tight.sumOf { toOpenAiTool(it, brief = true).toString().length } <= 1_000)
    }

    @Test
    fun `descriptions shorten to their first sentence`() {
        assertEquals("Ouvre une app.", briefOf("Ouvre une app. Puis autre chose.", 200))
        assertEquals("abc…", briefOf("abcdefgh", 4))
        assertEquals("Version 0.9.1 du truc", briefOf("Version 0.9.1 du truc", 200))
    }

    @Test
    fun `refusals are explained in words`() {
        assertTrue(otherModelHttpError(401, "", "m", "api.groq.com").contains("api.groq.com"))
        assertTrue(otherModelHttpError(404, """{"error":{"message":"model 'qwen3:9b' not found"}}""", "qwen3:9b", "h").contains("qwen3:9b"))
        assertTrue(otherModelHttpError(404, "404 page not found", "m", "h").contains("/v1"))
        assertTrue(otherModelHttpError(429, """{"error":{"message":"Rate limit"}}""", "m", "h").contains("Rate limit"))
        assertEquals(listOf("llama3", "qwen3:8b"), parseModelList(obj("""{"data":[{"id":"qwen3:8b"},{"id":"llama3"}]}""")))
        assertEquals(listOf("gemma3"), parseModelList(obj("""{"models":[{"name":"gemma3"}]}""")))
    }
}
