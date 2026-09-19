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
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64

class StreamingSpeechTest {
    private fun obj(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun audioEvent(pcm: ByteArray, finish: String? = null): String {
        val data = Base64.getEncoder().encodeToString(pcm)
        val finishPart = finish?.let { ""","finishReason":"$it"""" } ?: ""
        return """{"candidates":[{"content":{"parts":[{"inlineData":{"mimeType":"audio/L16;codec=pcm;rate=24000","data":"$data"}}]}$finishPart}]}"""
    }

    private fun sse(vararg events: String) = events.joinToString("\n\n", postfix = "\n\n") { "data: $it" }

    private class FakeNetwork(private val answers: Map<String, Pair<Int, String>>) : Interceptor {
        val urls = mutableListOf<String>()
        val keysSeen = mutableListOf<String>()
        override fun intercept(chain: Interceptor.Chain): Response {
            val key = chain.request().header("x-goog-api-key").orEmpty()
            urls += chain.request().url.toString()
            keysSeen += key
            val (code, body) = answers[key] ?: (500 to "")
            return Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("fake")
                .body(body.toResponseBody("text/event-stream".toMediaType()))
                .build()
        }
    }

    private fun collect(net: FakeNetwork, keys: List<String>): List<JsonObject> {
        var active = 0
        val transport = OkHttpGenerateTransport(
            client = OkHttpClient.Builder().addInterceptor(net).build(),
            apiKey = { keys[active] },
            nextKey = { rejected ->
                active = keys.indexOf(rejected) + 1
                keys.getOrNull(active)
            },
        )
        val events = mutableListOf<JsonObject>()
        runBlocking { transport.stream("models/tts", obj("{}")) { events += it } }
        return events
    }

    @Test
    fun `sse lines carry json only when they start with data`() {
        assertNull(parseSseData(""))
        assertNull(parseSseData(": keep-alive"))
        assertNull(parseSseData("event: message"))
        assertNull(parseSseData("data: [DONE]"))
        assertNull(parseSseData("data:"))
        assertEquals("1", parseSseData("data: {\"a\":\"1\"}")!!["a"].toString().trim('"'))
        assertEquals(ERROR_MALFORMED, try { parseSseData("data: {oops"); "" } catch (e: RestChatException) { e.message })
    }

    @Test
    fun `chunk parser returns audio, empty for events without audio, and refuses bad ones`() {
        val pcm = ByteArray(6) { it.toByte() }
        assertArrayEquals(pcm, parseSpeechChunk(obj(audioEvent(pcm))))
        assertEquals(0, parseSpeechChunk(obj("""{"usageMetadata":{}}""")).size)
        assertEquals(0, parseSpeechChunk(obj("""{"candidates":[{"finishReason":"STOP"}]}""")).size)
        assertEquals(ERROR_BLOCKED, try { parseSpeechChunk(obj("""{"candidates":[{"finishReason":"SAFETY"}]}""")); "" } catch (e: RestChatException) { e.message })
        assertEquals(ERROR_INVALID_AUDIO, try { parseSpeechChunk(obj(audioEvent(ByteArray(3)))); "" } catch (e: RestChatException) { e.message })
    }

    @Test
    fun `stream delivers each event in order through the sse endpoint`() {
        val body = sse(audioEvent(byteArrayOf(1, 2)), """{"usageMetadata":{}}""", audioEvent(byteArrayOf(3, 4), "STOP"))
        val net = FakeNetwork(mapOf("k1" to (200 to body)))
        val events = collect(net, listOf("k1"))
        assertEquals(3, events.size)
        assertTrue(net.urls.single().endsWith("models/tts:streamGenerateContent?alt=sse"))
        val audio = events.map { parseSpeechChunk(it) }.filter { it.isNotEmpty() }
        assertArrayEquals(byteArrayOf(1, 2), audio[0])
        assertArrayEquals(byteArrayOf(3, 4), audio[1])
    }

    @Test
    fun `a refused key falls back before any data is read`() {
        val body = sse(audioEvent(byteArrayOf(1, 2)))
        val net = FakeNetwork(mapOf("k1" to (429 to "{}"), "k2" to (200 to body)))
        assertEquals(1, collect(net, listOf("k1", "k2")).size)
        assertEquals(listOf("k1", "k2"), net.keysSeen)
    }

    @Test
    fun `a server error on the stream is reported with its code`() {
        val net = FakeNetwork(mapOf("k1" to (503 to "")))
        try {
            collect(net, listOf("k1"))
            fail("erreur attendue")
        } catch (e: RestChatException) {
            assertEquals(503, e.httpCode)
        }
    }

    @Test
    fun `voice plays streamed chunks in order and reports timings`() = runBlocking {
        val chunks = listOf(ByteArray(4) { 1 }, ByteArray(4) { 2 }, ByteArray(4) { 3 })
        val transport = object : GenerateTransport {
            override suspend fun generate(model: String, request: JsonObject): JsonObject =
                if (model == "models/text") obj("""{"candidates":[{"content":{"parts":[{"text":"salut"}]},"finishReason":"STOP"}]}""")
                else error("stream attendu")

            override suspend fun stream(model: String, request: JsonObject, onEvent: suspend (JsonObject) -> Unit) {
                chunks.forEach { onEvent(obj(audioEvent(it))) }
                onEvent(obj("""{"candidates":[{"finishReason":"STOP"}]}"""))
            }
        }
        val played = java.io.ByteArrayOutputStream()
        val emitted = mutableListOf<Int>()
        val output = object : SpeechOutput {
            override suspend fun play(source: suspend (suspend (ByteArray) -> Unit) -> Unit) {
                source { played.write(it); emitted += it.size }
            }

            override fun stop() {}
        }
        val metrics = mutableListOf<String>()
        var clock = 0L
        val voice = RestVoice(
            recorder = object : MicRecorder {
                override fun start() {}
                override fun stop() = ShortArray(1600) { 5 }
            },
            output = output,
            transport = transport,
            textModel = { "models/text" },
            speechModel = { "models/tts" },
            voice = { "Kore" },
            sendText = { null },
            lastReply = { "Bonjour" },
            onMetrics = { metrics += it },
            now = { clock += 100; clock },
        )
        voice.startRecording()
        assertNull(voice.finishRecording())
        assertEquals(listOf(4, 4, 4), emitted)
        assertArrayEquals(byteArrayOf(1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3), played.toByteArray())
        assertTrue(metrics.first().startsWith("Voix : premier son"))
        assertTrue(metrics.last().contains("3 morceaux"))
    }

    @Test
    fun `a stream without any audio is an error`() = runBlocking {
        val transport = object : GenerateTransport {
            override suspend fun generate(model: String, request: JsonObject): JsonObject =
                obj("""{"candidates":[{"content":{"parts":[{"text":"salut"}]},"finishReason":"STOP"}]}""")

            override suspend fun stream(model: String, request: JsonObject, onEvent: suspend (JsonObject) -> Unit) {
                onEvent(obj("""{"candidates":[{"finishReason":"STOP"}]}"""))
            }
        }
        val voice = RestVoice(
            recorder = object : MicRecorder {
                override fun start() {}
                override fun stop() = ShortArray(1600) { 5 }
            },
            output = object : SpeechOutput {
                override suspend fun play(source: suspend (suspend (ByteArray) -> Unit) -> Unit) = source { }
                override fun stop() {}
            },
            transport = transport,
            textModel = { "models/text" },
            speechModel = { "models/tts" },
            voice = { "Kore" },
            sendText = { null },
            lastReply = { "Bonjour" },
        )
        voice.startRecording()
        assertEquals(ERROR_INVALID_AUDIO, voice.finishRecording())
        assertEquals(VoiceStage.IDLE, voice.stage.value)
    }
}
