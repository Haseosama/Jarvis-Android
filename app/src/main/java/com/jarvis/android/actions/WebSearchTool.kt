package com.jarvis.android.actions

import com.jarvis.android.JarvisContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import okhttp3.Request
import org.jsoup.Jsoup

/**
 * Web search — Android port of `actions/web_search.py`'s DuckDuckGo fallback path
 * (the desktop version tries Gemini Grounded search first; that requires the
 * `google-genai` SDK's grounding tool, which isn't wired up for the Live
 * WebSocket path here, so this always uses DDG, same as the desktop fallback).
 */
object WebSearchTool : Tool {
    override val name = "web_search"
    override val description =
        "Search the web for current information — news, facts, prices, comparisons. " +
            "Use for anything that might have changed since training or that you're not certain about."
    override val parameters = objectSchema(required = listOf("query")) {
        string("query", "What to search for.")
    }

    override suspend fun run(args: JsonObject, ctx: JarvisContainer): String = withContext(Dispatchers.IO) {
        val query = args.stringArg("query")
        if (query.isBlank()) return@withContext "No search query given."
        try {
            val url = "https://lite.duckduckgo.com/lite/?q=" + java.net.URLEncoder.encode(query, "UTF-8")
            val request = Request.Builder().url(url)
                .header("User-Agent", "Mozilla/5.0 (Android) Jarvis/1.0")
                .build()
            ctx.http.newCall(request).execute().use { resp ->
                val body = resp.body?.string() ?: return@withContext "Search failed: empty response."
                val doc = Jsoup.parse(body)
                val results = doc.select("a.result-link, a[href].result-link, table tr td a")
                    .filter { it.text().isNotBlank() }
                    .take(5)
                if (results.isEmpty()) return@withContext "No results found for '$query'."
                val lines = results.mapIndexed { i, el -> "${i + 1}. ${el.text()}" }
                "Top results for '$query':\n" + lines.joinToString("\n")
            }
        } catch (e: Exception) {
            "Search failed: ${e.message}"
        }
    }
}
