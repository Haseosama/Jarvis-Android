package com.jarvis.android.connectors

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.Collections

/** A fake MCP server: answers JSON-RPC by method, as JSON or as an event stream, and records what it received. */
internal class FakeMcpServer(
    var sse: Boolean = false,
    var requireToken: String? = null,
    var tools: String = """[{"name":"search","description":"Search pages","inputSchema":{"type":"object","properties":{"query":{"type":"string"}},"required":["query"]}}]""",
) : Dispatcher() {
    val received: MutableList<RecordedRequest> = Collections.synchronizedList(ArrayList())
    val bodies: MutableList<JsonObject> = Collections.synchronizedList(ArrayList())
    var resourceMetadata: String? = null

    override fun dispatch(request: RecordedRequest): MockResponse {
        received += request
        val token = requireToken
        if (token != null && request.getHeader("Authorization") != "Bearer $token") {
            val header = "Bearer" + (resourceMetadata?.let { " resource_metadata=\"$it\"" } ?: "")
            return MockResponse().setResponseCode(401).setHeader("WWW-Authenticate", header)
        }
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        bodies += body
        val method = body["method"]!!.jsonPrimitive.content
        val id = (body["id"] as? JsonPrimitive)?.contentOrNull ?: return MockResponse().setResponseCode(202)
        val result = when (method) {
            "initialize" -> """{"protocolVersion":"2025-06-18","capabilities":{"tools":{}},"serverInfo":{"name":"fake","version":"1"}}"""
            "tools/list" -> """{"tools":$tools}"""
            "tools/call" -> {
                if (body["params"]!!.jsonObject["name"]!!.jsonPrimitive.content == "boom") {
                    return MockResponse().setHeader("Content-Type", "application/json").setBody("""{"jsonrpc":"2.0","id":$id,"error":{"code":-32602,"message":"Unknown tool"}}""")
                }
                val args = body["params"]!!.jsonObject["arguments"]!!.jsonObject
                """{"content":[{"type":"text","text":"Trouvé : ${args["query"]?.jsonPrimitive?.content}"}]}"""
            }
            else -> return MockResponse().setBody("""{"jsonrpc":"2.0","id":$id,"error":{"code":-32601,"message":"Method not found"}}""").setHeader("Content-Type", "application/json")
        }
        val answer = """{"jsonrpc":"2.0","id":$id,"result":$result}"""
        val response = if (sse) MockResponse().setHeader("Content-Type", "text/event-stream").setBody(": ping\n\nevent: message\ndata: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\"}\n\nevent: message\ndata: $answer\n\n")
        else MockResponse().setHeader("Content-Type", "application/json").setBody(answer)
        if (method == "initialize") response.setHeader("Mcp-Session-Id", "session-1")
        return response
    }
}

class McpClientTest {
    private lateinit var server: MockWebServer
    private val fake = FakeMcpServer()
    private val http = OkHttpClient()

    @Before
    fun start() {
        server = MockWebServer()
        server.dispatcher = fake
        server.start()
    }

    @After
    fun stop() {
        server.shutdown()
    }

    private fun client(token: String? = null) = McpClient(http, server.url("/mcp").toString(), { token })

    @Test
    fun `lists tools and calls one over json`() = runBlocking {
        val c = client()
        val tools = c.listTools()
        assertEquals(listOf("search"), tools.map { it.name })
        assertEquals("Search pages", tools[0].description)
        val result = c.callTool("search", JsonObject(mapOf("query" to JsonPrimitive("budget"))))
        assertEquals("Trouvé : budget", toolResultText(result))
        val methods = fake.bodies.map { it["method"]!!.jsonPrimitive.content }
        assertEquals(listOf("initialize", "notifications/initialized", "tools/list", "tools/call"), methods)
        // the session and the protocol version are sent after the handshake
        assertNull(fake.received[0].getHeader("Mcp-Session-Id"))
        assertEquals("session-1", fake.received[2].getHeader("Mcp-Session-Id"))
        assertEquals("2025-06-18", fake.received[2].getHeader("MCP-Protocol-Version"))
        assertTrue(fake.received[0].getHeader("Accept")!!.contains("text/event-stream"))
    }

    @Test
    fun `reads answers sent as an event stream`() = runBlocking {
        fake.sse = true
        val tools = client().listTools()
        assertEquals(listOf("search"), tools.map { it.name })
    }

    @Test
    fun `sends the token and reports a refusal with where to log in`() = runBlocking {
        fake.requireToken = "secret"
        fake.resourceMetadata = "https://example.com/.well-known/oauth-protected-resource"
        assertEquals(1, client("secret").listTools().size)
        try {
            client("wrong").listTools()
            fail("a refused token must throw")
        } catch (e: McpException) {
            assertTrue(e.unauthorized)
            assertEquals("https://example.com/.well-known/oauth-protected-resource", e.resourceMetadata)
        }
    }

    @Test
    fun `server errors become exceptions`() = runBlocking {
        try {
            client().callTool("boom", JsonObject(emptyMap()))
            fail("an error answer must throw")
        } catch (e: McpException) {
            assertTrue(e.message!!.contains("Unknown tool"))
            assertFalse(e.unauthorized)
        }
    }
}

/** The old HTTP+SSE transport (Home Assistant): the stream names where to post, and carries the answers. */
class McpLegacyClientTest {
    @Test
    fun `talks to an old server through its event stream`() = runBlocking {
        val posted = Collections.synchronizedList(ArrayList<String>())
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.method == "GET" -> MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                    "event: endpoint\ndata: /messages/?session_id=abc\n\n" +
                        "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":\"2024-11-05\"}}\n\n" +
                        "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"tools\":[{\"name\":\"HassTurnOn\",\"inputSchema\":{\"type\":\"object\"}}]}}\n\n",
                )
                else -> {
                    posted += request.path!!
                    MockResponse().setResponseCode(202)
                }
            }
        }
        server.start()
        try {
            val tools = McpClient(OkHttpClient(), server.url("/mcp_server/sse").toString(), { "tok" }).listTools()
            assertEquals(listOf("HassTurnOn"), tools.map { it.name })
            assertEquals(3, posted.size)
            assertTrue(posted.all { it == "/messages/?session_id=abc" })
        } finally {
            server.shutdown()
        }
    }
}
