package com.jarvis.android.actions

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One malformed declaration makes the Live API refuse the setup of the whole session (see [Tool.parameters]),
 * so every built-in tool is checked here against the shape Gemini accepts.
 */
class ToolRegistryContractTest {
    @After fun cleanUp() = ToolRegistry.setPlugins(emptyList())

    // Gemini: starts with a letter or an underscore, then letters, digits, _ . : -, at most 64 characters.
    private val nameRule = Regex("^[A-Za-z_][A-Za-z0-9_.:-]{0,63}$")
    private val types = setOf("STRING", "INTEGER", "NUMBER", "BOOLEAN", "ARRAY", "OBJECT")

    @Test
    fun `every built-in tool has a unique name Gemini accepts and says what it does`() {
        val names = ToolRegistry.ALL.map { it.name }
        assertEquals("noms en double : ${names.groupBy { it }.filterValues { it.size > 1 }.keys}", names.size, names.toSet().size)
        names.forEach { assertTrue("nom refusé par Gemini : $it", nameRule.matches(it)) }
        ToolRegistry.ALL.forEach { tool ->
            assertTrue("description vide : ${tool.name}", tool.description.isNotBlank())
        }
    }

    @Test
    fun `every declaration is an OBJECT with properties, typed and described, and required ones that exist`() {
        ToolRegistry.declarations().forEach { d ->
            val name = d["name"]!!.jsonPrimitive.content
            val params = d["parameters"]!!.jsonObject
            assertEquals("type de $name", "OBJECT", params["type"]!!.jsonPrimitive.content)
            val props = params["properties"] as? JsonObject
            assertTrue("$name n’a pas d’objet properties", props != null)
            props!!.forEach { (key, value) -> checkProperty("$name.$key", value.jsonObject) }
            (params["required"] as? JsonArray)?.forEach { req ->
                val key = req.jsonPrimitive.content
                assertTrue("$name exige « $key » qu’il ne déclare pas", key in props)
            }
        }
    }

    private fun checkProperty(where: String, p: JsonObject) {
        val type = (p["type"] as? JsonPrimitive)?.content
        assertTrue("type inconnu pour $where : $type", type in types)
        if (type == "ARRAY") assertTrue("$where : un ARRAY sans items", p["items"] is JsonObject)
        if (type == "OBJECT") (p["properties"] as? JsonObject)?.forEach { (k, v) -> checkProperty("$where.$k", v.jsonObject) }
    }

    @Test
    fun `get finds each built-in tool by its name and nothing for an unknown one`() {
        ToolRegistry.ALL.forEach { assertSame(it, ToolRegistry.get(it.name)) }
        assertNull(ToolRegistry.get("no_such_tool"))
        assertNull(ToolRegistry.get(""))
    }

    @Test
    fun `without plugins the declarations are exactly the built-in tools, in order`() {
        ToolRegistry.setPlugins(emptyList())
        assertEquals(ToolRegistry.ALL.map { it.name }, ToolRegistry.declarations().map { it["name"]!!.jsonPrimitive.content })
        assertTrue(ToolRegistry.ALL.all { it.name in ToolRegistry.builtInNames() })
    }

    @Test
    fun `a plugin cannot shadow a built-in tool`() {
        val fake = object : Tool {
            override val name = ToolRegistry.ALL.first().name
            override val description = "imposteur"
            override suspend fun run(args: JsonObject, ctx: com.jarvis.android.JarvisContainer) = "non"
        }
        ToolRegistry.setPlugins(listOf(fake))
        assertTrue(ToolRegistry.pluginTools().isEmpty())
        assertSame(ToolRegistry.ALL.first(), ToolRegistry.get(fake.name))
    }
}
