package com.jarvis.android.rest

import com.jarvis.android.i18n.tr
import com.jarvis.android.i18n.trf
import com.jarvis.android.text.stripThink
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.URI

/*
 * Another model than Gemini, after Mark LVI's providers/llm_openai.py: anything that serves `/v1/chat/completions` (Ollama, LM Studio,
 * llama.cpp, vLLM on the user's PC, or OpenAI, Groq, DeepSeek, Mistral, xAI, OpenRouter, Together…). One setting covers all of them
 * because they speak the same protocol. The rest of the app keeps building Gemini requests and reading Gemini answers: this file
 * translates one into the other and back, so the chat, the tools and every side call (memory, documents, the agent) work unchanged.
 * A request that needs what only Gemini does (Google Search grounding, audio, a PDF, a spoken answer) is refused here, and the caller
 * keeps Gemini for it.
 */

/** One of the usual services, to fill the address in one tap. [needsKey]: a hosted API (a key is required). */
internal data class OtherModelPreset(val label: String, val baseUrl: String, val model: String, val needsKey: Boolean)

internal val OTHER_MODEL_PRESETS = listOf(
    OtherModelPreset("Ollama (PC)", "http://ADRESSE-DU-PC:11434/v1", "qwen3:8b", needsKey = false),
    OtherModelPreset("LM Studio (PC)", "http://ADRESSE-DU-PC:1234/v1", "", needsKey = false),
    OtherModelPreset("Ollama (téléphone, Termux)", "http://127.0.0.1:11434/v1", "qwen3:1.7b", needsKey = false),
    OtherModelPreset("Groq", "https://api.groq.com/openai/v1", "llama-3.3-70b-versatile", needsKey = true),
    OtherModelPreset("OpenRouter", "https://openrouter.ai/api/v1", "", needsKey = true),
    OtherModelPreset("Mistral", "https://api.mistral.ai/v1", "mistral-small-latest", needsKey = true),
    OtherModelPreset("DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat", needsKey = true),
    OtherModelPreset("OpenAI", "https://api.openai.com/v1", "gpt-4.1-mini", needsKey = true),
    OtherModelPreset("xAI", "https://api.x.ai/v1", "grok-3-mini", needsKey = true),
    OtherModelPreset("Together", "https://api.together.xyz/v1", "", needsKey = true),
)

/** The settings of the other model; [use] is one of the OTHER_MODEL_* values. */
internal data class OtherModelConfig(val baseUrl: String, val model: String, val key: String?, val use: Int, val voice: Boolean) {
    val configured: Boolean get() = baseUrl.isNotBlank() && model.isNotBlank()
}

/** Never used. */
internal const val OTHER_MODEL_OFF = 0
/** When Gemini cannot answer: out of quota on every key and model, not answering, no key at all. */
internal const val OTHER_MODEL_FALLBACK = 1
/** For every text request it can handle, Gemini only for the rest (Google Search, audio, PDF, voices). */
internal const val OTHER_MODEL_ALWAYS = 2

/**
 * The address as the API wants it: a scheme (http for a private address, https otherwise), `localhost` written 127.0.0.1 (Mark LVI
 * found `localhost` resolving to ::1 first while Ollama and llama.cpp listen on IPv4 only), no trailing `/chat/completions` or slash,
 * and `/v1` added when the user gave only a host and port (what Ollama and LM Studio print).
 */
internal fun normalizeBaseUrl(raw: String): String {
    var url = raw.trim().trimEnd('/')
    if (url.isEmpty()) return ""
    if (!url.contains("://")) {
        val host = url.substringBefore('/').substringBefore(':')
        url = (if (isPrivateHost(host)) "http://" else "https://") + url
    }
    url = url.removeSuffix("/chat/completions").removeSuffix("/models").trimEnd('/')
    url = url.replaceFirst(Regex("^(https?://)localhost(?=[:/]|$)", RegexOption.IGNORE_CASE), "$1127.0.0.1")
    val path = try { URI(url).path.orEmpty() } catch (_: Exception) { "" }
    if (path.isEmpty() || path == "/") url += "/v1"
    return url
}

/**
 * True for a model on the user's own machines: the phone itself, the home network, Tailscale (100.64.0.0/10 and *.ts.net), *.local
 * and *.lan names. Such a model gets the tool budget (a small model drowns in ninety tools) and plain http is accepted for it.
 */
internal fun isPrivateHost(host: String): Boolean {
    val h = host.trim().trim('[', ']').lowercase()
    if (h.isEmpty()) return false
    if (h == "localhost" || h == "::1" || h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".ts.net") || h.endsWith(".home.arpa")) return true
    val parts = h.split('.').mapNotNull { it.toIntOrNull() }
    if (parts.size != 4 || h.count { it == '.' } != 3) return h.startsWith("fd") || h.startsWith("fe80")
    val (a, b) = parts[0] to parts[1]
    return a == 127 || a == 10 || (a == 192 && b == 168) || (a == 172 && b in 16..31) || (a == 100 && b in 64..127) || (a == 169 && b == 254)
}

internal fun isPrivateEndpoint(baseUrl: String): Boolean = try {
    isPrivateHost(URI(normalizeBaseUrl(baseUrl)).host.orEmpty())
} catch (_: Exception) {
    false
}

/** A request the other model cannot take (it needs Gemini itself); the message says what. */
internal class NotForOtherModel(why: String) : Exception(why)

/** What a small model on the user's own hardware is given: at most [count] tools and [chars] characters of their declarations. */
internal data class ToolBudget(val count: Int = 14, val chars: Int = 12_000)

private const val BRIEF_TOOL_CHARS = 220
private const val BRIEF_PARAM_CHARS = 90

/** The first sentence of [text], within [max] characters: the short form of a description, for a model with little room. */
internal fun briefOf(text: String, max: Int): String {
    val flat = text.replace(Regex("\\s+"), " ").trim()
    val sentence = Regex("^(.+?[.!?])(\\s|$)").find(flat)?.groupValues?.get(1) ?: flat
    return if (sentence.length <= max) sentence else sentence.take(max - 1).trimEnd() + "…"
}

/** A Gemini schema as JSON Schema: lower-case types, Gemini-only keys dropped, descriptions shortened when [brief]. */
internal fun toJsonSchema(schema: JsonElement, brief: Boolean): JsonElement {
    if (schema is JsonArray) return JsonArray(schema.map { toJsonSchema(it, brief) })
    if (schema !is JsonObject) return schema
    return buildJsonObject {
        for ((key, value) in schema) {
            when (key) {
                "type" -> put("type", (value as? JsonPrimitive)?.contentOrNull?.lowercase() ?: "string")
                "nullable", "propertyOrdering", "example" -> {}
                "description" -> put("description", (value as? JsonPrimitive)?.contentOrNull.orEmpty().let { if (brief) briefOf(it, BRIEF_PARAM_CHARS) else it })
                "properties" -> putJsonObject("properties") {
                    (value as? JsonObject)?.forEach { (name, prop) -> put(name, toJsonSchema(prop, brief)) }
                }
                else -> put(key, toJsonSchema(value, brief))
            }
        }
    }
}

/** One OpenAI tool from one Gemini function declaration. */
internal fun toOpenAiTool(declaration: JsonObject, brief: Boolean): JsonObject = buildJsonObject {
    put("type", "function")
    putJsonObject("function") {
        put("name", declaration["name"]?.jsonPrimitive?.contentOrNull.orEmpty())
        val description = declaration["description"]?.jsonPrimitive?.contentOrNull.orEmpty()
        put("description", if (brief) briefOf(description, BRIEF_TOOL_CHARS) else description)
        put("parameters", toJsonSchema(declaration["parameters"] ?: buildJsonObject { put("type", "OBJECT"); putJsonObject("properties") {} }, brief))
    }
}

/** The tool declarations of a Gemini request (functionDeclarations), or a refusal when it asks for one of Gemini's own tools. */
internal fun functionDeclarationsOf(request: JsonObject): List<JsonObject> {
    val out = mutableListOf<JsonObject>()
    for (tool in request["tools"]?.jsonArray.orEmpty()) {
        val obj = tool as? JsonObject ?: continue
        for (key in obj.keys) {
            if (key == "functionDeclarations" || key == "function_declarations") out += obj[key]!!.jsonArray.mapNotNull { it as? JsonObject }
            else throw NotForOtherModel(trf("cette demande utilise un outil propre à Gemini ({0})", key))
        }
    }
    return out
}

/** The last thing the user wrote in a Gemini request, for choosing the tools that fit it. */
internal fun lastUserText(request: JsonObject): String =
    request["contents"]?.jsonArray.orEmpty().mapNotNull { it as? JsonObject }
        .lastOrNull { it["role"]?.jsonPrimitive?.contentOrNull == "user" && it["parts"]?.jsonArray.orEmpty().any { p -> (p as? JsonObject)?.get("text") != null } }
        ?.get("parts")?.jsonArray.orEmpty().mapNotNull { (it as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull }.joinToString(" ")

/**
 * The declarations within [budget]: [pick] puts them in order of use for the question (the ones it leaves out come last), then they
 * are taken while both the count and the size fit. Counting alone is half a fix (Mark LVI: 20 tools to 8 only halved the characters,
 * because the useful tools are the long ones), so both limits apply, whichever bites first.
 */
internal fun withinBudget(declarations: List<JsonObject>, budget: ToolBudget, ordered: List<JsonObject>): List<JsonObject> {
    val rest = declarations.filter { d -> ordered.none { it["name"] == d["name"] } }
    val kept = mutableListOf<JsonObject>()
    var size = 0
    for (d in ordered + rest) {
        if (kept.size >= budget.count) break
        val cost = toOpenAiTool(d, brief = true).toString().length
        if (size + cost > budget.chars) continue
        kept += d
        size += cost
    }
    return kept
}

/**
 * The Gemini `generateContent` [request] as an OpenAI `chat/completions` body for [model]. [tools] are the declarations to offer
 * (all of them, or the budget's choice), shortened when [brief]. Throws [NotForOtherModel] for what only Gemini can do.
 */
internal fun toOpenAiRequest(model: String, request: JsonObject, tools: List<JsonObject>, brief: Boolean): JsonObject {
    val config = request["generationConfig"] as? JsonObject
    config?.get("responseModalities")?.jsonArray?.let { m ->
        if (m.any { it.jsonPrimitive.contentOrNull?.uppercase() == "AUDIO" }) throw NotForOtherModel(tr("une réponse parlée"))
    }
    val messages = buildJsonArray {
        val system = (request["systemInstruction"] as? JsonObject)?.get("parts")?.jsonArray.orEmpty()
            .mapNotNull { (it as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull }.joinToString("\n")
        if (system.isNotBlank()) addJsonObject { put("role", "system"); put("content", system) }
        // Gemini calls may carry no id: each gets one here, and the answers that follow are matched by id, else by name in order
        val pending = ArrayDeque<Pair<String, String>>()
        request["contents"]?.jsonArray.orEmpty().forEachIndexed { turn, element ->
            val content = element as? JsonObject ?: return@forEachIndexed
            val role = content["role"]?.jsonPrimitive?.contentOrNull ?: "user"
            val parts = content["parts"]?.jsonArray.orEmpty().mapNotNull { it as? JsonObject }
                .filter { it["thought"]?.jsonPrimitive?.booleanOrNull != true }
            if (role == "model") {
                val text = parts.mapNotNull { it["text"]?.jsonPrimitive?.contentOrNull }.joinToString("")
                val calls = parts.mapNotNull { it["functionCall"] as? JsonObject }
                pending.clear()
                addJsonObject {
                    put("role", "assistant")
                    if (text.isNotEmpty() || calls.isEmpty()) put("content", text) else put("content", JsonPrimitive(null as String?))
                    if (calls.isNotEmpty()) putJsonArray("tool_calls") {
                        calls.forEachIndexed { i, call ->
                            val name = call["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                            val id = call["id"]?.jsonPrimitive?.contentOrNull ?: "call_${turn}_$i"
                            pending.addLast(id to name)
                            addJsonObject {
                                put("id", id)
                                put("type", "function")
                                putJsonObject("function") {
                                    put("name", name)
                                    put("arguments", (call["args"] as? JsonObject ?: JsonObject(emptyMap())).toString())
                                }
                            }
                        }
                    }
                }
                return@forEachIndexed
            }
            for (part in parts) {
                val response = part["functionResponse"] as? JsonObject ?: continue
                val name = response["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val id = response["id"]?.jsonPrimitive?.contentOrNull
                    ?: pending.firstOrNull { it.second == name }?.first
                    ?: "call_${turn}_$name"
                pending.removeAll { it.first == id }
                val inner = response["response"] as? JsonObject
                val result = inner?.get("result")?.let { (it as? JsonPrimitive)?.contentOrNull ?: it.toString() } ?: inner?.toString().orEmpty()
                addJsonObject {
                    put("role", "tool")
                    put("tool_call_id", id)
                    put("content", result)
                }
            }
            val texts = parts.mapNotNull { it["text"]?.jsonPrimitive?.contentOrNull }
            val images = parts.mapNotNull { it["inlineData"] as? JsonObject ?: it["inline_data"] as? JsonObject }
            parts.firstOrNull { it["fileData"] != null || it["file_data"] != null }?.let { throw NotForOtherModel(tr("un fichier envoyé à Gemini")) }
            images.firstOrNull { !(it["mimeType"] ?: it["mime_type"])?.jsonPrimitive?.contentOrNull.orEmpty().startsWith("image/") }?.let {
                throw NotForOtherModel(trf("un fichier {0}", (it["mimeType"] ?: it["mime_type"])?.jsonPrimitive?.contentOrNull.orEmpty()))
            }
            if (texts.isEmpty() && images.isEmpty()) return@forEachIndexed
            addJsonObject {
                put("role", "user")
                if (images.isEmpty()) put("content", texts.joinToString("\n"))
                else putJsonArray("content") {
                    texts.forEach { t -> addJsonObject { put("type", "text"); put("text", t) } }
                    images.forEach { img ->
                        val mime = (img["mimeType"] ?: img["mime_type"])!!.jsonPrimitive.content
                        addJsonObject {
                            put("type", "image_url")
                            putJsonObject("image_url") { put("url", "data:$mime;base64," + img["data"]?.jsonPrimitive?.contentOrNull.orEmpty()) }
                        }
                    }
                }
            }
        }
    }
    return buildJsonObject {
        put("model", model)
        put("messages", messages)
        put("stream", false)
        if (tools.isNotEmpty()) {
            putJsonArray("tools") { tools.forEach { add(toOpenAiTool(it, brief)) } }
            val mode = (request["toolConfig"] as? JsonObject)?.get("functionCallingConfig")?.jsonObject?.get("mode")?.jsonPrimitive?.contentOrNull
            if (mode == "NONE") put("tool_choice", "none")
        }
        config?.get("temperature")?.jsonPrimitive?.doubleOrNull?.let { put("temperature", it) }
        config?.get("maxOutputTokens")?.jsonPrimitive?.intOrNull?.let { put("max_tokens", it) }
    }
}

/**
 * An OpenAI `chat/completions` answer as a Gemini `generateContent` one, so [parseGenerateResponse] and every caller read it as
 * usual: the text with its reasoning taken out, the tool calls as function calls (arguments parsed from their JSON string).
 */
internal fun fromOpenAiResponse(root: JsonObject): JsonObject {
    val choice = root["choices"]?.jsonArray?.firstOrNull() as? JsonObject
        ?: throw RestChatException(root["error"]?.let { errorText(it) }?.let { trf("L’autre modèle a refusé : {0}", it) } ?: tr("L’autre modèle n’a pas renvoyé de réponse."))
    val message = choice["message"] as? JsonObject ?: JsonObject(emptyMap())
    val finish = choice["finish_reason"]?.jsonPrimitive?.contentOrNull
    val raw = when (val c = message["content"]) {
        is JsonPrimitive -> c.contentOrNull.orEmpty()
        is JsonArray -> c.mapNotNull { (it as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull }.joinToString("")
        else -> ""
    }
    val text = stripThink(raw)
    val calls = message["tool_calls"]?.jsonArray.orEmpty().mapNotNull { it as? JsonObject }
    return buildJsonObject {
        putJsonArray("candidates") {
            addJsonObject {
                putJsonObject("content") {
                    put("role", "model")
                    putJsonArray("parts") {
                        if (text.isNotEmpty()) addJsonObject { put("text", text) }
                        calls.forEach { call ->
                            val fn = call["function"] as? JsonObject ?: return@forEach
                            val args = fn["arguments"]?.let { a ->
                                when (a) {
                                    is JsonObject -> a
                                    is JsonPrimitive -> a.contentOrNull?.takeIf { it.isNotBlank() }?.let {
                                        try { Json.parseToJsonElement(it) as? JsonObject } catch (_: Exception) { null }
                                    }
                                    else -> null
                                }
                            } ?: JsonObject(emptyMap())
                            addJsonObject {
                                putJsonObject("functionCall") {
                                    put("name", fn["name"]?.jsonPrimitive?.contentOrNull.orEmpty())
                                    put("args", args)
                                    call["id"]?.jsonPrimitive?.contentOrNull?.let { put("id", it) }
                                }
                            }
                        }
                    }
                }
                put("finishReason", if (finish == "content_filter") "SAFETY" else "STOP")
            }
        }
    }
}

/** The readable part of an `error` field, whatever its shape (`{"error": {"message": …}}` or `{"error": "…"}`). */
internal fun errorText(error: JsonElement): String? = when (error) {
    is JsonPrimitive -> error.contentOrNull
    is JsonObject -> (error["message"] as? JsonPrimitive)?.contentOrNull ?: error.toString()
    else -> null
}?.take(200)

/** A refused request in words: what is wrong and what to do, for [status] and its [body]; [model] is the name asked for. */
internal fun otherModelHttpError(status: Int, body: String, model: String, host: String): String {
    val detail = try { (Json.parseToJsonElement(body) as? JsonObject)?.get("error")?.let { errorText(it) } } catch (_: Exception) { null }
        ?: body.take(160)
    val b = body.lowercase()
    return when {
        status == 401 || status == 403 -> trf("Clé refusée par {0} ({1}). Vérifiez la clé de l’autre modèle.", host, status)
        status == 404 && b.contains("model") -> trf("Aucun modèle « {0} » à cette adresse. Touchez « Lister les modèles ».", model)
        status == 404 -> tr("Adresse incorrecte (404) : elle doit finir par /v1 (par exemple http://100.x.y.z:11434/v1).")
        status == 400 && b.contains("model") && (b.contains("not found") || b.contains("does not exist")) ->
            trf("Aucun modèle « {0} » à cette adresse. Touchez « Lister les modèles ».", model)
        status == 429 -> trf("L’autre modèle est à court de quota ou limité en débit (429) : {0}", detail)
        status in 500..599 -> trf("L’autre modèle est en panne ou surchargé ({0}).", status)
        else -> trf("L’autre modèle a refusé la demande ({0}) : {1}", status, detail)
    }
}

/** The model names in a `GET /models` answer (OpenAI's `data[].id`, Ollama's `models[].name` too). */
internal fun parseModelList(root: JsonObject): List<String> =
    (root["data"]?.jsonArray.orEmpty().mapNotNull { (it as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull } +
        root["models"]?.jsonArray.orEmpty().mapNotNull { (it as? JsonObject)?.let { m -> (m["name"] ?: m["model"])?.jsonPrimitive?.contentOrNull } })
        .distinct().sorted()
