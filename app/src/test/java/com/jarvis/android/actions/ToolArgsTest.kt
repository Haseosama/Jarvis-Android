package com.jarvis.android.actions

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ToolArgsTest {
    private val args = buildJsonObject {
        put("text", "bonjour")
        put("count", 3)
        put("countAsText", "7")
        put("bad", "sept")
        put("nothing", JsonNull)
        put("list", buildJsonArray { })
    }

    @Test
    fun `string arguments, with the default when missing, null or not a primitive`() {
        assertEquals("bonjour", args.stringArg("text"))
        assertEquals("3", args.stringArg("count"))
        assertEquals("", args.stringArg("absent"))
        assertEquals("défaut", args.stringArg("absent", "défaut"))
        assertEquals("défaut", args.stringArg("nothing", "défaut"))
        assertEquals("défaut", args.stringArg("list", "défaut"))
    }

    @Test
    fun `integer arguments accept numbers and numeric text, and fall back otherwise`() {
        assertEquals(3, args.intArg("count"))
        assertEquals(7, args.intArg("countAsText"))
        assertEquals(0, args.intArg("bad"))
        assertEquals(-1, args.intArg("bad", -1))
        assertEquals(5, args.intArg("absent", 5))
        assertEquals(5, args.intArg("nothing", 5))
    }

    @Test
    fun `objectSchema builds typed, described properties and lists the required ones`() {
        val schema = objectSchema(required = listOf("city")) {
            string("city", "La ville")
            integer("days", "Nombre de jours")
        }
        assertEquals("OBJECT", schema["type"]!!.jsonPrimitive.content)
        val props = schema["properties"]!!.jsonObject
        assertEquals("STRING", props["city"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("La ville", props["city"]!!.jsonObject["description"]!!.jsonPrimitive.content)
        assertEquals("INTEGER", props["days"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(listOf("city"), schema["required"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `objectSchema without required ones leaves the key out, and the default parameters are an empty OBJECT`() {
        val schema = objectSchema { string("q", "Recherche") }
        assertFalse("required" in schema)
        val bare = object : Tool {
            override val name = "bare"
            override val description = "test"
            override suspend fun run(args: JsonObject, ctx: com.jarvis.android.JarvisContainer) = ""
        }
        assertEquals("OBJECT", bare.parameters["type"]!!.jsonPrimitive.content)
        assertTrue(bare.parameters["properties"]!!.jsonObject.isEmpty())
    }

    @Test
    fun `document types are recognised whatever the case or a leading dot`() {
        assertEquals("pdf", documentType("PDF"))
        assertEquals("docx", documentType(" .docx "))
        assertEquals("md", documentType("md"))
        assertNull(documentType("exe"))
        assertNull(documentType(""))
    }

    @Test
    fun `call log types map to their kind`() {
        // android.provider.CallLog.Calls: INCOMING 1, OUTGOING 2, MISSED 3, REJECTED 5
        assertEquals(CallKind.INCOMING, callKindOf(1))
        assertEquals(CallKind.OUTGOING, callKindOf(2))
        assertEquals(CallKind.MISSED, callKindOf(3))
        assertEquals(CallKind.REJECTED, callKindOf(5))
        assertEquals(CallKind.OTHER, callKindOf(4))
        assertEquals(CallKind.OTHER, callKindOf(99))
    }

    @Test
    fun `fuel price ages read in minutes, hours, then days`() {
        val now = Instant.parse("2026-10-04T12:00:00Z")
        assertEquals("date inconnue", ageLabel(null, now))
        assertEquals("il y a 0 min", ageLabel(now, now))
        assertEquals("il y a 0 min", ageLabel(now.plusSeconds(600), now)) // a clock ahead never gives a negative age
        assertEquals("il y a 59 min", ageLabel(now.minusSeconds(59 * 60), now))
        assertEquals("il y a 1 h", ageLabel(now.minusSeconds(60 * 60), now))
        assertEquals("il y a 47 h", ageLabel(now.minusSeconds(47 * 3600), now))
        assertEquals("il y a 2 jours", ageLabel(now.minusSeconds(48 * 3600), now))
    }

    @Test
    fun `the air quality request asks for the place, the index, particles and pollens`() {
        val url = airQualityUrl(48.85, 2.35)
        assertTrue(url.startsWith("https://air-quality-api.open-meteo.com/v1/air-quality?"))
        assertTrue(url.contains("latitude=48.85"))
        assertTrue(url.contains("longitude=2.35"))
        assertTrue(url.contains("european_aqi"))
        assertTrue(url.contains("pm2_5"))
        com.jarvis.android.air.POLLENS.forEach { assertTrue("pollen ${it.key}", url.contains(it.key)) }
    }
}
