package com.jarvis.android.rest

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

internal const val MAX_SOURCES = 5

internal const val GROUNDING_INSTRUCTION =
    "Réponds en français, brièvement et factuellement, à partir des résultats de recherche. " +
        "Donne les chiffres et les dates exacts quand ils existent, et dis clairement si les sources ne permettent pas de répondre."

/** A request asking Gemini to answer [query] using Google Search. Search cannot be combined with function tools. */
internal fun buildGroundedRequest(query: String): JsonObject = buildJsonObject {
    putJsonObject("systemInstruction") {
        putJsonArray("parts") { addJsonObject { put("text", GROUNDING_INSTRUCTION) } }
    }
    putJsonArray("contents") {
        addJsonObject {
            put("role", "user")
            putJsonArray("parts") { addJsonObject { put("text", query) } }
        }
    }
    putJsonArray("tools") { addJsonObject { putJsonObject("google_search") {} } }
}

internal data class GroundedSource(val title: String, val url: String)

/** The web sources Gemini says it used, without duplicates. */
internal fun groundingSources(root: JsonObject): List<GroundedSource> {
    val chunks = root["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
        ?.get("groundingMetadata")?.jsonObject?.get("groundingChunks")?.jsonArray.orEmpty()
    return chunks.mapNotNull { chunk ->
        val web = chunk.jsonObject["web"]?.jsonObject ?: return@mapNotNull null
        val url = web["uri"]?.jsonPrimitive?.contentOrNull?.takeIf { it.startsWith("http") } ?: return@mapNotNull null
        GroundedSource(web["title"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { url }, url)
    }.distinctBy { it.url }.take(MAX_SOURCES)
}

/** The grounded answer followed by its sources; throws when there is no usable text. */
internal fun formatGroundedAnswer(root: JsonObject): String {
    val text = (parseGenerateResponse(root) as? RestReply.Text)?.text ?: throw RestChatException(ERROR_EMPTY)
    val sources = groundingSources(root)
    if (sources.isEmpty()) return text
    return text + "\n\nSources :\n" + sources.mapIndexed { i, s -> "${i + 1}. ${s.title} — ${s.url}" }.joinToString("\n")
}
