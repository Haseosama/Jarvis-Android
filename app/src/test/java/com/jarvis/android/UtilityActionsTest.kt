package com.jarvis.android.actions

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UtilityActionsTest {
    @Test
    fun `browser accepts only well formed HTTP addresses without credentials`() {
        assertEquals("https://example.com/", normalizeBrowserUrl(" example.com "))
        assertEquals("http://example.com/path", normalizeBrowserUrl("HTTP://example.com/path"))
        assertEquals("https://example.com:8443/", normalizeBrowserUrl("https://example.com:8443"))
        listOf("", "javascript:alert(1)", "intent://example.com", "file:///tmp/a", "ftp://example.com",
            "https:///path", "https://", "//example.com", "https://user:secret@example.com",
            "https://@example.com", "https://example.com\\evil", "https://exa mple.com",
            "https://example.com\n", "not-a-site", "https://example.com:99999").forEach {
            assertNull(it, normalizeBrowserUrl(it))
        }
        assertNull(normalizeBrowserUrl("example.com", allowBareHost = false))
        assertNull(normalizeBrowserUrl("https://example.com/" + "a".repeat(4096)))
    }

    @Test
    fun `query and JSON types are validated before use`() {
        assertEquals("météo Paris", normalizedUtilityQuery(" météo Paris ", 500))
        assertNull(normalizedUtilityQuery(" \t ", 500))
        assertNull(normalizedUtilityQuery("a\nb", 500))
        assertNull(normalizedUtilityQuery("a".repeat(501), 500))
        assertNull(normalizedUtilityQuery(null, 500))
        val args = buildJsonObject {
            put("query", 12)
            put("city", true)
            put("url", "https://example.com")
        }
        assertNull(args.utilityString("query"))
        assertNull(args.utilityString("city"))
        assertNull(args.utilityString("missing"))
        assertEquals("https://example.com", args.utilityString("url"))
    }

    @Test
    fun `message preparation preserves text and restricts destination apps`() {
        val text = "  Bonjour !\nDeuxième ligne\tFin  "
        assertEquals(MessageDraft(text, "whatsapp"), prepareMessageDraft(text, " WhatsApp "))
        assertEquals(MessageDraft(text, ""), prepareMessageDraft(text, ""))
        assertEquals("sms", prepareMessageDraft(text, "SMS")?.app)
        assertEquals("telegram", prepareMessageDraft(text, "telegram")?.app)
        assertNull(prepareMessageDraft(" ", "sms"))
        assertNull(prepareMessageDraft("Bonjour", "unknown"))
        assertNull(prepareMessageDraft("Bonjour", null))
        assertNull(prepareMessageDraft("a\u0000b", "sms"))
        assertNull(prepareMessageDraft("a".repeat(10001), "sms"))
        assertEquals(10000, prepareMessageDraft("a".repeat(10000), "sms")?.text?.length)
    }

    @Test
    fun `YouTube search encodes special characters as a single parameter`() {
        val query = "musique française & calme + #été ?"
        val url = youtubeSearchUrl(query)!!.toHttpUrl()
        assertEquals("https", url.scheme)
        assertEquals("www.youtube.com", url.host)
        assertEquals(query, url.queryParameter("search_query"))
        assertEquals(1, url.querySize)
        assertNull(url.fragment)
        assertNull(youtubeSearchUrl(""))
        assertNull(youtubeSearchUrl("a".repeat(501)))
    }

    @Test
    fun `search extracts real titles decoded URLs and matching snippets without duplicates`() {
        val html = """
            <table>
              <tr><td><a href="/about">Navigation</a></td></tr>
              <tr><td><a class="result-link" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Farticle%23top">Titre &amp; suite</a></td></tr>
              <tr><td class="result-snippet">Un <b>véritable</b> extrait.</td></tr>
              <tr><td><a class="result-link" href="https://example.com/article#other">Doublon</a></td></tr>
              <tr><td class="result-snippet">Extrait du doublon.</td></tr>
              <tr><td><a class="result-link" href="javascript:alert(1)">Invalide</a></td></tr>
              <tr><td><a class="result-link" href="/next">Navigation interne</a></td></tr>
              <tr><td><a class="result-link" href="https://example.org/">Deuxième</a></td></tr>
              <tr><td class="result-snippet">Deuxième extrait.</td></tr>
            </table>
        """.trimIndent()
        assertEquals(listOf(
            WebSearchResult("Titre & suite", "https://example.com/article", "Un véritable extrait."),
            WebSearchResult("Deuxième", "https://example.org/", "Deuxième extrait."),
        ), parseWebSearchResults(html))
    }

    @Test
    fun `search supports HTML result blocks and never borrows next result snippet`() {
        val html = """
            <div class="result"><a class="result__a" href="https://example.com/one">Un</a><a class="result__snippet">Extrait un</a></div>
            <table>
              <tr><td><a class="result-link" href="https://example.com/two">Deux</a></td></tr>
              <tr><td><a class="result-link" href="https://example.com/three">Trois</a></td></tr>
              <tr><td class="result-snippet">Extrait trois</td></tr>
            </table>
        """.trimIndent()
        val results = parseWebSearchResults(html)
        assertEquals(listOf("Extrait un", "", "Extrait trois"), results.map { it.snippet })
        assertTrue(parseWebSearchResults("<form>Vérification requise</form>").isEmpty())
    }

    @Test
    fun `search caps distinct results and rejects malformed redirects`() {
        val html = (1..8).joinToString("") { "<a class='result-link' href='https://example.com/$it'>Titre $it</a>" }
        assertEquals(5, parseWebSearchResults(html).size)
        assertNull(unwrapSearchUrl("https://duckduckgo.com/l/?uddg=javascript%3Aalert(1)"))
        assertNull(unwrapSearchUrl("https://duckduckgo.com/l/?uddg=%ZZ"))
        assertNull(unwrapSearchUrl("https://duckduckgo.com/about"))
        assertEquals("https://example.com/a?x=1&y=2", unwrapSearchUrl(
            "https://duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fa%3Fx%3D1%26y%3D2"))
    }

    @Test
    fun `weather distinguishes unknown city and invalid service data`() {
        assertNull(parseWeatherPlace("{}"))
        assertNull(parseWeatherPlace("""{"results":[]}"""))
        val place = parseWeatherPlace("""{"results":[{"name":"Paris","admin1":"Île-de-France","country":"France","latitude":48.85,"longitude":2.35}]}""")!!
        assertEquals("Paris, Île-de-France, France", place.label)
        assertEquals(48.85, place.latitude, 0.001)
        listOf("not json", """{"error":true,"reason":"private response"}""",
            """{"results":[{"name":"Paris","latitude":91,"longitude":2}]}""",
            """{"results":[{"latitude":48,"longitude":2}]}""",
            """{"results":[{"name":"Paris","latitude":48,"longitude":181}]}""").forEach { body ->
            assertThrows(Exception::class.java) { parseWeatherPlace(body) }
        }
    }

    @Test
    fun `weather reports French conditions and validated measurements`() {
        val report = formatCurrentWeather("""{"current":{"temperature_2m":12.5,"relative_humidity_2m":72,"wind_speed_10m":8.2,"weather_code":85}}""", "Paris")
        assertEquals("Météo à Paris : averses de neige, 12,5 °C, humidité 72 %, vent 8,2 km/h.", report)
        assertEquals("orage avec grêle", describeWeatherCode(99))
        assertEquals("conditions non précisées", describeWeatherCode(999))
        listOf("{}", """{"current":null}""",
            """{"current":{"temperature_2m":null,"relative_humidity_2m":72,"wind_speed_10m":8,"weather_code":0}}""",
            """{"current":{"temperature_2m":12,"relative_humidity_2m":101,"wind_speed_10m":8,"weather_code":0}}""",
            """{"current":{"temperature_2m":12,"relative_humidity_2m":72,"wind_speed_10m":-1,"weather_code":0}}""").forEach { body ->
            assertThrows(Exception::class.java) { formatCurrentWeather(body, "Paris") }
        }
    }

    @Test
    fun `HTTP failures are explicit and do not contain server bodies`() {
        assertTrue(utilityHttpError("Météo", 429).contains("trop de requêtes"))
        assertTrue(utilityHttpError("Recherche", 403).contains("accès refusé"))
        assertTrue(utilityHttpError("Météo", 503).contains("temporairement"))
        assertFalse(utilityHttpError("Météo", 400).contains("Exception"))
    }
}
