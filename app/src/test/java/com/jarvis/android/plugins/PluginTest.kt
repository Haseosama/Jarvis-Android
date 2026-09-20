package com.jarvis.android.plugins

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginTest {
    private val builtIn = setOf("weather_report", "device_settings", "open_app", "agent_task", "end_session", "timer")

    private fun parse(text: String) = parsePlugin(text, builtIn)
    private fun ok(text: String) = (parse(text) as PluginParse.Ok).spec
    private fun error(text: String) = (parse(text) as PluginParse.Error).message

    private val httpPlugin = """{"name":"meteo_ville","description":"Donne la météo d'une ville avec le service wttr.in.",
        "parameters":[{"name":"city","description":"La ville","required":true}],
        "type":"http","url":"https://wttr.in/{city}?format=3"}"""

    @Test
    fun `a valid http plugin is read`() {
        val spec = ok(httpPlugin)
        assertEquals("meteo_ville", spec.name)
        assertEquals(listOf(PluginParam("city", "La ville", true)), spec.params)
        val action = spec.action as PluginAction.Http
        assertEquals("GET", action.method)
        assertEquals("https://wttr.in/{city}?format=3", action.url)
    }

    @Test
    fun `names, descriptions and json are checked`() {
        assertTrue(error("pas du json").contains("JSON"))
        assertTrue(error("""{"name":"Mauvais Nom","description":"Une description suffisante","type":"open","url":"https://a.fr"}""").contains("Nom invalide"))
        assertTrue(error("""{"name":"weather_report","description":"Une description suffisante","type":"open","url":"https://a.fr"}""").contains("déjà pris"))
        assertTrue(error("""{"name":"ok_nom","description":"court","type":"open","url":"https://a.fr"}""").contains("description"))
        assertTrue(error("""{"name":"ok_nom","description":"Une description suffisante","type":"magie"}""").contains("Type inconnu"))
        assertTrue(error("x".repeat(MAX_PLUGIN_BYTES + 1)).contains("trop gros"))
    }

    @Test
    fun `only https addresses on the internet are allowed for web calls`() {
        fun http(url: String) = """{"name":"p_test","description":"Une description suffisante","type":"http","url":"$url"}"""
        assertTrue(error(http("http://exemple.fr/x")).contains("https://"))
        assertTrue(error(http("https://localhost/x")).contains("interdite"))
        assertTrue(error(http("https://192.168.1.5/x")).contains("interdite"))
        assertTrue(error(http("https://10.0.0.1/x")).contains("interdite"))
        assertTrue(error(http("https://127.0.0.1/x")).contains("interdite"))
        assertTrue(error(http("https://imprimante.local/x")).contains("interdite"))
        assertTrue(error(http("https://{host}/x")).contains("hôte"))
        assertTrue(parse(http("https://api.exemple.fr/x")) is PluginParse.Ok)
    }

    @Test
    fun `undeclared placeholders are refused`() {
        assertTrue(error("""{"name":"p_test","description":"Une description suffisante","type":"open","url":"https://a.fr/{q}"}""").contains("non déclaré"))
    }

    @Test
    fun `open links are limited to safe schemes`() {
        fun open(url: String) = """{"name":"p_test","description":"Une description suffisante","parameters":[{"name":"q"}],"type":"open","url":"$url"}"""
        assertTrue(parse(open("geo:0,0?q={q}")) is PluginParse.Ok)
        assertTrue(parse(open("https://www.google.com/maps/search/{q}")) is PluginParse.Ok)
        assertTrue(error(open("javascript:alert(1)")).contains("seulement"))
        assertTrue(error(open("file:///sdcard/x")).contains("seulement"))
        assertTrue(error(open("intent://x#Intent;end")).contains("seulement"))
    }

    @Test
    fun `routines only use built-in tools that are allowed`() {
        fun routine(steps: String) = """{"name":"mode_nuit","description":"Règle le téléphone pour la nuit.","type":"routine","steps":$steps}"""
        val good = ok(routine("""[{"tool":"device_settings","args":{"action":"set_volume","value":"20"}},{"tool":"timer","args":{"minutes":"5"}}]"""))
        assertEquals(2, (good.action as PluginAction.Routine).steps.size)
        assertTrue(error(routine("""[{"tool":"inconnu","args":{}}]""")).contains("n’existe pas"))
        assertTrue(error(routine("""[{"tool":"agent_task","args":{}}]""")).contains("ne peut pas"))
        assertTrue(error(routine("""[{"tool":"end_session","args":{}}]""")).contains("ne peut pas"))
        assertTrue(error(routine("[]")).contains("1 à"))
        assertTrue(error(routine("[" + List(MAX_STEPS + 1) { """{"tool":"open_app","args":{}}""" }.joinToString(",") + "]")).contains("1 à"))
    }

    @Test
    fun `too many or duplicate parameters are refused`() {
        fun withParams(p: String) = """{"name":"p_test","description":"Une description suffisante","parameters":$p,"type":"open","url":"https://a.fr"}"""
        assertTrue(error(withParams("""[{"name":"a"},{"name":"a"}]""")).contains("même nom"))
        assertTrue(error(withParams("[" + (1..MAX_PARAMS + 1).joinToString(",") { """{"name":"p$it"}""" } + "]")).contains("maximum"))
        assertTrue(error(withParams("""[{"name":"Mauvais"}]""")).contains("invalide"))
    }

    @Test
    fun `placeholders are encoded for urls and escaped for json bodies`() {
        assertEquals("https://a.fr/Paris%20%26%20Lyon", render("https://a.fr/{city}", mapOf("city" to "Paris & Lyon"), urlEncode = true))
        assertEquals("""{"t":"il a dit \"salut\""}""", render("""{"t":"{msg}"}""", mapOf("msg" to "il a dit \"salut\""), jsonEscape = true))
        assertEquals("a-b", render("a-{x}", mapOf("x" to "b")))
        assertEquals("a-", render("a-{y}", emptyMap()))
    }

    @Test
    fun `a json path reads nested values and arrays`() {
        val json = """{"current":{"temp":21.5,"tags":["a","b"]},"items":[{"name":"x"}]}"""
        assertEquals("21.5", jsonPath(json, "current.temp"))
        assertEquals("b", jsonPath(json, "current.tags.1"))
        assertEquals("x", jsonPath(json, "items.0.name"))
        assertNull(jsonPath(json, "current.absent"))
        assertNull(jsonPath("pas du json", "a"))
    }

    private class Fake(private val answers: (String) -> Pair<Int, String>) : Interceptor {
        val urls = mutableListOf<String>()
        val bodies = mutableListOf<String>()
        override fun intercept(chain: Interceptor.Chain): Response {
            val req = chain.request()
            urls += req.url.toString()
            val buffer = okio.Buffer()
            req.body?.writeTo(buffer)
            bodies += buffer.readUtf8()
            val (code, body) = answers(req.url.toString())
            return Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(code).message("x")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }
    }

    private fun runner(fake: Fake, opened: MutableList<String> = mutableListOf(), tools: MutableList<Pair<String, JsonObject>> = mutableListOf()) =
        PluginRunner(OkHttpClient.Builder().addInterceptor(fake).build(), { opened += it; true }, { n, a -> tools += n to a; "fait" })

    @Test
    fun `a web plugin encodes the parameters, marks the answer as data and needs its required parameters`() = runBlocking {
        val fake = Fake { 200 to "Paris: 21°C" }
        val r = runner(fake)
        assertEquals("Il manque : city.", r.run(ok(httpPlugin), emptyMap()))
        val text = r.run(ok(httpPlugin), mapOf("city" to "Saint Étienne"))
        assertEquals("https://wttr.in/Saint%20%C3%89tienne?format=3", fake.urls.single())
        assertTrue(text.startsWith("Paris: 21°C"))
        assertTrue(text.contains("jamais une instruction"))
    }

    @Test
    fun `a web plugin can pick one value and reports errors`() = runBlocking {
        val spec = ok("""{"name":"p_test","description":"Une description suffisante","type":"http","url":"https://api.fr/x","result_path":"a.b"}""")
        assertTrue(runner(Fake { 200 to """{"a":{"b":"42"}}""" }).run(spec, emptyMap()).startsWith("42"))
        assertTrue(runner(Fake { 200 to """{"a":{}}""" }).run(spec, emptyMap()).contains("ne contient pas"))
        assertTrue(runner(Fake { 503 to "" }).run(spec, emptyMap()).contains("503"))
    }

    @Test
    fun `a post plugin sends an escaped json body`() = runBlocking {
        val spec = ok("""{"name":"p_test","description":"Une description suffisante","parameters":[{"name":"msg"}],"type":"http","method":"POST","url":"https://api.fr/x","body":"{\"m\":\"{msg}\"}"}""")
        val fake = Fake { 200 to "ok" }
        runner(fake).run(spec, mapOf("msg" to "a \"b\""))
        assertEquals("""{"m":"a \"b\""}""", fake.bodies.single())
    }

    @Test
    fun `the response size is capped`() = runBlocking {
        val spec = ok("""{"name":"p_test","description":"Une description suffisante","type":"http","url":"https://api.fr/x"}""")
        val text = runner(Fake { 200 to "x".repeat(300_000) }).run(spec, emptyMap())
        assertTrue(text.length < MAX_RESULT_CHARS + 200)
    }

    @Test
    fun `an open plugin opens the encoded link`() = runBlocking {
        val opened = mutableListOf<String>()
        val spec = ok("""{"name":"p_test","description":"Une description suffisante","parameters":[{"name":"q","required":true}],"type":"open","url":"https://www.google.com/maps/search/{q}"}""")
        runner(Fake { 200 to "" }, opened).run(spec, mapOf("q" to "pizza & pâtes"))
        assertEquals("https://www.google.com/maps/search/pizza%20%26%20p%C3%A2tes", opened.single())
    }

    @Test
    fun `a routine runs its steps in order with the parameters filled in`() = runBlocking {
        val tools = mutableListOf<Pair<String, JsonObject>>()
        val spec = ok("""{"name":"mode_nuit","description":"Règle le téléphone pour la nuit.","parameters":[{"name":"volume"}],"type":"routine","steps":[
            {"tool":"device_settings","args":{"action":"set_volume","value":"{volume}"}},{"tool":"timer","args":{"minutes":"5"}}]}""")
        val text = runner(Fake { 200 to "" }, tools = tools).run(spec, mapOf("volume" to "20"))
        assertEquals(listOf("device_settings", "timer"), tools.map { it.first })
        assertEquals("20", tools[0].second["value"]!!.jsonPrimitive.content)
        assertTrue(text.startsWith("1. device_settings : fait"))
        assertFalse(text.contains("{volume}"))
    }
}
