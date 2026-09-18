package com.markliv.android

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

class AudioTranscriberTest {

    @Test
    fun encodesLittleEndianWithPredictableBase64() {
        val encoded = AudioTranscriber.toPcmBase64(shortArrayOf(0x1234, -1))
        assertEquals("NBL//w==", encoded)
        assertArrayEquals(byteArrayOf(0x34, 0x12, -1, -1), Base64.getDecoder().decode(encoded))
    }

    @Test
    fun encodesEmptyArray() {
        assertEquals("", AudioTranscriber.toPcmBase64(shortArrayOf()))
    }

    @Test
    fun roundTripsEveryShortWithManualLittleEndianDecoding() {
        val samples = ShortArray(65536) { (it + Short.MIN_VALUE).toShort() }
        val encoded = AudioTranscriber.toPcmBase64(samples)
        assertFalse(encoded.contains('\n'))
        assertFalse(encoded.contains('\r'))
        val bytes = Base64.getDecoder().decode(encoded)
        val decoded = ShortArray(bytes.size / 2) { index ->
            ((bytes[index * 2].toInt() and 0xFF) or
                ((bytes[index * 2 + 1].toInt() and 0xFF) shl 8)).toShort()
        }
        assertArrayEquals(samples, decoded)
    }

    @Test
    fun buildsWavPayloadWithVerbatimTranscriptionInstruction() {
        val request = JSONObject(AudioTranscriber.buildAudioRequestJson(shortArrayOf(0x1234, -1), 16000).toString())
        val contents = request.getJSONArray("contents")
        assertEquals(1, contents.length())
        val content = contents.getJSONObject(0)
        assertEquals("user", content.getString("role"))
        val parts = content.getJSONArray("parts")
        assertEquals(1, parts.length())
        assertFalse(parts.getJSONObject(0).has("text"))
        val inlineData = parts.getJSONObject(0).getJSONObject("inlineData")
        assertEquals("audio/wav", inlineData.getString("mimeType"))
        assertWav(Base64.getDecoder().decode(inlineData.getString("data")), 16000, byteArrayOf(0x34, 0x12, -1, -1))
        val instruction = request.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text")
        assertEquals(AudioTranscriber.TRANSCRIPTION_INSTRUCTION, instruction)
        assertTrue(instruction.contains("verbatim strict"))
        assertTrue(instruction.contains("Ne réponds pas aux questions"))
        assertTrue(instruction.contains("langue d'origine"))
        assertFalse(instruction.contains("Mark LIV"))
        assertFalse(instruction == GeminiClient.SYSTEM_INSTRUCTION)
        assertFalse(request.has("key"))
        assertFalse(request.has("tools"))
    }

    @Test
    fun rejectsInvalidAudioBeforeEncoding() {
        assertSafeError(GeminiClient.ERROR_EMPTY_AUDIO) {
            AudioTranscriber.buildAudioRequestJson(shortArrayOf(), 16000)
        }
        listOf(-1, 0, 7999, 48001, Int.MAX_VALUE).forEach { rate ->
            assertSafeError("Fréquence d'enregistrement audio non prise en charge.") {
                AudioTranscriber.buildAudioRequestJson(shortArrayOf(1), rate)
            }
        }
        assertSafeError(GeminiClient.ERROR_AUDIO_TOO_LARGE) {
            AudioTranscriber.buildAudioRequestJson(ShortArray(16000 * 60 + 1), 16000)
        }
        assertSafeError(GeminiClient.ERROR_AUDIO_TOO_LARGE) {
            AudioTranscriber.buildAudioRequestJson(ShortArray(8000 * 60 + 1), 8000)
        }
        assertSafeError(GeminiClient.ERROR_AUDIO_TOO_LARGE) {
            AudioTranscriber.toPcmBase64(ShortArray(16000 * 60 + 1))
        }
    }

    @Test
    fun acceptsExactlySixtySeconds() {
        val samples = ShortArray(16000 * 60)
        val request = AudioTranscriber.buildAudioRequestJson(samples, 16000)
        val data = request.getJSONArray("contents").getJSONObject(0)
            .getJSONArray("parts").getJSONObject(0).getJSONObject("inlineData").getString("data")
        assertEquals(44 + samples.size * 2, Base64.getDecoder().decode(data).size)
    }

    @Test
    fun delegatesToAudioGenerationWithoutNetwork() = runBlocking {
        val samples = shortArrayOf(0x1234, -1)
        var called = false
        val client = object : GeminiClient() {
            override suspend fun generateWithAudio(apiKey: String, model: String, samples: ShortArray, sampleRate: Int): String {
                called = true
                assertEquals("test-key", apiKey)
                assertEquals("gemini", model)
                assertArrayEquals(shortArrayOf(0x1234, -1), samples)
                assertEquals(16000, sampleRate)
                return "Bonjour"
            }
        }
        assertEquals("Bonjour", AudioTranscriber.transcribe(client, "test-key", "gemini", samples, 16000))
        assertTrue(called)
    }

    @Test
    fun validatesCredentialsBeforeNetworkAccess() {
        val client = GeminiClient()
        assertSafeError(GeminiClient.ERROR_INVALID_MODEL) {
            runBlocking { client.generateWithAudio("secret", "../secret", shortArrayOf(1), 16000) }
        }
        assertSafeError(GeminiClient.ERROR_MISSING_KEY) {
            runBlocking { client.generateWithAudio("", "gemini", shortArrayOf(1), 16000) }
        }
        assertSafeError(GeminiClient.ERROR_INVALID_KEY) {
            runBlocking { client.generateWithAudio("secret\r\n", "gemini", shortArrayOf(1), 16000) }
        }
        assertSafeError(GeminiClient.ERROR_EMPTY_AUDIO) {
            runBlocking { client.generateWithAudio("secret", "gemini", shortArrayOf(), 16000) }
        }
    }

    @Test
    fun wavUsesProvidedRecordingRate() {
        listOf(8000, 16000, 24000, 44100, 48000).forEach { rate ->
            val request = AudioTranscriber.buildAudioRequestJson(shortArrayOf(0x1234, -1), rate)
            val data = request.getJSONArray("contents").getJSONObject(0).getJSONArray("parts")
                .getJSONObject(0).getJSONObject("inlineData").getString("data")
            assertWav(Base64.getDecoder().decode(data), rate, byteArrayOf(0x34, 0x12, -1, -1))
        }
    }

    @Test
    fun buildsSpeechPayloadWithoutAssistantHistoryOrModelGuess() {
        val text = " Bonjour \"Mark\"\n"
        val request = GeminiClient().buildSpeechRequestJson(text)
        val contents = request.getJSONArray("contents")
        assertEquals(1, contents.length())
        assertEquals("user", contents.getJSONObject(0).getString("role"))
        val parts = contents.getJSONObject(0).getJSONArray("parts")
        assertEquals(1, parts.length())
        assertEquals(text, parts.getJSONObject(0).getString("text"))
        val config = request.getJSONObject("generationConfig")
        assertEquals(1, config.getJSONArray("responseModalities").length())
        assertEquals("AUDIO", config.getJSONArray("responseModalities").getString(0))
        assertEquals("Kore", config.getJSONObject("speechConfig").getJSONObject("voiceConfig")
            .getJSONObject("prebuiltVoiceConfig").getString("voiceName"))
        listOf("key", "apiKey", "model", "tools", "system_instruction", "systemInstruction").forEach {
            assertFalse(request.has(it))
        }
        assertSafeError(GeminiClient.ERROR_EMPTY_SPEECH_TEXT) { GeminiClient().buildSpeechRequestJson(" \n") }
    }

    @Test
    fun validatesSpeechInputsBeforeNetwork() {
        val client = GeminiClient()
        listOf("", "../secret", "model?key=secret", "model/secret", "model\n").forEach { model ->
            assertSafeError(GeminiClient.ERROR_INVALID_MODEL) {
                runBlocking { client.generateSpeech("secret", model, "Bonjour") }
            }
        }
        assertSafeError(GeminiClient.ERROR_MISSING_KEY) {
            runBlocking { client.generateSpeech("", "configured-model", "Bonjour") }
        }
        listOf("secret\r\n", "secret value", "secrét").forEach { key ->
            assertSafeError(GeminiClient.ERROR_INVALID_KEY) {
                runBlocking { client.generateSpeech(key, "configured-model", "Bonjour") }
            }
        }
        assertSafeError(GeminiClient.ERROR_EMPTY_SPEECH_TEXT) {
            runBlocking { client.generateSpeech("secret", "configured-model", "  ") }
        }
    }

    @Test
    fun parsesApiPcmInto24kMono16BitWav() {
        val pcm = byteArrayOf(0x34, 0x12, -1, -1)
        listOf("audio/L16;codec=pcm;rate=24000", "audio/pcm;rate=24000",
            "Audio/L16; channels=1; rate=24000; bits=16").forEach { mime ->
            val wav = GeminiClient().parseSpeechResponseJson(speechResponse(audioPart(mime, pcm)).toString())
            assertWav(wav, 24000, pcm)
            assertTrue(AudioTranscriber.isSpeechWav(wav))
        }
    }

    @Test
    fun joinsAudioPartsAndIgnoresThoughtsAndText() {
        val body = speechResponse(
            audioPart().put("thought", true),
            JSONObject().put("text", "secret"),
            audioPart(pcm = byteArrayOf(0x34, 0x12)),
            audioPart(pcm = byteArrayOf(-1, -1))
        )
        assertWav(GeminiClient().parseSpeechResponseJson(body.toString()), 24000, byteArrayOf(0x34, 0x12, -1, -1))
    }

    @Test
    fun rejectsEmptyMalformedAndTruncatedSpeech() {
        val client = GeminiClient()
        listOf("", " ", "{}", "{\"candidates\":[]}").forEach {
            assertSafeError(GeminiClient.ERROR_EMPTY) { client.parseSpeechResponseJson(it) }
        }
        listOf("secret", "{", "{\"error\":{\"message\":\"secret\"}}").forEach {
            assertSafeError(GeminiClient.ERROR_MALFORMED) { client.parseSpeechResponseJson(it) }
        }
        listOf("MAX_TOKENS", "OTHER", "", "secret").forEach { reason ->
            val body = speechResponse(audioPart())
            body.getJSONArray("candidates").getJSONObject(0).put("finishReason", reason)
            assertSafeError(GeminiClient.ERROR_MALFORMED) { client.parseSpeechResponseJson(body.toString()) }
        }
        listOf(speechResponse(), speechResponse(JSONObject().put("text", "secret")),
            speechResponse(audioPart().put("thought", true)), speechResponse(audioPart(pcm = byteArrayOf())),
            speechResponse(audioPart(pcm = byteArrayOf(1)))).forEach {
            assertSafeError(GeminiClient.ERROR_INVALID_AUDIO) { client.parseSpeechResponseJson(it.toString()) }
        }
        listOf("!!!", "AQ", "AR==", "AQ==\n").forEach { encoded ->
            val part = audioPart()
            part.getJSONObject("inlineData").put("data", encoded)
            assertSafeError(GeminiClient.ERROR_MALFORMED) {
                client.parseSpeechResponseJson(speechResponse(part).toString())
            }
        }
        val part = audioPart()
        part.getJSONObject("inlineData").put("data", 42)
        assertSafeError(GeminiClient.ERROR_INVALID_AUDIO) {
            client.parseSpeechResponseJson(speechResponse(part).toString())
        }
    }

    @Test
    fun rejectsUnsupportedOrAmbiguousSpeechFormats() {
        listOf("audio/wav", "audio/mpeg", "audio/L16xyz;rate=24000", "audio/L16", "audio/pcm;rate=",
            "audio/L16;rate=abc", "audio/L16;rate=16000", "audio/L16;rate=48000", "audio/L16;rate=0",
            "audio/L16;rate=24000;rate=24000", "audio/L16;rate=24000;channels=2",
            "audio/pcm;rate=24000;bits=8", "audio/L16;codec=mp3;rate=24000",
            "audio/pcm;rate=24000;endian=big", "audio/pcm;rate=24000;").forEach { mime ->
            assertSafeError(GeminiClient.ERROR_INVALID_AUDIO) {
                GeminiClient().parseSpeechResponseJson(speechResponse(audioPart(mime)).toString())
            }
        }
    }

    @Test
    fun rejectsBlockedSpeechEvenWhenAudioExists() {
        val client = GeminiClient()
        val prompt = speechResponse(audioPart()).put("promptFeedback", JSONObject().put("blockReason", "SAFETY"))
        assertSafeError(GeminiClient.ERROR_BLOCKED) { client.parseSpeechResponseJson(prompt.toString()) }
        listOf("SAFETY", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII", "RECITATION", "IMAGE_SAFETY").forEach { reason ->
            val body = speechResponse(audioPart())
            body.getJSONArray("candidates").getJSONObject(0).put("finishReason", reason)
            assertSafeError(GeminiClient.ERROR_BLOCKED) { client.parseSpeechResponseJson(body.toString()) }
        }
        val body = speechResponse(audioPart())
        body.getJSONArray("candidates").getJSONObject(0)
            .put("safetyRatings", JSONArray().put(JSONObject().put("blocked", true)))
        assertSafeError(GeminiClient.ERROR_BLOCKED) { client.parseSpeechResponseJson(body.toString()) }
    }

    @Test
    fun boundsSpeechBodyAndClosesStream() {
        var closed = false
        val stream = object : ByteArrayInputStream(ByteArray(17)) {
            override fun close() {
                closed = true
                super.close()
            }
        }
        assertSafeError(GeminiClient.ERROR_TOO_LARGE) { GeminiClient().readBody(stream, 16) }
        assertTrue(closed)
        assertSafeError(GeminiClient.ERROR_TOO_LARGE) { GeminiClient().parseSpeechResponseJson(" ".repeat(12_000_001)) }
    }

    @Test
    fun playerValidationRejectsDamagedWavHeaders() {
        val wav = AudioTranscriber.pcmToWav(byteArrayOf(1, 0), 24000)
        assertTrue(AudioTranscriber.isSpeechWav(wav))
        listOf(0, 4, 8, 12, 16, 20, 22, 24, 28, 32, 34, 36, 40).forEach { offset ->
            val damaged = wav.copyOf()
            damaged[offset] = (damaged[offset].toInt() xor 1).toByte()
            assertFalse(AudioTranscriber.isSpeechWav(damaged))
        }
        assertFalse(AudioTranscriber.isSpeechWav(byteArrayOf()))
        assertFalse(AudioTranscriber.isSpeechWav(wav.copyOf(44)))
        assertFalse(AudioTranscriber.isSpeechWav(wav.copyOf(wav.size - 1)))
        assertFalse(AudioTranscriber.isSpeechWav(AudioTranscriber.pcmToWav(byteArrayOf(1, 0), 16000)))
    }

    private fun audioPart(mime: String = "audio/L16;rate=24000", pcm: ByteArray = byteArrayOf(1, 0)): JSONObject =
        JSONObject().put("inlineData", JSONObject().put("mimeType", mime)
            .put("data", Base64.getEncoder().encodeToString(pcm)))

    private fun speechResponse(vararg parts: JSONObject): JSONObject {
        val array = JSONArray()
        parts.forEach { array.put(it) }
        return JSONObject().put("candidates", JSONArray().put(JSONObject().put("finishReason", "STOP")
            .put("content", JSONObject().put("parts", array))))
    }

    private fun assertWav(wav: ByteArray, rate: Int, pcm: ByteArray) {
        val header = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(44 + pcm.size, wav.size)
        assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII))
        assertEquals(36 + pcm.size, header.getInt(4))
        assertEquals("WAVEfmt ", String(wav, 8, 8, Charsets.US_ASCII))
        assertEquals(16, header.getInt(16))
        assertEquals(1, header.getShort(20).toInt())
        assertEquals(1, header.getShort(22).toInt())
        assertEquals(rate, header.getInt(24))
        assertEquals(rate * 2, header.getInt(28))
        assertEquals(2, header.getShort(32).toInt())
        assertEquals(16, header.getShort(34).toInt())
        assertEquals("data", String(wav, 36, 4, Charsets.US_ASCII))
        assertEquals(pcm.size, header.getInt(40))
        assertArrayEquals(pcm, wav.copyOfRange(44, wav.size))
    }

    private fun assertSafeError(expected: String, action: () -> Unit) {
        try {
            action()
            throw AssertionError("GeminiException attendue")
        } catch (error: GeminiException) {
            assertEquals(expected, error.message)
            var cause = error.cause
            while (cause != null) {
                assertTrue(cause is GeminiException)
                assertEquals(expected, cause.message)
                cause = cause.cause
            }
            assertNull(cause)
            assertTrue(error.suppressed.isEmpty())
            assertFalse(error.toString().contains("secret"))
        }
    }
}
