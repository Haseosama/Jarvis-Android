package com.jarvis.android.rest

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ModelLadderTest {
    private var clock = 0L
    private val ladder = ModelLadder(listOf("b", "c"), now = { clock })

    /** A client whose "server" answers each model with the status the test gives it. */
    private fun client(status: (model: String) -> Int, body: (model: String) -> String = { "{}" }, calls: MutableList<String>) =
        OkHttpClient.Builder().addInterceptor { chain ->
            val model = chain.request().url.encodedPath.substringAfter("models/").substringBefore(":")
            calls += model
            val code = status(model)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("x")
                .body((if (code == 200) """{"candidates":[{"content":{"parts":[{"text":"$model"}]}}]}""" else body(model)).toResponseBody("application/json".toMediaType()))
                .build()
        }.build()

    private val req: JsonObject = buildJsonObject { put("x", 1) }

    private fun transport(c: OkHttpClient) = OkHttpGenerateTransport(c, { "key" }, { null }, ladder)

    private fun answer(o: JsonObject) = o["candidates"]!!.jsonArray[0].jsonObject["content"]!!.jsonObject["parts"]!!.jsonArray[0]
        .jsonObject["text"]!!.jsonPrimitive.content

    @Test fun `a model that is not answering is set aside and the next one answers`() = runBlocking {
        val calls = mutableListOf<String>()
        val t = transport(client({ if (it == "a") 504 else 200 }, calls = calls))
        assertEquals("b", answer(t.generate("a", req)))
        // the next call does not wait on "a" again...
        assertEquals("b", answer(t.generate("a", req)))
        assertEquals(listOf("a", "b", "b"), calls)
        // ...for half an hour
        clock += 31 * 60_000L
        t.generate("a", req)
        assertEquals("a", calls[3])
    }

    @Test fun `out of quota on every key rests five minutes, a missing model six hours`() = runBlocking {
        val calls = mutableListOf<String>()
        val t = transport(client({ when (it) { "a" -> 429; "b" -> 400; else -> 200 } },
            body = { """{"error":{"message":"models/b is not found for API version v1beta"}}""" }, calls = calls))
        assertEquals("c", answer(t.generate("a", req)))
        clock += 6 * 60_000L
        assertEquals(listOf("a", "c"), ladder.candidates("a"))            // "a" back after its five minutes, "b" still out
        clock += 6 * 60 * 60_000L
        assertEquals(listOf("a", "b", "c"), ladder.candidates("a"))
    }

    @Test fun `a refused key or a bad request is not the model's fault`() = runBlocking {
        for (code in listOf(401, 400)) {
            val calls = mutableListOf<String>()
            val t = transport(client({ code }, body = { """{"error":{"message":"Invalid JSON payload"}}""" }, calls = calls))
            try { t.generate("a", req); fail() } catch (e: RestChatException) { assertEquals(code, e.httpCode) }
            assertEquals(listOf("a"), calls)
        }
    }

    @Test fun `speech models are never replaced by text ones`() {
        assertEquals(listOf("models/gemini-2.5-flash-preview-tts"), ladder.candidates("models/gemini-2.5-flash-preview-tts"))
        assertTrue(ladder.candidates("models/gemini-3.6-flash").containsAll(listOf("gemini-3.6-flash", "b", "c")))
    }

    @Test fun `the settings see what is resting and why, and can put it all back`() = runBlocking {
        val calls = mutableListOf<String>()
        val t = transport(client({ if (it == "a") 429 else 200 }, calls = calls))
        t.generate("a", req)
        assertEquals("b", ladder.lastAnswered)
        assertEquals(listOf(ModelLadder.Resting("a", ModelLadder.Failure.QUOTA, 5)), ladder.resting())
        clock += 2 * 60_000L
        assertEquals(3L, ladder.resting().single().minutesLeft)
        ladder.reset()
        assertTrue(ladder.resting().isEmpty())
        assertEquals("a", ladder.candidates("a").first())
    }

    @Test fun `every model resting still leaves the chosen one to try`() {
        ladder.rest("a", ModelLadder.Failure.QUOTA); ladder.rest("b", ModelLadder.Failure.QUOTA); ladder.rest("c", ModelLadder.Failure.QUOTA)
        assertEquals(listOf("a"), ladder.candidates("a"))
    }
}
