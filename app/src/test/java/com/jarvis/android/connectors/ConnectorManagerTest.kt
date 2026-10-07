package com.jarvis.android.connectors

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
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
import org.junit.Before
import org.junit.Test

class ConnectorManagerTest {
    private lateinit var server: MockWebServer
    private val mcp = FakeMcpServer()
    private var stored: String? = null
    private var tokenForm: String? = null

    @Before
    fun start() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val base = server.url("/").toString().trimEnd('/')
                val json = { body: String -> MockResponse().setHeader("Content-Type", "application/json").setBody(body) }
                return when (request.path) {
                    "/.well-known/oauth-protected-resource/mcp" -> json("""{"resource":"$base/mcp","authorization_servers":["$base/auth"],"scopes_supported":["read"]}""")
                    "/.well-known/oauth-authorization-server/auth" -> json(
                        """{"issuer":"$base/auth","authorization_endpoint":"$base/auth/authorize","token_endpoint":"$base/auth/token","registration_endpoint":"$base/auth/register"}""",
                    )
                    "/auth/register" -> json("""{"client_id":"jarvis-123"}""")
                    "/auth/token" -> {
                        tokenForm = request.body.readUtf8()
                        json("""{"access_token":"granted","refresh_token":"again","expires_in":3600,"token_type":"Bearer"}""")
                    }
                    else -> if (request.path!!.startsWith("/mcp")) mcp.dispatch(request) else MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        InstalledConnectors.set(emptyList())
    }

    @After
    fun stop() {
        server.shutdown()
        InstalledConnectors.set(emptyList())
    }

    private fun manager() = ConnectorManager(
        http = OkHttpClient(),
        load = { stored },
        save = { stored = it },
        reservedNames = { setOf("weather") },
        urlAllowed = { true },
    )

    @Test
    fun `a connector with a token brings its tools`() = runBlocking {
        mcp.requireToken = "pat"
        val m = manager()
        val outcome = m.add("Mes notes", server.url("/mcp").toString(), "pat")
        assertNull(outcome.loginUrl)
        assertTrue(outcome.message, outcome.message.contains("1 outil"))
        val tool = InstalledConnectors.all().single()
        assertEquals("mes_notes_search", tool.name)
        assertTrue(tool.description.startsWith("[Connecteur Mes notes]"))
        assertEquals("Trouvé : budget", m.call(tool.connectorId, tool.remote, JsonObject(mapOf("query" to JsonPrimitive("budget")))))

        // kept (with its tools) for the next start, before any server answer
        InstalledConnectors.set(emptyList())
        manager().loadCached()
        assertEquals(listOf("mes_notes_search"), InstalledConnectors.all().map { it.name })

        m.setEnabled("mes_notes", false)
        assertTrue(InstalledConnectors.all().isEmpty())
        assertTrue(m.call(tool.connectorId, tool.remote, JsonObject(emptyMap())).contains("désactivé"))
        m.setEnabled("mes_notes", true)
        m.remove("mes_notes")
        assertTrue(InstalledConnectors.all().isEmpty())
        assertTrue(m.list().isEmpty())
    }

    @Test
    fun `a server that wants a login gets one in the browser`() = runBlocking {
        mcp.requireToken = "granted"
        mcp.resourceMetadata = server.url("/.well-known/oauth-protected-resource/mcp").toString()
        val m = manager()
        val outcome = m.add("Notion", server.url("/mcp").toString())
        val login = outcome.loginUrl!!.toHttpUrl()
        assertEquals("/auth/authorize", login.encodedPath)
        assertEquals("jarvis-123", login.queryParameter("client_id"))
        assertEquals("S256", login.queryParameter("code_challenge_method"))
        assertEquals(OAUTH_REDIRECT_URI, login.queryParameter("redirect_uri"))
        assertEquals("read", login.queryParameter("scope"))
        assertEquals(server.url("/mcp").toString(), login.queryParameter("resource"))
        assertTrue(m.list().single().needsLogin)
        assertTrue(InstalledConnectors.all().isEmpty())

        assertTrue(m.finishLogin("code-1", "wrong state", null).message.contains("inattendue"))
        val done = m.finishLogin("code-1", login.queryParameter("state"), null)
        assertTrue(done.message, done.message.contains("connecté"))
        assertTrue(tokenForm!!.contains("code_verifier="))
        assertTrue(tokenForm!!.contains("grant_type=authorization_code"))
        val c = m.list().single()
        assertFalse(c.needsLogin)
        assertEquals("granted", c.oauth!!.accessToken)
        assertEquals(listOf("notion_search"), InstalledConnectors.all().map { it.name })
        assertNull(decodeConnectors(stored).pending)
    }

    @Test
    fun `a refused login says so and an unknown address is refused`() = runBlocking {
        val m = ConnectorManager(OkHttpClient(), { stored }, { stored = it }, { emptySet() })
        assertTrue(m.add("x", "http://192.168.1.2/mcp").message.startsWith("Adresse refusée"))
        assertTrue(m.finishLogin(null, "s", "access_denied").message.contains("Aucune connexion"))
    }
}
