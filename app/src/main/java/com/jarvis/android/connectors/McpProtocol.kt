package com.jarvis.android.connectors

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.Normalizer
import java.util.Base64

/*
 * The pure parts of the Model Context Protocol client: turning an MCP tool's JSON Schema into a Gemini function declaration,
 * naming the tools, reading results and server-sent events, and the URLs of the OAuth discovery. No network here.
 */

/** The protocol version Jarvis asks for; the server answers with the one it speaks. */
internal const val MCP_PROTOCOL_VERSION = "2025-06-18"

/** A tool's answer is cut beyond this, so that one big result does not fill the conversation. */
internal const val MAX_RESULT_CHARS = 12_000

/** Below this depth, an object or a list is asked of the model as JSON text (see [reviveArguments]). */
private const val MAX_SCHEMA_DEPTH = 4

private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

/** "Mon Notion !" → "mon_notion": the prefix of a connector's tools, also its id. */
internal fun connectorSlug(name: String): String {
    val plain = Normalizer.normalize(name, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
    val slug = plain.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').take(20).trim('_')
    return when {
        slug.isEmpty() -> "connecteur"
        slug[0].isLetter() -> slug
        else -> "c_$slug"
    }
}

/** The name the model sees for [tool] of the connector [slug]: letters, digits and _ only, 64 characters at most, not in [taken]. */
internal fun connectorToolName(slug: String, tool: String, taken: Set<String>): String {
    val clean = tool.replace(Regex("[^A-Za-z0-9_]+"), "_").trim('_').ifEmpty { "outil" }
    val base = "${slug}_$clean".take(64)
    if (base !in taken) return base
    var n = 2
    while (true) {
        val suffix = "_$n"
        val candidate = base.take(64 - suffix.length) + suffix
        if (candidate !in taken) return candidate
        n++
    }
}

/**
 * The parameters of an MCP tool (JSON Schema) as a Gemini function declaration accepts them: upper-case types, no `$ref`, `anyOf` or
 * `additionalProperties`. A free-form or very deep object is asked as JSON text and turned back by [reviveArguments].
 */
internal fun geminiParameters(inputSchema: JsonObject): JsonObject {
    val defs = definitions(inputSchema)
    val root = convertSchema(inputSchema, defs, 0, root = true)
    return if (root["type"].str() == "OBJECT") root else buildJsonObject {
        put("type", "OBJECT")
        putJsonObject("properties") {}
    }
}

private fun definitions(schema: JsonObject): Map<String, JsonObject> {
    val out = HashMap<String, JsonObject>()
    for (key in listOf("\$defs", "definitions")) {
        (schema[key] as? JsonObject)?.forEach { (name, value) -> if (value is JsonObject) out["#/$key/$name"] = value }
    }
    return out
}

/** The schema itself once `$ref`, `anyOf`/`oneOf`/`allOf` are resolved to one branch; and whether null is allowed. */
private fun resolve(schema: JsonObject, defs: Map<String, JsonObject>): Pair<JsonObject, Boolean> {
    var s = schema
    var nullable = false
    repeat(4) {
        val ref = s["\$ref"].str()
        if (ref != null) {
            val target = defs[ref] ?: return s to nullable
            s = JsonObject(target + s.filterKeys { it == "description" || it == "title" })
            return@repeat
        }
        val branches = (s["anyOf"] ?: s["oneOf"] ?: s["allOf"]) as? JsonArray ?: return s to nullable
        val objects = branches.filterIsInstance<JsonObject>()
        if (objects.any { it["type"].str() == "null" }) nullable = true
        val chosen = objects.firstOrNull { it["type"].str() != "null" } ?: return s to nullable
        s = JsonObject(chosen + s.filterKeys { it == "description" || it == "title" })
    }
    return s to nullable
}

private fun schemaType(s: JsonObject): Pair<String, Boolean> {
    val raw = s["type"]
    val names = when (raw) {
        is JsonArray -> raw.mapNotNull { it.str() }
        is JsonPrimitive -> listOfNotNull(raw.contentOrNull)
        else -> emptyList()
    }
    val nullable = "null" in names
    val type = names.firstOrNull { it != "null" } ?: when {
        s["properties"] is JsonObject -> "object"
        s["items"] != null -> "array"
        else -> "string"
    }
    return type to nullable
}

private fun convertSchema(schema: JsonObject, defs: Map<String, JsonObject>, depth: Int, root: Boolean = false): JsonObject {
    val (s, nullableBranch) = resolve(schema, defs)
    val (type, nullableType) = schemaType(s)
    val description = (s["description"].str() ?: s["title"].str())?.trim()?.take(400)
    val properties = s["properties"] as? JsonObject
    val asJsonText = !root && when (type) {
        "object" -> properties.isNullOrEmpty() || depth >= MAX_SCHEMA_DEPTH
        "array" -> depth >= MAX_SCHEMA_DEPTH
        else -> s["\$ref"] != null
    }
    return buildJsonObject {
        if (asJsonText) {
            put("type", "STRING")
            val kind = if (type == "array") "une liste JSON" else "un objet JSON"
            put("description", listOfNotNull(description, "À écrire comme $kind.").joinToString(" "))
            return@buildJsonObject
        }
        when (type) {
            "object" -> {
                put("type", "OBJECT")
                description?.let { put("description", it) }
                putJsonObject("properties") {
                    properties?.forEach { (name, value) ->
                        if (value is JsonObject) put(name, convertSchema(value, defs, depth + 1))
                    }
                }
                val names = properties?.keys.orEmpty()
                val required = (s["required"] as? JsonArray)?.mapNotNull { it.str() }?.filter { it in names }.orEmpty()
                if (required.isNotEmpty()) putJsonArray("required") { required.forEach { add(JsonPrimitive(it)) } }
            }
            "array" -> {
                put("type", "ARRAY")
                description?.let { put("description", it) }
                val items = s["items"] as? JsonObject ?: JsonObject(mapOf("type" to JsonPrimitive("string")))
                put("items", convertSchema(items, defs, depth + 1))
            }
            "integer", "number", "boolean" -> {
                put("type", type.uppercase())
                val values = (s["enum"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                val text = listOfNotNull(description, values?.takeIf { it.isNotEmpty() }?.let { "Valeurs : ${it.joinToString(", ")}." }).joinToString(" ")
                if (text.isNotEmpty()) put("description", text)
            }
            else -> {
                put("type", "STRING")
                description?.let { put("description", it) }
                val values = (s["enum"] as? JsonArray)?.mapNotNull { it.str() }
                if (!values.isNullOrEmpty()) {
                    put("format", "enum")
                    putJsonArray("enum") { values.forEach { add(JsonPrimitive(it)) } }
                }
            }
        }
        if (nullableBranch || nullableType) put("nullable", true)
    }
}

/**
 * The arguments as the server expects them: what [geminiParameters] asked as JSON text is parsed back, numbers written as text are
 * left to the server. Anything that does not parse is passed unchanged.
 */
internal fun reviveArguments(args: JsonObject, inputSchema: JsonObject): JsonObject {
    val defs = definitions(inputSchema)
    return revive(args, inputSchema, defs) as? JsonObject ?: args
}

private fun revive(value: JsonElement, schema: JsonObject, defs: Map<String, JsonObject>): JsonElement {
    val (s, _) = resolve(schema, defs)
    val (type, _) = schemaType(s)
    if (value is JsonPrimitive && value.isString && (type == "object" || type == "array")) {
        val parsed = try { kotlinx.serialization.json.Json.parseToJsonElement(value.content) } catch (_: Exception) { null }
        if (parsed is JsonObject || parsed is JsonArray) return revive(parsed, s, defs)
        return value
    }
    return when {
        value is JsonObject && type == "object" -> {
            val properties = s["properties"] as? JsonObject ?: return value
            JsonObject(value.mapValues { (k, v) -> (properties[k] as? JsonObject)?.let { revive(v, it, defs) } ?: v })
        }
        value is JsonArray && type == "array" -> {
            val items = s["items"] as? JsonObject ?: return value
            JsonArray(value.map { revive(it, items, defs) })
        }
        else -> value
    }
}

/** What a `tools/call` result says, as text for the model. */
internal fun toolResultText(result: JsonObject): String {
    val parts = (result["content"] as? JsonArray).orEmpty().mapNotNull { item ->
        val o = item as? JsonObject ?: return@mapNotNull null
        when (o["type"].str()) {
            "text" -> o["text"].str()
            "image" -> "[image ${o["mimeType"].str().orEmpty()}]".replace(" ]", "]")
            "audio" -> "[audio]"
            "resource_link" -> listOfNotNull(o["name"].str(), o["uri"].str()).joinToString(" : ")
            "resource" -> (o["resource"] as? JsonObject)?.let { it["text"].str() ?: it["uri"].str() }
            else -> null
        }
    }
    var text = parts.joinToString("\n").trim()
    if (text.isEmpty()) text = result["structuredContent"]?.takeIf { it !is JsonNull }?.toString().orEmpty()
    if (text.isEmpty()) text = "OK (aucun contenu renvoyé)."
    if ((result["isError"] as? JsonPrimitive)?.contentOrNull == "true") text = "Le connecteur signale une erreur : $text"
    return if (text.length > MAX_RESULT_CHARS) text.take(MAX_RESULT_CHARS) + "… (réponse tronquée)" else text
}

/** One server-sent event: its name ("message" when unnamed) and its data lines joined. */
internal data class SseEvent(val event: String, val data: String)

/** Collects lines of a `text/event-stream` into events; feed it line by line, it returns an event when one is complete. */
internal class SseReader {
    private var event = ""
    private val data = StringBuilder()

    fun line(line: String): SseEvent? {
        if (line.isEmpty()) {
            if (data.isEmpty() && event.isEmpty()) return null
            val done = SseEvent(event.ifEmpty { "message" }, data.toString())
            event = ""
            data.setLength(0)
            return done
        }
        if (line.startsWith(":")) return null
        val field = line.substringBefore(':')
        val value = line.substringAfter(':', "").removePrefix(" ")
        when (field) {
            "event" -> event = value
            "data" -> { if (data.isNotEmpty()) data.append('\n'); data.append(value) }
        }
        return null
    }
}

/** Every event of a complete `text/event-stream` body. */
internal fun parseSse(body: String): List<SseEvent> {
    val reader = SseReader()
    return (body.split("\r\n", "\n") + "").mapNotNull { reader.line(it) }
}

/** The `resource_metadata` URL of a `WWW-Authenticate: Bearer …` header, if it names one. */
internal fun resourceMetadataFromHeader(header: String?): String? =
    header?.let { Regex("resource_metadata=\"([^\"]+)\"").find(it)?.groupValues?.get(1) }

private fun origin(url: String): String {
    val u = URI(url)
    val port = if (u.port == -1) "" else ":${u.port}"
    return "${u.scheme}://${u.host}$port"
}

private fun path(url: String): String = URI(url).rawPath.orEmpty().trimEnd('/')

/** Where an MCP server may describe how to log in to it (RFC 9728), most specific first. */
internal fun protectedResourceMetadataUrls(serverUrl: String): List<String> {
    val o = origin(serverUrl)
    val p = path(serverUrl)
    return listOfNotNull(if (p.isNotEmpty()) "$o/.well-known/oauth-protected-resource$p" else null, "$o/.well-known/oauth-protected-resource")
}

/** Where an authorization server describes itself (RFC 8414, then OpenID Connect), most specific first. */
internal fun authorizationServerMetadataUrls(issuer: String): List<String> {
    val o = origin(issuer)
    val p = path(issuer)
    return if (p.isEmpty()) listOf("$o/.well-known/oauth-authorization-server", "$o/.well-known/openid-configuration")
    else listOf("$o/.well-known/oauth-authorization-server$p", "$o/.well-known/openid-configuration$p", "$o$p/.well-known/openid-configuration")
}

/** The server's address as the OAuth `resource` parameter wants it: no fragment, no final slash. */
internal fun canonicalResource(serverUrl: String): String = serverUrl.substringBefore('#').trimEnd('/')

/** The server's origin, the authorization server to try when the server names none. */
internal fun originOf(url: String): String = origin(url)

private val random = SecureRandom()

/** A random URL-safe string, for the PKCE verifier and the OAuth state. */
internal fun randomToken(bytes: Int = 32): String {
    val b = ByteArray(bytes).also(random::nextBytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(b)
}

/** The PKCE S256 challenge of [verifier]. */
internal fun pkceChallenge(verifier: String): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

/** Whether [url] is an address a connector may have: https only (Android refuses clear-text HTTP to this app). */
internal fun connectorUrlAllowed(url: String): Boolean {
    val u = try { URI(url.trim()) } catch (_: Exception) { return false }
    return u.scheme?.lowercase() == "https" && !u.host.isNullOrEmpty()
}
