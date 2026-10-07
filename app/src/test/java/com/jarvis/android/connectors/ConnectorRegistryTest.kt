package com.jarvis.android.connectors

import com.jarvis.android.registry.ToolRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectorRegistryTest {
    @After fun cleanUp() = InstalledConnectors.set(emptyList())

    // a schema as real servers write them: $schema, anyOf with null, a free-form object, a nested list of objects
    private val schema = Json.parseToJsonElement(
        """{"${'$'}schema":"http://json-schema.org/draft-07/schema#","type":"object","additionalProperties":false,
           "properties":{"q":{"type":"string"},"opts":{"type":"object"},"when":{"anyOf":[{"type":"string","format":"date-time"},{"type":"null"}]},
           "items":{"type":"array","items":{"type":"object","properties":{"id":{"type":"integer"},"tags":{"type":"array","items":{"type":"string"}}}}}},
           "required":["q"]}""",
    ).jsonObject

    private fun tools(count: Int): List<McpTool> {
        val taken = HashSet(ToolRegistry.builtInNames())
        return (1..count).map { n ->
            val name = connectorToolName("github", "tool-$n", taken).also { taken += it }
            McpTool("github", "GitHub", RemoteTool("tool-$n", "Outil numéro $n du serveur", schema), name)
        }
    }

    private val nameRule = Regex("^[A-Za-z_][A-Za-z0-9_.:-]{0,63}$")
    private val types = setOf("STRING", "INTEGER", "NUMBER", "BOOLEAN", "ARRAY", "OBJECT")

    private fun checkProperty(where: String, p: JsonObject) {
        val type = (p["type"] as? JsonPrimitive)?.content
        assertTrue("type inconnu pour $where : $type", type in types)
        if (type == "ARRAY") assertTrue("$where : un ARRAY sans items", p["items"] is JsonObject)
        if (type == "ARRAY") checkProperty("$where[]", p["items"]!!.jsonObject)
        if (type == "OBJECT") (p["properties"] as? JsonObject)?.forEach { (k, v) -> checkProperty("$where.$k", v.jsonObject) }
    }

    @Test
    fun `connector tools are declared up to the limit, the rest through connecteurs`() {
        InstalledConnectors.set(tools(40))
        val declarations = ToolRegistry.declarations()
        val names = declarations.map { it["name"]!!.jsonPrimitive.content }
        assertEquals(ToolRegistry.ALL.size + MAX_DECLARED_CONNECTOR_TOOLS, names.size)
        assertEquals(names.size, names.toSet().size)
        assertTrue("github_tool_1" in names)
        assertFalse("github_tool_40" in names)
        declarations.filter { it["name"]!!.jsonPrimitive.content.startsWith("github_") }.forEach { d ->
            val name = d["name"]!!.jsonPrimitive.content
            assertTrue(nameRule.matches(name))
            val params = d["parameters"]!!.jsonObject
            assertEquals("OBJECT", params["type"]!!.jsonPrimitive.content)
            params["properties"]!!.jsonObject.forEach { (k, v) -> checkProperty("$name.$k", v.jsonObject) }
            (params["required"] as JsonArray).forEach { assertTrue(it.jsonPrimitive.content in params["properties"]!!.jsonObject) }
        }
        // every tool is found by name, and the undeclared ones are listed by the connecteurs tool
        assertNotNull(ToolRegistry.get("github_tool_40"))
        val meta = ToolRegistry.get("connecteurs")!!
        assertTrue(meta.description.contains("github_tool_40 : Outil numéro 40"))
        assertFalse(meta.description.contains("github_tool_1 :"))
        assertNull(ToolRegistry.get("github_inconnu"))
    }

    @Test
    fun `without connectors the connecteurs tool only explains itself`() {
        assertFalse(ToolRegistry.get("connecteurs")!!.description.contains("Outils de connecteur"))
        assertTrue(ConnectorTool.describe(emptyList()).startsWith("Aucun connecteur"))
        val listed = ConnectorTool.describe(
            listOf(
                Connector("notion", "Notion", "https://mcp.notion.com/mcp", needsLogin = true),
                Connector("gh", "GitHub", "https://api.githubcopilot.com/mcp/", tools = listOf(RemoteTool("a"), RemoteTool("b"))),
            ),
        )
        assertTrue(listed.contains("Notion (https://mcp.notion.com/mcp) : connexion nécessaire"))
        assertTrue(listed.contains("GitHub (https://api.githubcopilot.com/mcp/) : 2 outils"))
    }

    @Test
    fun `arguments and search for the connecteurs tool`() {
        assertEquals(JsonObject(emptyMap()), parseConnectorArguments(" "))
        assertEquals("x", parseConnectorArguments("""{"q":"x","n":{"a":1}}""")!!["q"]!!.jsonPrimitive.content)
        assertNull(parseConnectorArguments("[1]"))
        assertNull(parseConnectorArguments("pas du json"))
        val all = tools(3)
        assertEquals(listOf("github_tool_2"), searchConnectorTools(all, "Numéro 2").map { it.name })
        assertEquals(3, searchConnectorTools(all, "").size)
    }
}
