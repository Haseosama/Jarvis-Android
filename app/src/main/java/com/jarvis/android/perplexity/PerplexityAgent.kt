package com.jarvis.android.perplexity

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/*
 * The Perplexity Agent API (POST https://api.perplexity.ai/v1/agent), without Android so it is unit-tested on the JVM.
 * Request and response shapes follow https://docs.perplexity.ai/api-reference/agent-post: the answer is in output[] items of
 * type "message" (content parts of type "output_text"), the sources in items of type "search_results". The raw HTTP JSON has
 * no top-level output_text (that is an SDK convenience), so it is assembled here the same way.
 */

internal const val PERPLEXITY_AGENT_URL = "https://api.perplexity.ai/v1/agent"
internal const val PERPLEXITY_MAX_SOURCES = 5

/** How deep to search: a preset bundles model, tools and limits. "low" (web search + reading one page) is the default. */
internal fun perplexityPreset(depth: String): String = when (depth.trim().lowercase()) {
    "rapide", "fast", "vite" -> "fast"
    "approfondie", "approfondi", "detaillee", "détaillée", "medium", "profonde" -> "medium"
    else -> "low"
}

/** The request body for [question], continuing [previousResponseId] when given, searching only [domains] when any. */
internal fun buildPerplexityRequest(
    question: String,
    preset: String,
    previousResponseId: String? = null,
    domains: List<String> = emptyList(),
): JsonObject = buildJsonObject {
    put("preset", preset)
    put("input", question)
    put("language_preference", "fr")
    previousResponseId?.let { put("previous_response_id", it) }
    if (domains.isNotEmpty()) {
        putJsonArray("tools") {
            addJsonObject {
                put("type", "web_search")
                putJsonObject("filters") { putJsonArray("search_domain_filter") { domains.take(20).forEach { add(JsonPrimitive(it)) } } }
            }
            addJsonObject { put("type", "fetch_url") }
        }
    }
}

/** "lemonde.fr, https://www.service-public.fr/" to bare domains. */
internal fun parseDomains(input: String): List<String> =
    input.split(',', ' ', ';').map { it.trim().removePrefix("https://").removePrefix("http://").trimEnd('/') }
        .filter { it.contains('.') }.distinct()

internal data class PerplexitySource(val id: Long?, val title: String, val url: String)

internal data class PerplexityAnswer(val id: String?, val text: String, val sources: List<PerplexitySource>)

/** Thrown when the response carries no usable answer; the message is shown to the model. */
internal class PerplexityException(message: String) : Exception(message)

private val CITATION_MARKER = Regex("\\s?\\[(?:web|fetch|finance|url):(\\d+)\\]")

/** The answer of a non-streamed /v1/agent response, with the sources it cites first. */
internal fun parsePerplexityResponse(root: JsonObject): PerplexityAnswer {
    val status = root["status"]?.jsonPrimitive?.contentOrNull
    val output = root["output"] as? JsonArray ?: JsonArray(emptyList())
    val items = output.mapNotNull { it as? JsonObject }
    val raw = items.filter { it.str("type") == "message" }
        .flatMap { (it["content"] as? JsonArray).orEmpty() }
        .mapNotNull { it as? JsonObject }
        .filter { it.str("type") == "output_text" }
        .joinToString("") { it.str("text").orEmpty() }
        .trim()
    if (raw.isEmpty()) {
        val error = (root["error"] as? JsonObject)?.str("message")
        throw PerplexityException(error ?: "réponse vide (statut ${status ?: "inconnu"})")
    }
    val searched = items.filter { it.str("type") == "search_results" }
        .flatMap { (it["results"] as? JsonArray).orEmpty() }
        .mapNotNull { it as? JsonObject }
        .mapNotNull { r ->
            val url = r.str("url")?.takeIf { it.startsWith("http") } ?: return@mapNotNull null
            PerplexitySource(r["id"]?.jsonPrimitive?.longOrNull, r.str("title").orEmpty().ifBlank { url }, url)
        }
    val annotated = items.filter { it.str("type") == "message" }
        .flatMap { (it["content"] as? JsonArray).orEmpty() }
        .flatMap { ((it as? JsonObject)?.get("annotations") as? JsonArray).orEmpty() }
        .mapNotNull { it as? JsonObject }
        .filter { it.str("type") == "url_citation" }
        .mapNotNull { a -> a.str("url")?.takeIf { it.startsWith("http") }?.let { PerplexitySource(null, a.str("title").orEmpty().ifBlank { it }, it) } }
    // Cited sources in the order the answer cites them, then the other results.
    val cited = CITATION_MARKER.findAll(raw).mapNotNull { it.groupValues[1].toLongOrNull() }.distinct().toList()
    val ordered = annotated + cited.mapNotNull { id -> searched.firstOrNull { it.id == id } } + searched.filter { it.id !in cited }
    val text = CITATION_MARKER.replace(raw, "").trim()
    return PerplexityAnswer(root.str("id"), text, ordered.distinctBy { it.url }.take(PERPLEXITY_MAX_SOURCES))
}

/** The answer then its sources, the way the model reads tool results. */
internal fun formatPerplexityAnswer(question: String, answer: PerplexityAnswer): String {
    val head = "Réponse de Perplexity pour « $question » :\n${answer.text}"
    if (answer.sources.isEmpty()) return head
    return head + "\n\nSources :\n" + answer.sources.mapIndexed { i, s -> "${i + 1}. ${s.title} — ${s.url}" }.joinToString("\n")
}

/** Seconds to wait from a Retry-After header (seconds form); null when absent or a date. */
internal fun retryAfterSeconds(header: String?): Long? = header?.trim()?.toLongOrNull()?.takeIf { it >= 0 }

/** A short French message for a failed HTTP status. */
internal fun perplexityHttpError(code: Int, retryAfter: Long?): String = when (code) {
    401 -> "Perplexity refuse la clé (HTTP 401). Vérifiez-la dans Paramètres > IA et clés API > Recherche Perplexity, ou créez-en une nouvelle sur console.perplexity.ai."
    402, 403 -> "Perplexity refuse l’accès (HTTP $code) : crédit épuisé ou clé sans droit sur l’Agent API. Vérifiez sur console.perplexity.ai."
    429 -> "Perplexity est saturé ou la limite de requêtes est atteinte (HTTP 429)." +
        (retryAfter?.let { " Réessayez dans $it s." } ?: " Réessayez un peu plus tard.")
    in 500..599 -> "Perplexity est temporairement indisponible (HTTP $code). Réessayez plus tard."
    else -> "Perplexity indisponible (HTTP $code)."
}

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
