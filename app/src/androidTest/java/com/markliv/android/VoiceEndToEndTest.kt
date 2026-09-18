package com.markliv.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

@RunWith(AndroidJUnit4::class)
class VoiceEndToEndTest {

    @Test
    fun offlineSpeechResponseHasPcmWavHeader() {
        val pcm = byteArrayOf(0x34, 0x12, -1, -1)
        val inlineData = JSONObject()
            .put("mimeType", "audio/L16;codec=pcm;rate=24000")
            .put("data", Base64.getEncoder().encodeToString(pcm))
        val parts = JSONArray().put(JSONObject().put("inlineData", inlineData))
        val candidate = JSONObject().put("finishReason", "STOP")
            .put("content", JSONObject().put("parts", parts))
        val body = JSONObject().put("candidates", JSONArray().put(candidate)).toString()
        val wav = GeminiClient().parseSpeechResponseJson(body)
        val header = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(44 + pcm.size, wav.size)
        assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII))
        assertEquals(36 + pcm.size, header.getInt(4))
        assertEquals("WAVEfmt ", String(wav, 8, 8, Charsets.US_ASCII))
        assertEquals(16, header.getInt(16))
        assertEquals(1, header.getShort(20).toInt())
        assertEquals(1, header.getShort(22).toInt())
        assertEquals(24000, header.getInt(24))
        assertEquals(48000, header.getInt(28))
        assertEquals(2, header.getShort(32).toInt())
        assertEquals(16, header.getShort(34).toInt())
        assertEquals("data", String(wav, 36, 4, Charsets.US_ASCII))
        assertEquals(pcm.size, header.getInt(40))
        assertArrayEquals(pcm, wav.copyOfRange(44, wav.size))
    }
}
