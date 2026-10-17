package com.jarvis.android.rest

import com.jarvis.android.i18n.tr
import com.jarvis.android.i18n.trf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.ConnectException
import java.net.URI
import java.net.UnknownHostException

/** Nothing answered at the address: the server is off, or the PC asleep. Also the answer the routing reads as "try Gemini". */
internal const val ERROR_OTHER_UNREACHABLE = "Rien ne répond à cette adresse : le serveur est-il lancé (et le PC allumé) ?"

/**
 * Sends Gemini-shaped requests to an OpenAI-compatible server and hands back Gemini-shaped answers (see OpenAiCompat.kt). [config]
 * is read on every call, so a change in the settings applies at once. [pickTools] puts the tool declarations in order of use for a
 * question; it only matters for a model on the user's own hardware, which gets the [ToolBudget] (a hosted one never does).
 */
internal class OtherModelTransport(
    private val client: OkHttpClient,
    private val config: suspend () -> OtherModelConfig,
    private val pickTools: (question: String, declarations: List<JsonObject>) -> List<JsonObject> = { _, d -> d },
    private val budget: ToolBudget = ToolBudget(),
    private val log: (String) -> Unit = {},
) : GenerateTransport {
    /** [model] is Gemini's name for the call; the other model's own name, from the settings, is used instead. */
    override suspend fun generate(model: String, request: JsonObject): JsonObject {
        val c = config()
        if (!c.configured) throw RestChatException(tr("L’autre modèle n’est pas configuré (Paramètres > IA et clés API > Autre modèle)."))
        val base = normalizeBaseUrl(c.baseUrl)
        val all = try { functionDeclarationsOf(request) } catch (e: NotForOtherModel) { throw RestChatException(trf("L’autre modèle ne peut pas faire ceci : {0}.", e.message.orEmpty())) }
        val local = isPrivateEndpoint(base)
        refusePlainHttp(base, local)
        val tools = if (local && all.size > budget.count) withinBudget(all, budget, pickTools(lastUserText(request), all)) else all
        val body = try {
            toOpenAiRequest(c.model, request, tools, brief = local)
        } catch (e: NotForOtherModel) {
            throw RestChatException(trf("L’autre modèle ne peut pas faire ceci : {0}.", e.message.orEmpty()))
        }
        return try {
            fromOpenAiResponse(post(base, c, body))
        } catch (e: ToolsRefused) {
            // some models served by Ollama cannot take tools at all: asked again without them, so at least they answer
            log("Autre modèle : ${c.model} refuse les outils, nouvel essai sans")
            fromOpenAiResponse(post(base, c, JsonObject(body - "tools" - "tool_choice")))
        }
    }

    private class ToolsRefused : Exception()

    private suspend fun post(base: String, c: OtherModelConfig, body: JsonObject): JsonObject = withContext(Dispatchers.IO) {
        val host = try { URI(base).host.orEmpty() } catch (_: Exception) { base }
        val http = Request.Builder().url("$base/chat/completions")
            .apply { c.key?.takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer ${it.trim()}") } }
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        try {
            client.newCall(http).await().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val lower = text.lowercase()
                    if (response.code == 400 && body["tools"] != null && lower.contains("tool") && (lower.contains("not support") || lower.contains("does not support"))) {
                        throw ToolsRefused()
                    }
                    throw RestChatException(otherModelHttpError(response.code, text, c.model, host), response.code)
                }
                try { Json.parseToJsonElement(text).jsonObject } catch (_: Exception) { throw RestChatException(tr("Réponse illisible de l’autre modèle.")) }
            }
        } catch (e: IOException) {
            throw otherNetworkFailure(e)
        }
    }

    /** What a test in the settings says: the answer time, or what is wrong in a sentence. */
    suspend fun test(): String {
        val started = System.nanoTime()
        val request = buildJsonObject {
            putJsonArray("contents") { addJsonObject { put("role", "user"); putJsonArray("parts") { addJsonObject { put("text", "Réponds seulement : OK") } } } }
        }
        return try {
            val reply = parseGenerateResponse(generate("", request))
            val seconds = (System.nanoTime() - started) / 1e9
            val said = (reply as? RestReply.Text)?.text?.take(40).orEmpty()
            trf("Répond en {0} s : « {1} ».", String.format(java.util.Locale.FRANCE, "%.2f", seconds), said)
        } catch (e: RestChatException) {
            e.message ?: tr("Échec.")
        }
    }

    /** The models the server offers (`GET /models`), or a sentence saying why it could not tell. */
    suspend fun models(): Result<List<String>> = withContext(Dispatchers.IO) {
        val c = config()
        val base = normalizeBaseUrl(c.baseUrl)
        if (base.isBlank()) return@withContext Result.failure(RestChatException(tr("Indiquez d’abord l’adresse.")))
        try { refusePlainHttp(base, isPrivateEndpoint(base)) } catch (e: RestChatException) { return@withContext Result.failure(e) }
        val http = Request.Builder().url("$base/models")
            .apply { c.key?.takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer ${it.trim()}") } }
            .get().build()
        try {
            client.newCall(http).await().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) return@withContext Result.failure(RestChatException(otherModelHttpError(response.code, text, c.model, URI(base).host.orEmpty())))
                Result.success(parseModelList(Json.parseToJsonElement(text).jsonObject))
            }
        } catch (e: IOException) {
            Result.failure(otherNetworkFailure(e))
        } catch (e: Exception) {
            Result.failure(RestChatException(tr("Réponse illisible de l’autre modèle.")))
        }
    }
}

/** Plain http only reaches the user's own machines: to anything else, the key and the conversation would cross the internet unencrypted. */
internal fun refusePlainHttp(base: String, private: Boolean) {
    if (base.startsWith("http://", ignoreCase = true) && !private) {
        throw RestChatException(tr("Pour un service sur Internet, l’adresse doit commencer par https:// (http:// n’est accepté que pour une machine chez vous)."))
    }
}

/** A network failure in words: nothing listening, a name that does not exist, too slow (504, so the routing may try Gemini). */
internal fun otherNetworkFailure(e: IOException): RestChatException = when (e) {
    is ConnectException -> RestChatException(tr(ERROR_OTHER_UNREACHABLE), 503)
    is UnknownHostException -> RestChatException(tr("Adresse introuvable : vérifiez le nom ou l’adresse IP (Tailscale doit être connecté pour une adresse 100.x)."), 503)
    is java.io.InterruptedIOException -> RestChatException(tr("L’autre modèle n’a pas répondu à temps."), 504)
    is javax.net.ssl.SSLException -> RestChatException(tr("Connexion sécurisée impossible : pour un serveur chez vous, l’adresse commence par http:// et non https://."), 503)
    else -> RestChatException(trf("Connexion impossible à l’autre modèle : {0}", e.message ?: e.javaClass.simpleName), 503)
}
