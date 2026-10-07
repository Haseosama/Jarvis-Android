package com.jarvis.android.connectors

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpProtocolTest {
    private fun obj(text: String) = Json.parseToJsonElement(text).jsonObject

    @Test
    fun `connector names become plain ids`() {
        assertEquals("mon_notion", connectorSlug("Mon Notion !"))
        assertEquals("maison", connectorSlug("Maïson"))
        assertEquals("c_42_outils", connectorSlug("42 outils"))
        assertEquals("connecteur", connectorSlug("!!!"))
        assertTrue(connectorSlug("un nom vraiment beaucoup trop long pour un outil").length <= 20)
    }

    @Test
    fun `tool names are clean, short and unique`() {
        assertEquals("notion_search_pages", connectorToolName("notion", "search-pages", emptySet()))
        assertEquals("notion_search_pages_2", connectorToolName("notion", "search.pages", setOf("notion_search_pages")))
        val long = connectorToolName("notion", "x".repeat(100), emptySet())
        assertEquals(64, long.length)
        val second = connectorToolName("notion", "x".repeat(100), setOf(long))
        assertEquals(64, second.length)
        assertTrue(second.endsWith("_2"))
    }

    @Test
    fun `json schema becomes a gemini declaration`() {
        val schema = obj(
            """{"type":"object","${'$'}schema":"x","additionalProperties":false,
               "properties":{
                 "query":{"type":"string","description":"What to look for"},
                 "limit":{"type":["integer","null"]},
                 "sort":{"type":"string","enum":["asc","desc"]},
                 "tags":{"type":"array","items":{"type":"string"}},
                 "filter":{"anyOf":[{"type":"null"},{"type":"object","properties":{"done":{"type":"boolean"}}}]},
                 "extra":{"type":"object","additionalProperties":true}
               },
               "required":["query","missing"]}""",
        )
        val g = geminiParameters(schema)
        assertEquals("OBJECT", g["type"]!!.jsonPrimitive.content)
        assertNull(g["\$schema"])
        assertNull(g["additionalProperties"])
        val props = g["properties"]!!.jsonObject
        assertEquals("STRING", props["query"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("What to look for", props["query"]!!.jsonObject["description"]!!.jsonPrimitive.content)
        assertEquals("INTEGER", props["limit"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("true", props["limit"]!!.jsonObject["nullable"]!!.jsonPrimitive.content)
        assertEquals(listOf("asc", "desc"), props["sort"]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("STRING", props["tags"]!!.jsonObject["items"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        val filter = props["filter"]!!.jsonObject
        assertEquals("OBJECT", filter["type"]!!.jsonPrimitive.content)
        assertEquals("BOOLEAN", filter["properties"]!!.jsonObject["done"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("STRING", props["extra"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(listOf("query"), g["required"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `references are followed and an empty schema still declares properties`() {
        val schema = obj(
            """{"type":"object","properties":{"page":{"${'$'}ref":"#/${'$'}defs/Page"}},
               "${'$'}defs":{"Page":{"type":"object","properties":{"title":{"type":"string"}}}}}""",
        )
        val page = geminiParameters(schema)["properties"]!!.jsonObject["page"]!!.jsonObject
        assertEquals("OBJECT", page["type"]!!.jsonPrimitive.content)
        assertTrue("title" in page["properties"]!!.jsonObject)
        val empty = geminiParameters(JsonObject(emptyMap()))
        assertEquals("OBJECT", empty["type"]!!.jsonPrimitive.content)
        assertTrue(empty["properties"] is JsonObject)
    }

    @Test
    fun `json written as text is turned back into objects`() {
        val schema = obj("""{"type":"object","properties":{"extra":{"type":"object"},"ids":{"type":"array","items":{"type":"integer"}},"name":{"type":"string"}}}""")
        val args = obj("""{"extra":"{\"a\":1}","ids":[1,2],"name":"{not json}"}""")
        val revived = reviveArguments(args, schema)
        assertEquals(1, revived["extra"]!!.jsonObject["a"]!!.jsonPrimitive.content.toInt())
        assertTrue(revived["ids"] is JsonArray)
        assertEquals("{not json}", revived["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `results are read as text`() {
        val result = obj("""{"content":[{"type":"text","text":"Bonjour"},{"type":"image","mimeType":"image/png","data":"…"},{"type":"resource_link","uri":"https://x/1","name":"Doc"}]}""")
        assertEquals("Bonjour\n[image image/png]\nDoc : https://x/1", toolResultText(result))
        assertEquals("""{"n":3}""", toolResultText(obj("""{"content":[],"structuredContent":{"n":3}}""")))
        assertTrue(toolResultText(obj("""{"content":[{"type":"text","text":"Raté"}],"isError":true}""")).startsWith("Le connecteur signale une erreur"))
        val long = toolResultText(JsonObject(mapOf("content" to JsonArray(listOf(JsonObject(mapOf("type" to JsonPrimitive("text"), "text" to JsonPrimitive("a".repeat(20_000)))))))))
        assertTrue(long.length < 12_100)
        assertTrue(long.endsWith("(réponse tronquée)"))
    }

    @Test
    fun `server sent events are split and joined`() {
        val events = parseSse(": ping\nevent: endpoint\ndata: /messages?id=1\n\ndata: {\"a\":\ndata: 1}\n\n")
        assertEquals(listOf(SseEvent("endpoint", "/messages?id=1"), SseEvent("message", "{\"a\":\n1}")), events)
    }

    @Test
    fun `json rpc answers are matched by id`() {
        assertEquals("1", McpClient.parseMessage("""{"jsonrpc":"2.0","id":1,"result":{}}""", 1)!!["id"]!!.jsonPrimitive.content)
        assertNull(McpClient.parseMessage("""{"jsonrpc":"2.0","method":"notifications/progress"}""", 1))
        assertNull(McpClient.parseMessage("not json", 1))
        assertEquals("2", McpClient.parseMessage("""[{"id":1,"result":{}},{"id":2,"result":{}}]""", 2)!!["id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `oauth discovery addresses`() {
        assertEquals(
            "https://mcp.example.com/.well-known/oauth-protected-resource",
            resourceMetadataFromHeader("""Bearer error="invalid_token", resource_metadata="https://mcp.example.com/.well-known/oauth-protected-resource""""),
        )
        assertNull(resourceMetadataFromHeader("Bearer"))
        assertEquals(
            listOf("https://a.com/.well-known/oauth-protected-resource/v1/mcp", "https://a.com/.well-known/oauth-protected-resource"),
            protectedResourceMetadataUrls("https://a.com/v1/mcp"),
        )
        assertEquals(
            listOf("https://auth.a.com/.well-known/oauth-authorization-server", "https://auth.a.com/.well-known/openid-configuration"),
            authorizationServerMetadataUrls("https://auth.a.com/"),
        )
        assertEquals("https://auth.a.com/.well-known/oauth-authorization-server/tenant", authorizationServerMetadataUrls("https://auth.a.com/tenant")[0])
        assertEquals("https://a.com/mcp", canonicalResource("https://a.com/mcp/#x"))
    }

    @Test
    fun `pkce challenge is the url-safe sha-256 of the verifier`() {
        // computed apart with Python's hashlib and base64.urlsafe_b64encode
        assertEquals("Ip0QNGeZ3RhV918-sCTBugnt6GEVkKdyo-LOi5IOdS0", pkceChallenge("dBjftJeZ4CVP-mJ92K9KJ6E2VoLxOKFxYNG3S9GjlJc"))
        assertFalse(randomToken().contains('='))
    }

    @Test
    fun `only https addresses are taken`() {
        assertTrue(connectorUrlAllowed("https://mcp.notion.com/mcp"))
        assertFalse(connectorUrlAllowed("http://192.168.1.10:8123/mcp_server/sse"))
        assertFalse(connectorUrlAllowed("ftp://x"))
        assertFalse(connectorUrlAllowed("pas une adresse"))
    }
}
