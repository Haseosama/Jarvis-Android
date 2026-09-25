package com.jarvis.android.actions

import com.jarvis.android.JarvisContainer
import com.jarvis.android.offline.normalize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.jsoup.Jsoup
import java.io.IOException
import java.time.LocalDate

/** "site", "semaine", "cette année"… (accent/case-insensitive) to the one-letter code both search backends below understand. */
internal fun recencyCode(recency: String): String? = when (normalize(recency)) {
    "jour", "aujourd hui", "24h", "24 heures", "hier", "recent", "recemment" -> "d"
    "semaine", "cette semaine", "derniere semaine" -> "w"
    "mois", "ce mois", "ce mois ci", "dernier mois" -> "m"
    "annee", "cette annee", "an", "cette annee ci" -> "y"
    else -> null
}

/** Prefixes `site:` onto the query when [site] is given (a bare domain, any "https://" or trailing slash stripped). */
internal fun withSiteFilter(query: String, site: String): String {
    val s = site.trim().removePrefix("https://").removePrefix("http://").trimEnd('/')
    return if (s.isEmpty()) query else "site:$s $query"
}

/** The Google search operator for "no older than [code]", computed from [today] so it is a fixed date the way Google expects, not a relative word it might not parse the same way every time. Null passes through unfiltered. */
internal fun googleAfterOperator(code: String?, today: LocalDate): String? = when (code) {
    "d" -> "after:${today.minusDays(1)}"
    "w" -> "after:${today.minusWeeks(1)}"
    "m" -> "after:${today.minusMonths(1)}"
    "y" -> "after:${today.minusYears(1)}"
    else -> null
}

object WebSearchTool : Tool {
    override val name = "web_search"
    override val description =
        "Rechercher des informations actuelles sur le Web et retourner leurs titres, liens et extraits. Les extraits sont courts : pour un " +
            "fait précis, une date, un chiffre exact ou une citation, ouvrez ensuite un des liens avec read_webpage pour lire la page elle-même. " +
            "site restreint la recherche à un domaine (« lemonde.fr »). recency ne garde que les résultats récents ('jour', 'semaine', 'mois' ou 'année') " +
            "— seulement quand l’utilisateur le demande (« cette semaine », « récemment »…), jamais par défaut."
    override val parameters = objectSchema(required = listOf("query")) {
        string("query", "Termes à rechercher sur le Web.")
        string("site", "Facultatif : restreindre à un domaine, par exemple « lemonde.fr ».")
        string("recency", "Facultatif : 'jour', 'semaine', 'mois' ou 'année' pour ne garder que les résultats récents.")
    }

    override suspend fun run(args: JsonObject, ctx: JarvisContainer): String = withContext(Dispatchers.IO) {
        val rawQuery = normalizedUtilityQuery(args.utilityString("query"), 500)
            ?: return@withContext "Indiquez une recherche non vide, de 500 caractères maximum."
        val query = withSiteFilter(rawQuery, args.stringArg("site"))
        val recency = recencyCode(args.stringArg("recency"))
        // First choice: Gemini answering with Google Search. Any failure falls back to DuckDuckGo.
        try {
            val groundedQuery = query + (googleAfterOperator(recency, LocalDate.now())?.let { " $it" } ?: "")
            val model = ctx.configStore.snapshotRestModel()
            val answer = com.jarvis.android.rest.formatGroundedAnswer(
                ctx.restChat.transport.generate(model, com.jarvis.android.rest.buildGroundedRequest(groundedQuery))
            )
            return@withContext "Recherche Google pour « $query » :\n$answer"
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // fall through to the DuckDuckGo path below
        }
        try {
            val urlBuilder = "https://lite.duckduckgo.com/lite/".toHttpUrl().newBuilder()
                .addQueryParameter("q", query)
            recency?.let { urlBuilder.addQueryParameter("df", it) }
            val url = urlBuilder.build()
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
