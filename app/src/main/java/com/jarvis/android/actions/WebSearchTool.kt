package com.jarvis.android.actions

import com.jarvis.android.JarvisContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.jsoup.Jsoup
import java.io.IOException

object WebSearchTool : Tool {
    override val name = "web_search"
    override val description =
        "Rechercher des informations actuelles sur le Web et retourner leurs titres, liens et extraits. Les extraits sont courts : pour un " +
            "fait précis, une date, un chiffre exact ou une citation, ouvrez ensuite un des liens avec read_webpage pour lire la page elle-même."
    override val parameters = objectSchema(required = listOf("query")) {
        string("query", "Termes à rechercher sur le Web.")
    }

    override suspend fun run(args: JsonObject, ctx: JarvisContainer): String = withContext(Dispatchers.IO) {
        val query = normalizedUtilityQuery(args.utilityString("query"), 500)
            ?: return@withContext "Indiquez une recherche non vide, de 500 caractères maximum."
        // First choice: Gemini answering with Google Search. Any failure falls back to DuckDuckGo.
        try {
            val model = ctx.configStore.snapshotRestModel()
            val answer = com.jarvis.android.rest.formatGroundedAnswer(
                ctx.restChat.transport.generate(model, com.jarvis.android.rest.buildGroundedRequest(query))
            )
            return@withContext "Recherche Google pour « $query » :\n$answer"
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // fall through to the DuckDuckGo path below
        }
        try {
            val url = "https://lite.duckduckgo.com/lite/".toHttpUrl().newBuilder()
                .addQueryParameter("q", query).build()
            val request = Request.Builder().url(url)
                .header("User-Agent", "Mozilla/5.0 (Android) Jarvis/1.0").build()
            ctx.http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext utilityHttpError("Recherche", response.code)
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) return@withContext "Recherche indisponible : réponse vide."
                val results = parseWebSearchResults(body)
                if (results.isEmpty()) return@withContext "Aucun résultat exploitable. Le service peut être indisponible ou demander une vérification ; essayez une autre recherche."
                "Résultats pour « $query » :\n" + results.mapIndexed { index, result ->
                    "${index + 1}. ${result.title}\n${result.url}\n${result.snippet.ifBlank { "Extrait non fourni par la source." }}"
                }.joinToString("\n\n")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: IOException) {
            "Recherche indisponible : connexion impossible ou délai dépassé. Réessayez plus tard."
        } catch (_: Exception) {
            "Recherche indisponible : réponse du service inexploitable."
        }
    }
}

internal data class WebSearchResult(val title: String, val url: String, val snippet: String)

internal fun parseWebSearchResults(html: String): List<WebSearchResult> {
    val doc = Jsoup.parse(html, "https://lite.duckduckgo.com/lite/")
    val results = mutableListOf<WebSearchResult>()
    val seen = mutableSetOf<String>()
    for (link in doc.select("a.result-link, a.result__a")) {
        val title = link.text().trim().take(300)
        if (title.isEmpty()) continue
        val url = unwrapSearchUrl(link.absUrl("href")) ?: continue
        val canonical = url.toHttpUrl().newBuilder().fragment(null).build().toString()
        if (!seen.add(canonical)) continue
        val block = link.closest(".result")
        var snippet = block?.selectFirst(".result__snippet, .result-snippet")?.text().orEmpty()
        if (snippet.isBlank()) {
            var row = link.closest("tr")?.nextElementSibling()
            while (row != null) {
                if (row.selectFirst("a.result-link, a.result__a") != null) break
                snippet = row.selectFirst(".result-snippet")?.text().orEmpty()
                if (snippet.isNotBlank()) break
                row = row.nextElementSibling()
            }
        }
        results += WebSearchResult(title, canonical, snippet.trim().take(700))
        if (results.size == 5) break
    }
    return results
}

internal fun unwrapSearchUrl(input: String): String? {
    var url = normalizeBrowserUrl(input, allowBareHost = false)?.toHttpUrlOrNull() ?: return null
    repeat(3) {
        val isProvider = url.host == "duckduckgo.com" || url.host.endsWith(".duckduckgo.com")
        if (!isProvider) return url.toString()
        val target = url.queryParameter("uddg") ?: return null
        url = normalizeBrowserUrl(target, allowBareHost = false)?.toHttpUrlOrNull() ?: return null
    }
    return null
}

internal fun utilityHttpError(service: String, code: Int): String = when (code) {
    429 -> "$service indisponible : trop de requêtes. Réessayez plus tard."
    401, 403 -> "$service indisponible : accès refusé (HTTP $code)."
    in 500..599 -> "$service temporairement indisponible (HTTP $code). Réessayez plus tard."
    else -> "$service indisponible (HTTP $code)."
}
