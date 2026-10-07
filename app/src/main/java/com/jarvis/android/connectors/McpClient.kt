package com.jarvis.android.connectors

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSource
import java.io.IOException
import java.util.concurrent.TimeUnit

/** A server refused or failed a request. [unauthorized]: it wants a login (HTTP 401/403); [resourceMetadata]: where it says how. */
internal class McpException(
    message: String,
    val unauthorized: Boolean = false,
    val resourceMetadata: String? = null,
) : IOException(message)

/**
 * A client of one remote MCP server. Speaks the Streamable HTTP transport (one POST per JSON-RPC message, the answer as JSON or as a
 * short event stream) and, for older servers such as Home Assistant's, the HTTP+SSE transport (an event stream that names where to
 * POST, the answers coming back on the stream). [bearer] gives the token to send, read again before every request.
 */
internal class McpClient(
    http: OkHttpClient,
    private val url: String,
    private val bearer: () -> String?,
    private val clientVersion: String = "1",
) {
    private val http = http.newBuilder().readTimeout(90, TimeUnit.SECONDS).build()
    private val lock = Mutex()
    private var sessionId: String? = null
    private var protocolVersion: String? = null
    private var initialized = false
    private var legacy = urlLooksLegacy(url)
    private var legacyTried = false
    private var streamableTried = false
    private var nextId = 1

    suspend fun listTools(): List<RemoteTool> = lock.withLock {
        withContext(Dispatchers.IO) {
            val tools = ArrayList<RemoteTool>()
            var cursor: String? = null
            var pages = 0
            do {
                val params = buildJsonObject { cursor?.let { put("cursor", it) } }
                val result = request("tools/list", params)
                (result["tools"] as? JsonArray).orEmpty().forEach { item ->
                    val o = item as? JsonObject ?: return@forEach
                    val name = (o["name"] as? JsonPrimitive)?.contentOrNull ?: return@forEach
                    tools += RemoteTool(
                        name = name,
                        description = ((o["description"] as? JsonPrimitive)?.contentOrNull ?: (o["title"] as? JsonPrimitive)?.contentOrNull).orEmpty(),
                        inputSchema = o["inputSchema"] as? JsonObject ?: JsonObject(emptyMap()),
                    )
                }
                cursor = (result["nextCursor"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
                pages++
            } while (cursor != null && pages < 20)
            tools
        }
    }

    suspend fun callTool(name: String, arguments: JsonObject): JsonObject = lock.withLock {
        withContext(Dispatchers.IO) {
            request("tools/call", buildJsonObject {
                put("name", name)
                put("arguments", arguments)
            })
        }
    }

    /** Forgets the session (after a new token, for instance): the next request starts a new one. */
    fun reset() {
        sessionId = null
        initialized = false
    }

    private fun request(method: String, params: JsonObject): JsonObject {
        if (legacy) {
            try {
                return legacyExchange(method, params)
            } catch (e: NotLegacy) {
                // the address only looked like an old server's
                if (streamableTried) throw McpException(e.message ?: "Le serveur ne répond pas au protocole MCP.")
                legacy = false
            }
        }
        return streamable(method, params)
    }

    private fun streamable(method: String, params: JsonObject): JsonObject {
        streamableTried = true
        return try {
            if (!initialized) initialize()
            try {
                post(method, params)
            } catch (e: SessionExpired) {
                reset()
                initialize()
                post(method, params)
            }
        } catch (e: NotStreamable) {
            if (legacyTried) throw McpException(e.message ?: "Le serveur ne répond pas au protocole MCP.")
            legacy = true
            legacyExchange(method, params)
        }
    }

    private class SessionExpired : IOException()
    private class NotStreamable(message: String) : IOException(message)
    private class NotLegacy(message: String) : IOException(message)

    private fun initializeParams() = buildJsonObject {
        put("protocolVersion", MCP_PROTOCOL_VERSION)
        putJsonObject("capabilities") {}
        putJsonObject("clientInfo") {
            put("name", "Jarvis Android")
            put("version", clientVersion)
        }
    }

    private fun initialize() {
        val result = post("initialize", initializeParams(), initializing = true)
        protocolVersion = (result["protocolVersion"] as? JsonPrimitive)?.contentOrNull
        notify("notifications/initialized")
        initialized = true
    }

    private fun baseRequest(target: String): Request.Builder {
        val b = Request.Builder().url(target)
        bearer()?.takeIf { it.isNotBlank() }?.let { b.header("Authorization", "Bearer $it") }
        return b
    }

    private fun rpcBody(method: String, params: JsonObject?, id: Int?): String = buildJsonObject {
        put("jsonrpc", "2.0")
        id?.let { put("id", it) }
        put("method", method)
        params?.let { put("params", it) }
    }.toString()

    private fun streamableRequest(body: String): Request {
        val b = baseRequest(url)
            .header("Accept", "application/json, text/event-stream")
            .post(body.toRequestBody(JSON))
        sessionId?.let { b.header("Mcp-Session-Id", it) }
        protocolVersion?.let { b.header("MCP-Protocol-Version", it) }
        return b.build()
    }

    private fun notify(method: String) {
        http.newCall(streamableRequest(rpcBody(method, null, null))).execute().use { r ->
            if (r.code == 401 || r.code == 403) throw unauthorized(r)
        }
    }

    private fun post(method: String, params: JsonObject, initializing: Boolean = false): JsonObject {
        val id = nextId++
        http.newCall(streamableRequest(rpcBody(method, params, id))).execute().use { r ->
            if (r.code == 401 || r.code == 403) throw unauthorized(r)
            if (r.code == 404 && sessionId != null && !initializing) throw SessionExpired()
            if (!r.isSuccessful) {
                if (initializing && r.code in setOf(400, 404, 405, 406, 415)) throw NotStreamable("Le serveur a répondu ${r.code}.")
                throw McpException("Le serveur a répondu ${r.code}.")
            }
            if (initializing) r.header("Mcp-Session-Id")?.let { sessionId = it }
            val type = r.header("Content-Type").orEmpty()
            val source = r.body?.source() ?: throw McpException("Réponse vide du serveur.")
            val message = if (type.contains("text/event-stream")) readUntilAnswer(source, id)
            else parseMessage(source.readUtf8(), id)
            return resultOf(message ?: throw McpException("Le serveur n’a pas répondu à la requête."))
        }
    }

    /** Reads an event stream until the answer to [id] (other messages, notifications or pings, are skipped). */
    private fun readUntilAnswer(source: BufferedSource, id: Int): JsonObject? {
        val reader = SseReader()
        while (true) {
            val line = source.readUtf8Line() ?: break
            val event = reader.line(line) ?: continue
            if (event.event != "message") continue
            parseMessage(event.data, id)?.let { return it }
        }
        return null
    }

    // HTTP+SSE (protocol 2024-11-05): one stream per exchange, closed when done, so that nothing stays open in the background.
    private fun legacyExchange(method: String, params: JsonObject): JsonObject {
        legacyTried = true
        val get = baseRequest(url).header("Accept", "text/event-stream").get().build()
        http.newCall(get).execute().use { r ->
            if (r.code == 401 || r.code == 403) throw unauthorized(r)
            if (!r.isSuccessful) throw NotLegacy("Le serveur a répondu ${r.code}.")
            if (!r.header("Content-Type").orEmpty().contains("text/event-stream")) throw NotLegacy("Le serveur n’ouvre pas de flux d’événements.")
            val source = r.body?.source() ?: throw NotLegacy("Réponse vide du serveur.")
            val reader = SseReader()
            var endpoint: String? = null
            while (endpoint == null) {
                val line = source.readUtf8Line() ?: throw NotLegacy("Le serveur a fermé le flux sans donner d’adresse.")
                val event = reader.line(line) ?: continue
                if (event.event == "endpoint") endpoint = resolveUrl(event.data.trim())
            }
            fun send(method: String, params: JsonObject?, id: Int?) {
                val req = baseRequest(endpoint).post(rpcBody(method, params, id).toRequestBody(JSON)).build()
                http.newCall(req).execute().use { p ->
                    if (p.code == 401 || p.code == 403) throw unauthorized(p)
                    if (!p.isSuccessful) throw McpException("Le serveur a répondu ${p.code}.")
                }
            }
            fun await(id: Int): JsonObject {
                while (true) {
                    val line = source.readUtf8Line() ?: throw McpException("Le serveur a fermé le flux avant de répondre.")
                    val event = reader.line(line) ?: continue
                    if (event.event != "message") continue
                    parseMessage(event.data, id)?.let { return resultOf(it) }
                }
            }
            val initId = nextId++
            send("initialize", initializeParams(), initId)
            await(initId)
            send("notifications/initialized", null, null)
            val id = nextId++
            send(method, params, id)
            return await(id)
        }
    }

    private fun resolveUrl(endpoint: String): String =
        url.toHttpUrlOrNull()?.resolve(endpoint)?.toString() ?: throw McpException("Adresse de messages invalide : $endpoint")

    private fun unauthorized(r: Response) = McpException(
        "Le serveur demande une connexion (${r.code}).",
        unauthorized = true,
        resourceMetadata = resourceMetadataFromHeader(r.header("WWW-Authenticate")),
    )

    companion object {
        private val JSON = "application/json".toMediaType()

        /** Home Assistant and other older servers give an address ending in /sse. */
        fun urlLooksLegacy(url: String): Boolean = url.substringBefore('?').trimEnd('/').endsWith("/sse")

        /** The JSON-RPC answer to [id] in [text] (an object, or a batch), or null when [text] holds something else. */
        fun parseMessage(text: String, id: Int): JsonObject? {
            val element = try { Json.parseToJsonElement(text) } catch (_: Exception) { return null }
            val candidates: List<JsonElement> = if (element is JsonArray) element else listOf(element)
            return candidates.filterIsInstance<JsonObject>().firstOrNull { (it["id"] as? JsonPrimitive)?.contentOrNull == id.toString() }
        }

        /** The result of an answer, or its error as an exception. */
        fun resultOf(message: JsonObject): JsonObject {
            (message["error"] as? JsonObject)?.let { error ->
                val text = (error["message"] as? JsonPrimitive)?.contentOrNull ?: error.toString()
                throw McpException("Erreur du serveur : $text")
            }
            return message["result"] as? JsonObject ?: JsonObject(emptyMap())
        }
    }
}
