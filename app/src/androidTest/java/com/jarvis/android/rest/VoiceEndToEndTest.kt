package com.jarvis.android.rest

import android.Manifest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Base64
import kotlin.math.PI
import kotlin.math.sin

/** Push-to-talk on the real recorder and player, with the network replaced by a script. */
@RunWith(AndroidJUnit4::class)
class VoiceEndToEndTest {
    @get:Rule
    val permission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun obj(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun audioEvent(pcm: ByteArray): JsonObject {
        val data = Base64.getEncoder().encodeToString(pcm)
        return obj("""{"candidates":[{"content":{"parts":[{"inlineData":{"mimeType":"audio/L16;codec=pcm;rate=24000","data":"$data"}}]}}]}""")
    }

    private class ScriptedTransport(val chunks: List<ByteArray>, val encode: (ByteArray) -> JsonObject) : GenerateTransport {
        val requests = mutableListOf<JsonObject>()

        override suspend fun generate(model: String, request: JsonObject): JsonObject {
            requests += request
            return Json.parseToJsonElement(
                """{"candidates":[{"content":{"parts":[{"text":"quel temps fait-il"}]},"finishReason":"STOP"}]}"""
            ).jsonObject
        }

        override suspend fun stream(model: String, request: JsonObject, onEvent: suspend (JsonObject) -> Unit) {
            requests += request
            chunks.forEach { onEvent(encode(it)) }
        }
    }

    @Test
    fun recordTranscribeSendAndSpeak() = runBlocking {
        val tone = shortsToPcm(ShortArray(SPEECH_SAMPLE_RATE / 2) { (sin(2 * PI * 440 * it / SPEECH_SAMPLE_RATE) * 3000).toInt().toShort() })
        val third = (tone.size / 3) and 1.inv()
        val transport = ScriptedTransport(
            listOf(tone.copyOfRange(0, third), tone.copyOfRange(third, 2 * third), tone.copyOfRange(2 * third, tone.size)),
        ) { audioEvent(it) }
        val sent = mutableListOf<String>()
        val metrics = mutableListOf<String>()
        val voice = RestVoice(
            recorder = AudioRecorder(context),
            output = AudioPlayer(context),
            transport = transport,
            textModel = { "models/text" },
            speechModel = { "models/tts" },
            voice = { "Kore" },
            sendText = { sent += it; null },
            lastReply = { "Il fait beau." },
            onMetrics = { metrics += it },
        )

        assertNull(voice.startRecording())
        assertEquals(VoiceStage.RECORDING, voice.stage.value)
        Thread.sleep(600)
        assertNull(voice.finishRecording())

        assertEquals(listOf("quel temps fait-il"), sent)
        assertEquals(VoiceStage.IDLE, voice.stage.value)
        // First request is the transcription: a WAV recording; the second asks for speech.
        val audioPart = transport.requests[0]["contents"]!!.jsonArray[0].jsonObject["parts"]!!.jsonArray[0]
            .jsonObject["inlineData"]!!.jsonObject
        assertEquals("audio/wav", audioPart["mimeType"]!!.jsonPrimitive.content)
        val wav = Base64.getDecoder().decode(audioPart["data"]!!.jsonPrimitive.content)
        assertEquals("RIFF", String(wav, 0, 4))
        assertTrue("wav : ${wav.size} octets", wav.size > 44 + 16_000)
        assertEquals(2, transport.requests.size)
        assertTrue(metrics.first().startsWith("Voix : premier son"))
        assertTrue(metrics.last().contains("3 morceaux"))
    }
}
