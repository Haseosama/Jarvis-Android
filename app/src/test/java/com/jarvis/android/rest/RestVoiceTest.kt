package com.jarvis.android.rest

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class RestVoiceTest {
    private fun obj(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun textReply(text: String) =
        obj("""{"candidates":[{"content":{"role":"model","parts":[{"text":"$text"}]},"finishReason":"STOP"}]}""")

    private fun audioReply(pcm: ByteArray, mime: String = "audio/L16;codec=pcm;rate=24000") = obj(
        """{"candidates":[{"content":{"parts":[{"inlineData":{"mimeType":"$mime","data":"${Base64.getEncoder().encodeToString(pcm)}"}}]},"finishReason":"STOP"}]}"""
    )

    private class FakeRecorder(var samples: ShortArray = ShortArray(1600) { 100 }, var startError: String? = null) : MicRecorder {
        var running = false
        override fun start() {
            startError?.let { throw RestChatException(it) }
            running = true
        }

        override fun stop(): ShortArray {
            running = false
            return samples
        }
    }

    private class FakeOutput : SpeechOutput {
        val played = mutableListOf<ByteArray>()
        override suspend fun play(pcm: ByteArray) {
            played += pcm
        }

        override fun stop() {}
    }

    private class ScriptedTransport(private val replies: MutableList<() -> JsonObject>) : GenerateTransport {
        val models = mutableListOf<String>()
        val requests = mutableListOf<JsonObject>()
        override suspend fun generate(model: String, request: JsonObject): JsonObject {
            models += model
            requests += request
            return replies.removeAt(0)()
        }
    }

    private class Harness(
        replies: List<() -> JsonObject>,
        val recorder: FakeRecorder = FakeRecorder(),
        var chatError: String? = null,
        var reply: String? = "Il fait beau.",
    ) {
        val output = FakeOutput()
        val transport = ScriptedTransport(replies.toMutableList())
        val sent = mutableListOf<String>()
        val voice = RestVoice(
            recorder = recorder,
            output = output,
            transport = transport,
            textModel = { "models/text" },
            speechModel = { "models/tts" },
            voice = { "Kore" },
            sendText = { sent += it; chatError },
            lastReply = { reply },
        )
    }

    @Test
    fun `full round trip transcribes, sends, then speaks the answer`() = runBlocking {
        val pcm = ByteArray(480) { 3 }
        val h = Harness(listOf({ textReply("Quel temps fait-il") }, { audioReply(pcm) }))
        assertNull(h.voice.startRecording())
        assertEquals(VoiceStage.RECORDING, h.voice.stage.value)
        assertNull(h.voice.finishRecording())
        assertEquals(listOf("Quel temps fait-il"), h.sent)
        assertEquals(listOf("models/text", "models/tts"), h.transport.models)
        assertEquals(1, h.output.played.size)
        assertArrayEquals(pcm, h.output.played[0])
        assertEquals(VoiceStage.IDLE, h.voice.stage.value)
    }

    @Test
    fun `speech request uses the chosen voice and the answer text`() = runBlocking {
        val h = Harness(listOf({ textReply("salut") }, { audioReply(ByteArray(4)) }))
        h.voice.startRecording()
        h.voice.finishRecording()
        val request = h.transport.requests[1]
        assertEquals("Il fait beau.", request["contents"]!!.jsonArray[0].jsonObject["parts"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
        val voiceName = request["generationConfig"]!!.jsonObject["speechConfig"]!!.jsonObject["voiceConfig"]!!
            .jsonObject["prebuiltVoiceConfig"]!!.jsonObject["voiceName"]!!.jsonPrimitive.content
        assertEquals("Kore", voiceName)
    }

    @Test
    fun `start failure reports the message and stays idle`() {
        val h = Harness(emptyList(), FakeRecorder(startError = ERROR_MIC_UNAVAILABLE))
        assertEquals(ERROR_MIC_UNAVAILABLE, h.voice.startRecording())
        assertEquals(VoiceStage.IDLE, h.voice.stage.value)
    }

    @Test
    fun `second start while recording is refused`() {
        val h = Harness(emptyList())
        h.voice.startRecording()
        assertTrue(h.voice.startRecording() != null)
    }

    @Test
    fun `empty recording is reported and nothing is sent`() = runBlocking {
        val h = Harness(emptyList(), FakeRecorder(samples = ShortArray(0)))
        h.voice.startRecording()
        assertEquals(ERROR_EMPTY_AUDIO, h.voice.finishRecording())
        assertTrue(h.sent.isEmpty())
        assertEquals(VoiceStage.IDLE, h.voice.stage.value)
    }

    @Test
    fun `nothing intelligible is reported and nothing is sent`() = runBlocking {
        val h = Harness(listOf({ obj("""{"candidates":[{"content":{"parts":[{"text":"  "}]},"finishReason":"STOP"}]}""") }))
        h.voice.startRecording()
        assertEquals(ERROR_NO_SPEECH, h.voice.finishRecording())
        assertTrue(h.sent.isEmpty())
    }

    @Test
    fun `chat failure is reported and the answer is not spoken`() = runBlocking {
        val h = Harness(listOf({ textReply("bonjour") }), chatError = "Quota atteint")
        h.voice.startRecording()
        assertEquals("Quota atteint", h.voice.finishRecording())
        assertTrue(h.output.played.isEmpty())
        assertEquals(VoiceStage.IDLE, h.voice.stage.value)
    }

    @Test
    fun `speech failure is reported but the written answer is kept`() = runBlocking {
        val h = Harness(listOf({ textReply("bonjour") }, { throw RestChatException("Quota atteint (429)") }))
        h.voice.startRecording()
        assertEquals("Quota atteint (429)", h.voice.finishRecording())
        assertEquals(listOf("bonjour"), h.sent)
        assertEquals(VoiceStage.IDLE, h.voice.stage.value)
    }

    @Test
    fun `cancel drops the recording without a request`() {
        val h = Harness(emptyList())
        h.voice.startRecording()
        h.voice.cancelRecording()
        assertEquals(VoiceStage.IDLE, h.voice.stage.value)
        assertFalse(h.recorder.running)
        assertTrue(h.transport.requests.isEmpty())
    }

    @Test
    fun `finish without a recording does nothing`() = runBlocking {
        val h = Harness(emptyList())
        assertNull(h.voice.finishRecording())
        assertTrue(h.transport.requests.isEmpty())
    }
}
