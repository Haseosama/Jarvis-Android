package com.jarvis.android.rest

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GroundingTest {
    private fun obj(text: String) = Json.parseToJsonElement(text).jsonObject

    private val answer = obj(
        """{"candidates":[{"content":{"parts":[{"text":"Il fait 21 °C à Paris."}]},"finishReason":"STOP",
        "groundingMetadata":{"groundingChunks":[
          {"web":{"uri":"https://meteo.example/paris","title":"Météo Paris"}},
          {"web":{"uri":"https://meteo.example/paris","title":"Doublon"}},
          {"web":{"uri":"javascript:alert(1)","title":"Piège"}},
          {"web":{"uri":"https://autre.example/x"}},
          {"retrievedContext":{"uri":"gs://x"}}]}}]}"""
    )

    @Test
    fun `request asks for google search and carries the query`() {
        val request = buildGroundedRequest("météo Paris")
        assertTrue(request["tools"]!!.jsonArray[0].jsonObject.containsKey("google_search"))
        assertFalse(request.toString().contains("functionDeclarations"))
        assertEquals("météo Paris", request["contents"]!!.jsonArray[0].jsonObject["parts"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `sources are web links only, without duplicates`() {
        val sources = groundingSources(answer)
        assertEquals(listOf("https://meteo.example/paris", "https://autre.example/x"), sources.map { it.url })
        assertEquals("https://autre.example/x", sources[1].title)
    }

    @Test
    fun `the answer is followed by numbered sources`() {
        val text = formatGroundedAnswer(answer)
        assertTrue(text.startsWith("Il fait 21 °C à Paris."))
        assertTrue(text.contains("1. Météo Paris — https://meteo.example/paris"))
        assertFalse(text.contains("javascript:"))
    }

    @Test
    fun `an answer without sources is returned as is`() {
        val plain = obj("""{"candidates":[{"content":{"parts":[{"text":"Bonjour"}]},"finishReason":"STOP"}]}""")
        assertEquals("Bonjour", formatGroundedAnswer(plain))
    }

    @Test
    fun `sources are capped`() {
        val chunks = (1..12).joinToString(",") { """{"web":{"uri":"https://s.example/$it","title":"S$it"}}""" }
        val many = obj("""{"candidates":[{"content":{"parts":[{"text":"x"}]},"finishReason":"STOP","groundingMetadata":{"groundingChunks":[$chunks]}}]}""")
        assertEquals(MAX_SOURCES, groundingSources(many).size)
    }

    @Test
    fun `no text is an error so the caller can fall back`() {
        try {
            formatGroundedAnswer(obj("{}"))
            fail("erreur attendue")
        } catch (e: RestChatException) {
            assertEquals(ERROR_EMPTY, e.message)
        }
    }
}
