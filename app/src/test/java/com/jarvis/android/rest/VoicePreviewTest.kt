package com.jarvis.android.rest

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class VoicePreviewTest {
    private fun audio(pcm: ByteArray) = Json.parseToJsonElement(
        """{"candidates":[{"content":{"parts":[{"inlineData":{"mimeType":"audio/L16;codec=pcm;rate=24000","data":"${Base64.getEncoder().encodeToString(pcm)}"}}]}}]}"""
    ).jsonObject

    private class Output : SpeechOutput {
        val played = mutableListOf<ByteArray>()
        override suspend fun play(source: suspend (suspend (ByteArray) -> Unit) -> Unit) {
            val all = java.io.ByteArrayOutputStream()
            source { all.write(it) }
            played += all.toByteArray()
        }
        override fun stop() {}
    }

    private class Transport(val reply: () -> JsonObject) : GenerateTransport {
        val requests = mutableListOf<JsonObject>()
        override suspend fun generate(model: String, request: JsonObject): JsonObject { requests += request; return reply() }
    }

    private val dir = kotlin.io.path.createTempDirectory("vp").toFile()

    @Test fun `a voice is fetched once, then played from the phone`() = runBlocking {
        val pcm = ByteArray(4800) { (it % 7).toByte() }
        val t = Transport { audio(pcm) }
        val out = Output()
        val p = VoicePreview(t, { "tts" }, out, dir)
        assertNull(p.play("Leda", english = false, name = "JARVIS"))
        assertNull(p.play("Leda", english = false, name = "JARVIS"))
        assertEquals(1, t.requests.size)
        assertEquals(2, out.played.size)
        assertArrayEquals(pcm, out.played[1])
        // the sample speaks as the assistant, in the voice asked for
        val req = t.requests[0].toString()
        assertTrue(req.contains("Leda") && req.contains("je suis Jarvis"))
        // another voice, or the other language, is another sample
        assertNull(p.play("Leda", english = true, name = "JARVIS"))
        assertNull(p.play("Kore", english = false, name = "JARVIS"))
        assertEquals(3, t.requests.size)
    }

    @Test fun `a failure is a message, and nothing is kept`() = runBlocking {
        val t = Transport { throw RestChatException("Clé API refusée.") }
        val p = VoicePreview(t, { "tts" }, Output(), dir)
        assertEquals("Clé API refusée.", p.play("Zephyr", english = false, name = "Jarvis"))
        val empty = Transport { Json.parseToJsonElement("""{"candidates":[{"content":{"parts":[]}}]}""").jsonObject }
        val q = VoicePreview(empty, { "tts" }, Output(), dir)
        assertTrue(q.play("Zephyr", english = false, name = "Jarvis") != null)
        assertTrue(java.io.File(dir, "voice_samples").listFiles().orEmpty().none { it.name.startsWith("Zephyr") })
    }
}
