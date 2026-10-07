package com.jarvis.android.perplexity

import com.jarvis.android.JarvisContainer
import com.jarvis.android.tool.Tool
import com.jarvis.android.tool.objectSchema
import com.jarvis.android.tool.stringArg
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * A web-grounded, sourced answer from the Perplexity Agent API, for questions where a list of links from web_search is not
 * enough. Needs the user's Perplexity key (Settings > IA et clés API > Recherche Perplexity), stored encrypted like the
 * other keys; it is never logged or shown.
 */
object PerplexityTool : Tool {
    override val name = "perplexity_search"
    override val description =
        "Répondre à une question avec Perplexity, qui cherche sur le Web, lit les pages et rédige une réponse sourcée. À préférer à " +
            "web_search pour une question qui demande de croiser plusieurs sources : comparer, expliquer une actualité, faire le point sur " +
            "un sujet, une recherche détaillée. profondeur : 'rapide', 'normale' (par défaut) ou 'approfondie' (plus long et plus cher, " +
            "seulement si l’utilisateur demande une recherche poussée). suite='oui' poursuit la réponse Perplexity précédente " +
            "(« et pour Lyon ? »). Demande une clé Perplexity ; sans clé, utilisez web_search."
    override val parameters = objectSchema(required = listOf("question")) {
        string("question", "La question complète, avec son contexte (lieu, date, objet), telle que Perplexity doit la chercher.")
        string("profondeur", "Facultatif : 'rapide', 'normale' ou 'approfondie'.")
        string("suite", "Facultatif : 'oui' pour poursuivre la réponse Perplexity précédente.")
        string("sites", "Facultatif : ne chercher que sur ces domaines, séparés par des virgules (« service-public.fr, legifrance.gouv.fr »).")
    }

    /** The last answer's id, so a follow-up question continues it (previous_response_id). Lives as long as the app process. */
    @Volatile private var lastResponseId: String? = null

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun run(args: JsonObject, ctx: JarvisContainer): String = withContext(Dispatchers.IO) {
        val question = args.stringArg("question").trim().take(4000)
        if (question.isEmpty()) return@withContext "Indiquez la question à poser à Perplexity."
        val key = ctx.configStore.getPerplexityKey()?.trim()?.takeIf { it.isNotEmpty() }
            ?: return@withContext "Aucune clé Perplexity : l’utilisateur peut en créer une sur console.perplexity.ai et la coller dans " +
                "Paramètres > IA et clés API > Recherche Perplexity. En attendant, utilisez web_search."
        val continuing = args.stringArg("suite").trim().lowercase() in setOf("oui", "true", "yes", "1")
        val body = buildPerplexityRequest(
            question = question,
            preset = perplexityPreset(args.stringArg("profondeur")),
            previousResponseId = if (continuing) lastResponseId else null,
            domains = parseDomains(args.stringArg("sites")),
        ).toString()
        // A grounded answer searches and reads pages over several steps: much longer than the shared client's 20 s.
        val client = ctx.http.newBuilder().readTimeout(120, TimeUnit.SECONDS).callTimeout(150, TimeUnit.SECONDS).build()
        try {
            when (val first = post(client, key, body)) {
                is Outcome.Answer -> answered(question, first.answer)
                is Outcome.Failed -> {
                    // Rate limited: honour Retry-After once when it is short enough to wait inside a conversation.
                    val wait = first.retryAfter
                    if (first.code != 429 || wait == null || wait > 10) return@withContext perplexityHttpError(first.code, wait)
                    delay(wait * 1000)
                    when (val second = post(client, key, body)) {
                        is Outcome.Answer -> answered(question, second.answer)
                        is Outcome.Failed -> perplexityHttpError(second.code, second.retryAfter)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: IOException) {
            "Perplexity injoignable : connexion impossible ou délai dépassé. Réessayez plus tard, ou utilisez web_search."
        } catch (e: PerplexityException) {
            "Perplexity n’a pas donné de réponse : ${e.message}."
        } catch (_: Exception) {
            "Perplexity a renvoyé une réponse inexploitable. Utilisez web_search."
        }
    }

    private fun answered(question: String, answer: PerplexityAnswer): String {
        lastResponseId = answer.id
        return formatPerplexityAnswer(question, answer)
    }

    private sealed interface Outcome {
        data class Answer(val answer: PerplexityAnswer) : Outcome
        data class Failed(val code: Int, val retryAfter: Long?) : Outcome
    }

    private fun post(client: OkHttpClient, key: String, body: String): Outcome {
        val request = Request.Builder().url(PERPLEXITY_AGENT_URL)
            .header("Authorization", "Bearer $key")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return Outcome.Failed(response.code, retryAfterSeconds(response.header("Retry-After")))
            val root = json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject
            return Outcome.Answer(parsePerplexityResponse(root))
        }
    }
}
