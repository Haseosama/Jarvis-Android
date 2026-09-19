package com.jarvis.android.rest

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A failure of the text chat that can be shown to the user as is. */
class RestChatException(message: String, val httpCode: Int? = null) : Exception(message)

/**
 * True when a failed response says the key itself is the problem, so another key may work:
 * quota (429), refused (401/403), or a 400 that names an invalid key (a 400 about the model or
 * the request would fail identically with any key).
 */
internal fun isKeyProblem(code: Int, body: String): Boolean = when (code) {
    429, 401, 403 -> true
    400 -> body.contains("API_KEY_INVALID") || body.contains("API key not valid", ignoreCase = true)
    else -> false
}

internal const val ERROR_EMPTY_DRAFT = "Message vide : écrivez quelque chose avant d’envoyer."
internal const val ERROR_NO_KEY = "Aucune clé API valide enregistrée. Ouvrez les paramètres pour la saisir."
internal const val ERROR_NETWORK = "Connexion impossible au service Gemini. Vérifiez votre accès internet."
internal const val ERROR_EMPTY = "Gemini n’a pas renvoyé de réponse."
internal const val ERROR_BLOCKED = "La réponse a été bloquée par les filtres de sécurité de Gemini."
internal const val ERROR_MALFORMED = "Réponse illisible du service Gemini."
internal const val ERROR_INVALID_MODEL = "Nom de modèle texte invalide. Corrigez-le dans les paramètres."
internal const val ERROR_TOO_MANY_TOOLS = "Trop d’actions enchaînées : la demande a été interrompue."

internal fun httpErrorMessage(code: Int): String = when (code) {
    400 -> "Requête refusée par Gemini (400). Clé API invalide ou modèle texte incorrect : vérifiez les deux dans les paramètres."
    401, 403 -> "Clé API refusée ou accès non autorisé ($code). Vérifiez la clé et ses restrictions."
    404 -> "Modèle introuvable (404). Choisissez un autre modèle texte dans les paramètres."
    429 -> "Quota ou limite de débit atteint (429). Réessayez dans un instant."
    in 500..599 -> "Le service Gemini est momentanément indisponible ($code). Réessayez."
    else -> "Erreur du service Gemini ($code)."
}

/** One function call requested by the model. */
internal data class RestCall(val name: String, val args: JsonObject, val id: String? = null)

internal sealed interface RestReply {
    /** [content] is the model turn exactly as received, so it can be echoed back verbatim. */
    data class Text(val text: String, val content: JsonObject) : RestReply

    data class Calls(val calls: List<RestCall>, val content: JsonObject) : RestReply
}

internal interface GenerateTransport {
    suspend fun generate(model: String, request: JsonObject): JsonObject
}

private val BLOCKED_FINISH_REASONS = setOf(
    "SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII", "IMAGE_SAFETY",
)

/** Turns a `generateContent` response into text or function calls, or throws a readable error. */
internal fun parseGenerateResponse(root: JsonObject): RestReply {
    try {
        val blockReason = root["promptFeedback"]?.jsonObject?.get("blockReason")?.jsonPrimitive?.contentOrNull
        if (blockReason != null) throw RestChatException(ERROR_BLOCKED)
        val candidate = root["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: throw RestChatException(ERROR_EMPTY)
        val finish = candidate["finishReason"]?.jsonPrimitive?.contentOrNull
        val content = candidate["content"]?.jsonObject
        val parts = content?.get("parts")?.jsonArray.orEmpty().map { it.jsonObject }

        val calls = parts.mapNotNull { part ->
            part["functionCall"]?.jsonObject?.let { call ->
                val name = call["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?: throw RestChatException(ERROR_MALFORMED)
                RestCall(
                    name = name,
                    args = call["args"]?.jsonObject ?: JsonObject(emptyMap()),
                    id = call["id"]?.jsonPrimitive?.contentOrNull,
                )
            }
        }
        if (calls.isNotEmpty() && content != null) return RestReply.Calls(calls, content)

        val text = parts
            .filter { it["thought"]?.jsonPrimitive?.booleanOrNull != true }
            .mapNotNull { it["text"]?.jsonPrimitive?.contentOrNull }
            .joinToString("")
            .trim()
        if (finish in BLOCKED_FINISH_REASONS) throw RestChatException(ERROR_BLOCKED)
        if (text.isEmpty() || content == null) throw RestChatException(ERROR_EMPTY)
        return RestReply.Text(text, content)
    } catch (e: RestChatException) {
        throw e
    } catch (_: Exception) {
        throw RestChatException(ERROR_MALFORMED)
    }
}

internal fun userTurn(text: String): JsonObject = buildJsonObject {
    put("role", "user")
    putJsonArray("parts") { addJsonObject { put("text", text) } }
}

/** The model turn as received, with the role guaranteed to be present. */
internal fun modelTurn(content: JsonObject): JsonObject =
    if (content["role"] != null) content else JsonObject(content + ("role" to JsonPrimitive("model")))

internal fun functionResponseTurn(results: List<Pair<RestCall, String>>): JsonObject = buildJsonObject {
    put("role", "user")
    putJsonArray("parts") {
        results.forEach { (call, result) ->
            addJsonObject {
                putJsonObject("functionResponse") {
                    put("name", call.name)
                    call.id?.let { put("id", it) }
                    putJsonObject("response") { put("result", result) }
                }
            }
        }
    }
}

internal fun buildGenerateRequest(
    systemInstruction: String,
    contents: List<JsonObject>,
    tools: List<JsonObject>,
): JsonObject = buildJsonObject {
    putJsonObject("systemInstruction") {
        putJsonArray("parts") { addJsonObject { put("text", systemInstruction) } }
    }
    putJsonArray("contents") { contents.forEach { add(it) } }
    if (tools.isNotEmpty()) {
        putJsonArray("tools") {
            addJsonObject {
                putJsonArray("functionDeclarations") { tools.forEach { add(it) } }
            }
        }
    }
}

private val MODEL_PATTERN = Regex("(models/)?[A-Za-z0-9._-]+")

/** Sends `generateContent` requests with OkHttp; the key travels in a header, never in the URL. */
internal class OkHttpGenerateTransport(
    private val client: OkHttpClient,
    private val apiKey: () -> String?,
    /** Called with a key that was refused; returns another key to try, or null. */
    private val nextKey: (rejected: String) -> String? = { null },
) : GenerateTransport {
    override suspend fun generate(model: String, request: JsonObject): JsonObject = withContext(Dispatchers.IO) {
        var key = apiKey()?.takeIf { it.isNotBlank() } ?: throw RestChatException(ERROR_NO_KEY)
        if (!MODEL_PATTERN.matches(model)) throw RestChatException(ERROR_INVALID_MODEL)
        val path = if (model.startsWith("models/")) model else "models/$model"
        val tried = mutableSetOf<String>()
        var result: JsonObject? = null
        while (result == null) {
            try {
                result = send(path, key, request)
            } catch (e: KeyRefused) {
                tried += key
                key = nextKey(key)?.takeIf { it.isNotBlank() && it !in tried }
                    ?: throw RestChatException(httpErrorMessage(e.code), e.code)
            }
        }
        result
    }

    private class KeyRefused(val code: Int) : Exception()

    private suspend fun send(path: String, key: String, request: JsonObject): JsonObject {
        val http = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/$path:generateContent")
            .header("x-goog-api-key", key)
            .post(request.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return try {
            client.newCall(http).await().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    if (isKeyProblem(response.code, body)) throw KeyRefused(response.code)
                    throw RestChatException(httpErrorMessage(response.code), response.code)
                }
                try {
                    Json.parseToJsonElement(body).jsonObject
                } catch (_: Exception) {
                    throw RestChatException(ERROR_MALFORMED)
                }
            }
        } catch (e: IOException) {
            throw RestChatException(ERROR_NETWORK)
        }
    }
}

internal suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            if (continuation.isActive) continuation.resume(response) else response.close()
        }

        override fun onFailure(call: Call, e: IOException) {
            if (!continuation.isCancelled) continuation.resumeWithException(e)
        }
    })
    continuation.invokeOnCancellation { cancel() }
}
