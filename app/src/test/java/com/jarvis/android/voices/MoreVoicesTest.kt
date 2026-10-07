package com.jarvis.android.voices

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MoreVoicesTest {
    @Test
    fun `the edge token is the one edge-tts makes for the same five minutes`() {
        // computed with edge-tts's own algorithm (drm.py) for 2026-10-07 20:00:00 UTC
        val expected = "AD1CE50BA06F94DE9F3EC08A056A0E793B0E885C816BAFEBE7467CB8E1E7387C"
        assertEquals(expected, edgeSecMsGec(1_791_403_200L))
        assertEquals(expected, edgeSecMsGec(1_791_403_200L + 299))
        assertTrue(edgeSecMsGec(1_791_403_200L + 300) != expected)
        assertEquals("Wed Oct 07 2026 20:00:00 GMT+0000 (Coordinated Universal Time)", edgeDate(1_791_403_200L))
        val url = edgeUrl(1_791_403_200L, "abc")
        assertTrue(url.startsWith("wss://speech.platform.bing.com/") && url.contains("ConnectionId=abc") && url.contains("Sec-MS-GEC=$expected"))
    }

    @Test
    fun `the ssml escapes the text and names the voice`() {
        val m = edgeSsmlMessage("id1", 1_791_403_200L, "fr-FR-DeniseNeural", "Tom & Jerry <3\u000B")
        assertTrue(m.startsWith("X-RequestId:id1\r\nContent-Type:application/ssml+xml\r\nX-Timestamp:Wed Oct 07 2026 20:00:00 GMT+0000 (Coordinated Universal Time)Z\r\nPath:ssml\r\n\r\n"))
        assertTrue(m.contains("<voice name='fr-FR-DeniseNeural'>"))
        assertTrue(m.contains("Tom &amp; Jerry &lt;3 </prosody>"))
        assertTrue(edgeConfigMessage(0).contains("audio-24khz-48kbitrate-mono-mp3"))
    }

    @Test
    fun `binary messages give their audio, text messages their path`() {
        val header = "X-RequestId:1\r\nContent-Type:audio/mpeg\r\nPath:audio\r\n".toByteArray()
        val audio = byteArrayOf(1, 2, 3)
        val message = byteArrayOf(0, header.size.toByte()) + header + audio
        assertArrayEquals(audio, edgeAudio(message))
        assertNull(edgeAudio(byteArrayOf(0, header.size.toByte()) + header))
        assertNull(edgeAudio(byteArrayOf(0, 50, 1)))
        assertEquals("turn.end", edgePath("X-RequestId:1\r\nPath:turn.end\r\n\r\n{}"))
    }

    @Test
    fun `stored choices are read back`() {
        assertEquals(VoiceKind.GEMINI, voiceKind(""))
        assertEquals(VoiceKind.EDGE, voiceKind("edge:fr-FR-HenriNeural"))
        assertEquals(VoiceKind.ELEVEN, voiceKind(elevenChoice("abc", "Rachel")))
        assertEquals(VoiceKind.GEMINI, voiceKind("eleven:"))
        assertEquals("abc", voiceIdOf(elevenChoice("abc", "Rachel")))
        assertEquals("Rachel", voiceNameOf(elevenChoice("abc", "Rachel")))
        assertEquals("Henri", voiceNameOf("edge:fr-FR-HenriNeural"))
        assertEquals("fr-FR-HenriNeural", voiceIdOf("edge:fr-FR-HenriNeural"))
        assertEquals("com.google.android.tts" to "fr-fr-x-frd-local", phoneChoice("phone:com.google.android.tts|fr-fr-x-frd-local"))
        assertNull(phoneChoice("phone:|x"))
        assertNull(phoneChoice("edge:x"))
        assertEquals(EDGE_VOICES.size, EDGE_VOICES.map { it.id }.toSet().size)
    }

    @Test
    fun `the transcript is cut into sentences as it comes`() {
        val b = SentenceBuffer()
        assertEquals(emptyList<String>(), b.add("Bonjour M. Dupont, il fait 3.5"))
        assertEquals(listOf("Bonjour M. Dupont, il fait 3.5 degrés."), b.add(" degrés. Voulez"))
        assertEquals(listOf("Voulez-vous un rappel ?"), b.add("-vous un rappel ? "))
        assertEquals("D'accord", b.add("D'accord").let { b.flush() })
        assertNull(b.flush())
        val long = SentenceBuffer(50).add("un deux trois quatre cinq six sept huit neuf dix onze douze treize quatorze")
        assertTrue(long.isNotEmpty() && long.all { it.length <= 50 })
        assertEquals(emptyList<String>(), SentenceBuffer().add("... "))
    }

    @Test
    fun `elevenlabs voices and errors are read`() {
        val root = Json.parseToJsonElement("""{"voices":[{"voice_id":"v1","name":"Rachel","labels":{"gender":"female","accent":"american"}},{"name":"no id"}]}""").jsonObject
        val voices = parseElevenVoices(root)
        assertEquals(1, voices.size)
        assertEquals("eleven:v1|Rachel", voices[0].id)
        assertTrue(voices[0].female)
        assertEquals("eleven_flash_v2_5", elevenRequest("Salut")["model_id"]!!.jsonPrimitive.content)
        assertEquals("fr", elevenRequest("Salut")["language_code"]!!.jsonPrimitive.content)
        assertTrue(elevenError(401).contains("clé"))
    }

    @Test
    fun `phone voices that work offline come first, in the language asked`() {
        val list = listOf(
            PhoneVoice("g", "Google", "en-us-x-sfg-local", "en-US", true, 400),
            PhoneVoice("g", "Google", "fr-fr-x-frd-network", "fr-FR", false, 500),
            PhoneVoice("g", "Google", "fr-fr-x-frd-local", "fr-FR", true, 300),
            PhoneVoice("s", "SherpaTTS", "piper-fr-siwis", "fr-FR", true, 400),
        )
        assertEquals(listOf("piper-fr-siwis", "fr-fr-x-frd-local", "en-us-x-sfg-local"), sortPhoneVoices(list, "fr").map { it.name })
        assertEquals("FRD (fr-FR) · Google", phoneVoiceLabel(list[2]))
        assertEquals("piper-fr-siwis (fr-FR) · SherpaTTS", phoneVoiceLabel(list[3]))
    }

    @Test
    fun `stereo 48 kHz becomes mono 24 kHz`() {
        // two stereo frames per output sample: (100,300) (100,300) (200,400) (200,400) → mono 200,200,300,300 → 24 kHz: 200,300
        fun le(vararg s: Int) = s.flatMap { listOf((it and 0xFF).toByte(), (it shr 8).toByte()) }.toByteArray()
        val out = toMono24k(le(100, 300, 100, 300, 200, 400, 200, 400), 48_000, 2)
        assertArrayEquals(le(200, 300), out)
        assertArrayEquals(le(5, -5), toMono24k(le(5, -5), 24_000, 1))
    }
}
