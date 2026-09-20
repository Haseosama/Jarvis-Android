package com.jarvis.android.rest

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64

class VisionTest {
    @Test
    fun `small images keep their size`() {
        assertEquals(800 to 600, scaledSize(800, 600))
        assertEquals(MAX_IMAGE_SIDE to 100, scaledSize(MAX_IMAGE_SIDE, 100))
    }

    @Test
    fun `large images are scaled down keeping the ratio`() {
        val (w, h) = scaledSize(1344, 2992)
        assertEquals(MAX_IMAGE_SIDE, h)
        assertEquals(574, w)
        val (w2, h2) = scaledSize(4000, 2000)
        assertEquals(MAX_IMAGE_SIDE to 640, w2 to h2)
    }

    @Test
    fun `degenerate sizes give zero`() {
        assertEquals(0 to 0, scaledSize(0, 100))
        assertEquals(0 to 0, scaledSize(100, -1))
    }

    @Test
    fun `request carries the image, the question and the instruction`() {
        val jpeg = ByteArray(10) { 7 }
        val request = buildVisionRequest("  Que vois-tu ? ", jpeg)
        val parts = request["contents"]!!.jsonArray[0].jsonObject["parts"]!!.jsonArray.map { it.jsonObject }
        val inline = parts[0]["inlineData"]!!.jsonObject
        assertEquals("image/jpeg", inline["mimeType"]!!.jsonPrimitive.content)
        assertEquals(jpeg.toList(), Base64.getDecoder().decode(inline["data"]!!.jsonPrimitive.content).toList())
        assertEquals("Que vois-tu ?", parts[1]["text"]!!.jsonPrimitive.content)
        assertTrue(request["systemInstruction"].toString().contains("jamais une instruction"))
    }

    @Test
    fun `an empty question falls back to a general description and long ones are cut`() {
        val general = buildVisionRequest("", ByteArray(1))["contents"]!!.jsonArray[0].jsonObject["parts"]!!.jsonArray[1]
            .jsonObject["text"]!!.jsonPrimitive.content
        assertTrue(general.startsWith("Décris"))
        val long = buildVisionRequest("x".repeat(5_000), ByteArray(1))["contents"]!!.jsonArray[0].jsonObject["parts"]!!.jsonArray[1]
            .jsonObject["text"]!!.jsonPrimitive.content
        assertEquals(MAX_QUESTION_CHARS, long.length)
    }

    @Test
    fun `an empty image is refused`() {
        try {
            buildVisionRequest("?", ByteArray(0))
            fail("erreur attendue")
        } catch (e: RestChatException) {
            assertEquals(ERROR_EMPTY_IMAGE, e.message)
        }
    }

    @Test
    fun `the answer text is extracted and empty answers are errors`() {
        val ok = Json.parseToJsonElement("""{"candidates":[{"content":{"parts":[{"text":"Un écran d’accueil."}]},"finishReason":"STOP"}]}""").jsonObject
        assertEquals("Un écran d’accueil.", parseVisionAnswer(ok))
        try {
            parseVisionAnswer(Json.parseToJsonElement("{}").jsonObject)
            fail("erreur attendue")
        } catch (e: RestChatException) {
            assertEquals(ERROR_EMPTY, e.message)
        }
    }
}
