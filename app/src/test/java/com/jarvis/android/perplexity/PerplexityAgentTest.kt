package com.jarvis.android.perplexity

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PerplexityAgentTest {
    @Test
    fun `the request carries a preset, the question in french, and only the options asked for`() {
        val plain = buildPerplexityRequest("Prix du Livret A ?", "low")
        assertEquals("low", plain["preset"]!!.jsonPrimitive.content)
        assertEquals("Prix du Livret A ?", plain["input"]!!.jsonPrimitive.content)
        assertEquals("fr", plain["language_preference"]!!.jsonPrimitive.content)
        assertNull(plain["previous_response_id"])
        assertNull(plain["tools"])

        val follow = buildPerplexityRequest("Et à Lyon ?", "fast", "resp_123", listOf("service-public.fr"))
        assertEquals("resp_123", follow["previous_response_id"]!!.jsonPrimitive.content)
        val tools = follow["tools"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("web_search", "fetch_url"), tools.map { it["type"]!!.jsonPrimitive.content })
        assertEquals("service-public.fr", tools[0]["filters"]!!.jsonObject["search_domain_filter"]!!.jsonArray[0].jsonPrimitive.content)
    }

    @Test
    fun `depth words map to presets, low by default`() {
        assertEquals("low", perplexityPreset(""))
        assertEquals("low", perplexityPreset("normale"))
        assertEquals("fast", perplexityPreset("Rapide"))
        assertEquals("medium", perplexityPreset("approfondie"))
        assertEquals(listOf("lemonde.fr", "www.service-public.fr"), parseDomains("https://lemonde.fr/, www.service-public.fr ; pas un site"))
    }

    private val response = """
        {"id":"resp_abc","object":"response","created_at":1,"status":"completed","model":"openai/gpt-6-luna",
         "output":[
           {"type":"search_results","queries":["livret a taux"],"results":[
             {"id":1,"url":"https://example.org/a","title":"Source A","snippet":"..."},
             {"id":2,"url":"https://example.org/b","title":"Source B","snippet":"..."},
             {"id":3,"url":"ftp://bad","title":"Bad","snippet":"..."}]},
           {"type":"message","id":"m1","status":"completed","role":"assistant","content":[
             {"type":"output_text","text":"Le taux est de 2,4 % [web:2].","annotations":[]},
             {"type":"output_text","text":" Il change en février.[web:1]","annotations":[]}]}],
         "usage":{"input_tokens":10,"output_tokens":20,"total_tokens":30}}
    """.trimIndent()

    @Test
    fun `the answer is the output_text parts joined, markers removed, cited sources first`() {
        val answer = parsePerplexityResponse(Json.parseToJsonElement(response).jsonObject)
        assertEquals("resp_abc", answer.id)
        assertEquals("Le taux est de 2,4 %. Il change en février.", answer.text)
        assertEquals(listOf("https://example.org/b", "https://example.org/a"), answer.sources.map { it.url })
        val shown = formatPerplexityAnswer("Livret A", answer)
        assertTrue(shown.startsWith("Réponse de Perplexity pour « Livret A » :\nLe taux"))
        assertTrue(shown.endsWith("Sources :\n1. Source B — https://example.org/b\n2. Source A — https://example.org/a"))
    }

    @Test
    fun `url citations are kept and an empty or failed response is an error`() {
        val cited = parsePerplexityResponse(Json.parseToJsonElement("""
            {"id":"r","status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"Oui.",
             "annotations":[{"type":"url_citation","start_index":0,"end_index":4,"title":"T","url":"https://t.example"}]}]}]}
        """).jsonObject)
        assertEquals(listOf("https://t.example"), cited.sources.map { it.url })

        val failed = runCatching {
            parsePerplexityResponse(Json.parseToJsonElement("""{"id":"r","status":"failed","output":[],"error":{"message":"model overloaded"}}""").jsonObject)
        }.exceptionOrNull()
        assertTrue(failed is PerplexityException)
        assertEquals("model overloaded", failed!!.message)
        assertTrue(runCatching { parsePerplexityResponse(Json.parseToJsonElement("""{"status":"incomplete"}""").jsonObject) }.exceptionOrNull() is PerplexityException)
    }

    @Test
    fun `http errors say what to do, and retry-after is read in seconds only`() {
        assertTrue(perplexityHttpError(401, null).contains("console.perplexity.ai"))
        assertTrue(perplexityHttpError(429, 30).contains("30 s"))
        assertFalse(perplexityHttpError(429, null).contains(" s."))
        assertEquals(7L, retryAfterSeconds(" 7 "))
        assertNull(retryAfterSeconds("Wed, 21 Oct 2026 07:28:00 GMT"))
        assertNull(retryAfterSeconds(null))
    }
}
