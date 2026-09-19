package com.jarvis.android.rest

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

class SpeechTest {
    private fun obj(text: String) = Json.parseToJsonElement(text).jsonObject

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
    fun `wav header describes mono 16 bit pcm`() {
        val wav = pcm16ToWav(ByteArray(10), 16_000)
        val b = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(54, wav.size)
        assertEquals("RIFF", String(wav, 0, 4))
        assertEquals(46, b.getInt(4))
        assertEquals("WAVEfmt ", String(wav, 8, 8))
        assertEquals(1, b.getShort(20).toInt())
        assertEquals(1, b.getShort(22).toInt())
        assertEquals(16_000, b.getInt(24))
        assertEquals(32_000, b.getInt(28))
        assertEquals(16, b.getShort(34).toInt())
        assertEquals(10, b.getInt(40))
    }

    @Test
    fun `shorts become little endian bytes`() {
        assertArrayEquals(byteArrayOf(0x34, 0x12, 0xFF.toByte(), 0xFF.toByte()), shortsToPcm(shortArrayOf(0x1234, -1)))
    }

    @Test
    fun `transcription request carries the audio and the instruction`() {
        val request = buildTranscriptionRequest(ShortArray(100) { 1 })
        val part = request["contents"]!!.jsonArray[0].jsonObject["parts"]!!.jsonArray[0].jsonObject["inlineData"]!!.jsonObject
        assertEquals("audio/wav", part["mimeType"]!!.jsonPrimitive.content)
        assertEquals(244, Base64.getDecoder().decode(part["data"]!!.jsonPrimitive.content).size)
        assertTrue(request["systemInstruction"].toString().contains("verbatim"))
    }

    @Test
    fun `empty and overlong recordings are refused`() {
        assertEquals(ERROR_EMPTY_AUDIO, failure { buildTranscriptionRequest(ShortArray(0)) })
        assertEquals(ERROR_AUDIO_TOO_LARGE, failure { buildTranscriptionRequest(ShortArray(RECORD_SAMPLE_RATE * MAX_RECORD_SECONDS + 1)) })
    }

    @Test
    fun `speech response returns the raw pcm`() {
        val pcm = ByteArray(8) { it.toByte() }
        val reply = obj("""{"candidates":[{"content":{"parts":[{"inlineData":{"mimeType":"audio/L16;codec=pcm;rate=24000","data":"${Base64.getEncoder().encodeToString(pcm)}"}}]}}]}""")
        assertArrayEquals(pcm, parseSpeechResponse(reply))
    }

    @Test
    fun `speech response with another rate, mime or no audio is refused`() {
        val data = Base64.getEncoder().encodeToString(ByteArray(8))
        val badRate = obj("""{"candidates":[{"content":{"parts":[{"inlineData":{"mimeType":"audio/L16;rate=16000","data":"$data"}}]}}]}""")
        assertEquals(ERROR_INVALID_AUDIO, failure { parseSpeechResponse(badRate) })
        val badMime = obj("""{"candidates":[{"content":{"parts":[{"inlineData":{"mimeType":"audio/mpeg","data":"$data"}}]}}]}""")
        assertEquals(ERROR_INVALID_AUDIO, failure { parseSpeechResponse(badMime) })
        val textOnly = obj("""{"candidates":[{"content":{"parts":[{"text":"x"}]}}]}""")
        assertEquals(ERROR_INVALID_AUDIO, failure { parseSpeechResponse(textOnly) })
        assertEquals(ERROR_BLOCKED, failure { parseSpeechResponse(obj("""{"promptFeedback":{"blockReason":"SAFETY"}}""")) })
        assertEquals(ERROR_EMPTY, failure { parseSpeechResponse(obj("{}")) })
    }

    @Test
    fun `speech request is limited and needs text`() {
        assertEquals(ERROR_EMPTY, failure { buildSpeechRequest("   ", "Kore") })
        val text = buildSpeechRequest("a".repeat(MAX_SPOKEN_CHARS + 500), "Puck")["contents"]!!.jsonArray[0].jsonObject["parts"]!!
            .jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        assertEquals(MAX_SPOKEN_CHARS, text.length)
    }

    @Test
    fun `speech models are picked from list pages by name and method`() {
        val body = """{"models":[
            {"name":"models/gemini-x-flash","supportedGenerationMethods":["generateContent"]},
            {"name":"models/gemini-x-flash-tts","supportedGenerationMethods":["generateContent"]},
            {"name":"models/old-tts","supportedGenerationMethods":["countTokens"]}]}"""
        assertEquals(listOf("models/gemini-x-flash-tts"), parseSpeechModelNames(body))
        assertTrue(parseSpeechModelNames("""{"models":[]}""").isEmpty())
        assertTrue(parseSpeechModelNames("{}").isEmpty())
    }

    @Test
    fun `transcription of blank text means no speech`() {
        val blank = obj("""{"candidates":[{"content":{"parts":[{"text":""}]},"finishReason":"STOP"}]}""")
        assertEquals(ERROR_NO_SPEECH, failure { parseTranscription(blank) })
        val ok = obj("""{"candidates":[{"content":{"parts":[{"text":" bonjour "}]},"finishReason":"STOP"}]}""")
        assertEquals("bonjour", parseTranscription(ok))
    }
}
