package com.markliv.android

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Base64
import javax.net.ssl.HttpsURLConnection

data class ChatMessage(val role: String, val text: String = "", val functionCall: JSONObject? = null, val functionResponse: JSONObject? = null)

sealed class GeminiResponse {
    data class Text(val text: String) : GeminiResponse()
    data class FunctionCall(val name: String, val args: JSONObject) : GeminiResponse()
}

class GeminiException(message: String) : Exception(message)

open class GeminiClient {

    open suspend fun generate(apiKey: String, model: String, messages: List<ChatMessage>, tools: org.json.JSONArray? = null): GeminiResponse {
        return withContext(Dispatchers.IO) {
            requireValidModel(model)
            if (apiKey.isBlank()) {
                throw GeminiException(ERROR_MISSING_KEY)
            }
            if (apiKey.any { it !in '!'..'~' }) {
                throw GeminiException(ERROR_INVALID_KEY)
            }
            val request = buildRequestJson(messages, tools)
            var connection: HttpsURLConnection? = null
            try {
                connection = openConnection(model)
                writeRequest(connection, apiKey, request)
                val status = connection.responseCode
                if (status != HttpURLConnection.HTTP_OK) {
                    try {
                        connection.errorStream?.close()
                    } finally {
                        throw GeminiException(httpErrorMessage(status))
                    }
                }
                parseResponseJson(readBody(connection.inputStream))
            } catch (error: IOException) {
                throw GeminiException(ERROR_NETWORK)
            } finally {
                connection?.disconnect()
            }
        }
    }

    open suspend fun generateWithAudio(apiKey: String, model: String, samples: ShortArray, sampleRate: Int): GeminiResponse {
        return withContext(Dispatchers.IO) {
            requireValidModel(model)
            if (apiKey.isBlank()) {
                throw GeminiException(ERROR_MISSING_KEY)
            }
            if (apiKey.any { it !in '!'..'~' }) {
                throw GeminiException(ERROR_INVALID_KEY)
            }
            if (samples.isEmpty()) {
                throw GeminiException(ERROR_EMPTY_AUDIO)
            }
            val request = AudioTranscriber.buildAudioRequestJson(samples, sampleRate)
            var connection: HttpsURLConnection? = null
            try {
                connection = openConnection(model)
                writeRequest(connection, apiKey, request)
                val status = connection.responseCode
                if (status != HttpURLConnection.HTTP_OK) {
                    try {
                        connection.errorStream?.close()
                    } finally {
                        throw GeminiException(httpErrorMessage(status))
                    }
                }
                parseResponseJson(readBody(connection.inputStream))
            } catch (error: IOException) {
                throw GeminiException(ERROR_NETWORK)
            } finally {
                connection?.disconnect()
            }
        }
    }

    open suspend fun generateSpeech(apiKey: String, model: String, text: String): ByteArray {
        return withContext(Dispatchers.IO) {
            requireValidModel(model)
            if (apiKey.isBlank()) throw GeminiException(ERROR_MISSING_KEY)
            if (apiKey.any { it !in '!'..'~' }) throw GeminiException(ERROR_INVALID_KEY)
            val request = buildSpeechRequestJson(text)
            var connection: HttpsURLConnection? = null
            try {
                connection = openConnection(model)
                writeRequest(connection, apiKey, request)
                val status = connection.responseCode
                if (status != HttpURLConnection.HTTP_OK) {
                    try {
                        connection.errorStream?.close()
                    } finally {
                        throw GeminiException(httpErrorMessage(status))
                    }
                }
                parseSpeechResponseJson(readBody(connection.inputStream, MAX_SPEECH_RESPONSE_BYTES))
            } catch (error: IOException) {
                throw GeminiException(ERROR_NETWORK)
            } finally {
                connection?.disconnect()
            }
        }
    }

    suspend fun listModels(apiKey: String): List<String> {
        return withContext(Dispatchers.IO) {
            if (apiKey.isBlank()) {
                throw GeminiException(ERROR_MISSING_KEY)
            }
            if (apiKey.any { it !in '!'..'~' }) {
                throw GeminiException(ERROR_INVALID_KEY)
            }
            val names = mutableSetOf<String>()
            var pageToken: String? = null
            for (page in 1..MAX_PAGES) {
                var connection: HttpsURLConnection? = null
                try {
                    connection = openModelsConnection(pageToken)
                    writeModelsRequest(connection, apiKey)
                    val status = connection.responseCode
                    if (status != HttpURLConnection.HTTP_OK) {
                        try {
                            connection.errorStream?.close()
                        } finally {
                            throw GeminiException(httpErrorMessage(status))
                        }
                    }
                    val pageResult = parseModelsJson(readBody(connection.inputStream))
                    names.addAll(pageResult.first)
                    pageToken = pageResult.second
                    if (pageToken == null) return@withContext names.sorted()
                } catch (error: IOException) {
                    throw GeminiException(ERROR_NETWORK)
                } finally {
                    connection?.disconnect()
                }
            }
            throw GeminiException(ERROR_TOO_MANY_PAGES)
        }
    }

    internal fun requireValidModel(model: String) {
        if (model.isBlank() || !MODEL_PATTERN.matches(model)) {
            throw GeminiException(ERROR_INVALID_MODEL)
        }
    }

    internal fun buildRequestJson(messages: List<ChatMessage>, tools: org.json.JSONArray? = null): JSONObject {
        if (messages.isEmpty()) {
            throw GeminiException(ERROR_EMPTY_CONVERSATION)
        }
        val contents = JSONArray()
        for (message in messages) {
            val content = JSONObject()
            content.put("role", mapRole(message.role))
            val parts = JSONArray()

            if (message.text.isNotBlank()) {
                parts.put(JSONObject().put("text", message.text))
            }

            message.functionCall?.let {
                parts.put(JSONObject().put("functionCall", it))
            }

            message.functionResponse?.let {
                parts.put(JSONObject().put("functionResponse", it))
            }

            if (parts.length() > 0) {
                content.put("parts", parts)
                contents.put(content)
            }
        }

        val systemParts = JSONArray().put(JSONObject().put("text", SYSTEM_INSTRUCTION))
        val request = JSONObject()
        request.put("system_instruction", JSONObject().put("parts", systemParts))
        request.put("contents", contents)

        tools?.let {
            request.put("tools", JSONArray().put(JSONObject().put("function_declarations", it)))
        }

        return request
    }

    internal fun parseResponseJson(body: String): GeminiResponse {
        if (body.isBlank()) throw GeminiException(ERROR_EMPTY)
        try {
            val root = JSONObject(body)
            val promptFeedback = root.optJSONObject("promptFeedback")
            val blockReason = promptFeedback?.opt("blockReason") as? String ?: ""
            if (blockReason.isNotBlank() && blockReason != "BLOCK_REASON_UNSPECIFIED") {
                throw GeminiException(ERROR_BLOCKED)
            }
            val candidates = root.optJSONArray("candidates")
            if (candidates == null || candidates.length() == 0) {
                throw GeminiException(ERROR_EMPTY)
            }
            val candidate = candidates.optJSONObject(0) ?: throw GeminiException(ERROR_EMPTY)
            if (candidate.optString("finishReason") in BLOCKED_FINISH_REASONS) {
                throw GeminiException(ERROR_BLOCKED)
            }
            val parts = candidate.optJSONObject("content")?.optJSONArray("parts")
            if (parts == null || parts.length() == 0) {
                throw GeminiException(ERROR_EMPTY)
            }

            // Check for functionCall first
            for (index in 0 until parts.length()) {
                val part = parts.optJSONObject(index) ?: continue
                if (part.has("functionCall")) {
                    val fc = part.getJSONObject("functionCall")
                    return GeminiResponse.FunctionCall(
                        fc.getString("name"),
                        fc.optJSONObject("args") ?: JSONObject()
                    )
                }
            }

            val builder = StringBuilder()
            for (index in 0 until parts.length()) {
                val part = parts.optJSONObject(index) ?: continue
                if (part.optBoolean("thought", false)) continue
                val text = part.opt("text") as? String ?: continue
                builder.append(text)
            }
            val response = builder.toString().trim()
            if (response.isEmpty()) {
                throw GeminiException(ERROR_EMPTY)
            }
            return GeminiResponse.Text(response)
        } catch (error: GeminiException) {
            throw error
        } catch (error: JSONException) {
            throw GeminiException(ERROR_MALFORMED)
        }
    }

    internal fun buildSpeechRequestJson(text: String): JSONObject {
        if (text.isBlank()) throw GeminiException(ERROR_EMPTY_SPEECH_TEXT)
        return JSONObject()
            .put("contents", JSONArray().put(
                JSONObject().put("role", "user")
                    .put("parts", JSONArray().put(JSONObject().put("text", text)))
            ))
            .put("generationConfig", JSONObject()
                .put("responseModalities", JSONArray().put("AUDIO"))
                .put("speechConfig", JSONObject().put("voiceConfig", JSONObject().put(
                    "prebuiltVoiceConfig", JSONObject().put("voiceName", "Kore")
                )))
            )
    }

    internal fun parseSpeechResponseJson(body: String): ByteArray {
        if (body.length > MAX_SPEECH_RESPONSE_BYTES || body.toByteArray(Charsets.UTF_8).size > MAX_SPEECH_RESPONSE_BYTES) {
            throw GeminiException(ERROR_TOO_LARGE)
        }
        if (body.isBlank()) throw GeminiException(ERROR_EMPTY)
        try {
            val root = JSONObject(body)
            if (root.has("error")) throw GeminiException(ERROR_MALFORMED)
            if (root.has("promptFeedback")) {
                val feedback = root.getJSONObject("promptFeedback")
                if (feedback.has("blockReason")) {
                    val reason = feedback.get("blockReason") as? String ?: throw GeminiException(ERROR_MALFORMED)
                    if (reason.isNotBlank() && reason != "BLOCK_REASON_UNSPECIFIED") {
                        throw GeminiException(ERROR_BLOCKED)
                    }
                }
                requireUnblockedRatings(feedback)
            }
            val candidates = root.optJSONArray("candidates") ?: throw GeminiException(ERROR_EMPTY)
            if (candidates.length() == 0) throw GeminiException(ERROR_EMPTY)
            val candidate = candidates.getJSONObject(0)
            val finishReason = candidate.opt("finishReason") as? String ?: throw GeminiException(ERROR_MALFORMED)
            if (finishReason in BLOCKED_FINISH_REASONS) throw GeminiException(ERROR_BLOCKED)
            requireUnblockedRatings(candidate)
            if (finishReason != "STOP") throw GeminiException(ERROR_MALFORMED)
            val parts = candidate.getJSONObject("content").getJSONArray("parts")
            val pcm = ByteArrayOutputStream()
            for (index in 0 until parts.length()) {
                val part = parts.getJSONObject(index)
                if (part.has("thought")) {
                    val thought = part.get("thought") as? Boolean ?: throw GeminiException(ERROR_MALFORMED)
                    if (thought) continue
                }
                if (!part.has("inlineData")) {
                    if (part.opt("text") is String) continue
                    throw GeminiException(ERROR_INVALID_AUDIO)
                }
                val inlineData = part.getJSONObject("inlineData")
                val mime = inlineData.get("mimeType") as? String ?: throw GeminiException(ERROR_INVALID_AUDIO)
                requireSpeechMime(mime)
                val data = inlineData.get("data") as? String ?: throw GeminiException(ERROR_INVALID_AUDIO)
                if (data.isEmpty()) throw GeminiException(ERROR_INVALID_AUDIO)
                if (data.length > MAX_PCM_BASE64_CHARS) throw GeminiException(ERROR_TOO_LARGE)
                val decoded = try {
                    Base64.getDecoder().decode(data)
                } catch (_: IllegalArgumentException) {
                    throw GeminiException(ERROR_MALFORMED)
                }
                if (Base64.getEncoder().encodeToString(decoded) != data) throw GeminiException(ERROR_MALFORMED)
                if (decoded.isEmpty() || decoded.size % 2 != 0) throw GeminiException(ERROR_INVALID_AUDIO)
                if (decoded.size > AudioTranscriber.MAX_PCM_BYTES - pcm.size()) throw GeminiException(ERROR_TOO_LARGE)
                pcm.write(decoded)
            }
            if (pcm.size() == 0) throw GeminiException(ERROR_INVALID_AUDIO)
            return AudioTranscriber.pcmToWav(pcm.toByteArray(), 24000)
        } catch (error: JSONException) {
            throw GeminiException(ERROR_MALFORMED)
        }
    }

    private fun requireSpeechMime(mime: String) {
        val tokens = mime.split(';').map { it.trim() }
        if (!tokens[0].equals("audio/L16", true) && !tokens[0].equals("audio/pcm", true)) {
            throw GeminiException(ERROR_INVALID_AUDIO)
        }
        val parameters = mutableMapOf<String, String>()
        for (token in tokens.drop(1)) {
            val pair = token.split('=')
            if (pair.size != 2) throw GeminiException(ERROR_INVALID_AUDIO)
            val name = pair[0].trim().lowercase(java.util.Locale.ROOT)
            val value = pair[1].trim().lowercase(java.util.Locale.ROOT)
            if (parameters.put(name, value) != null) throw GeminiException(ERROR_INVALID_AUDIO)
            val valid = when (name) {
                "rate" -> value == "24000"
                "channels" -> value == "1"
                "bits", "bit-depth" -> value == "16"
                "codec" -> value == "pcm"
                else -> false
            }
            if (!valid) throw GeminiException(ERROR_INVALID_AUDIO)
        }
        if (parameters["rate"] != "24000") throw GeminiException(ERROR_INVALID_AUDIO)
    }

    private fun requireUnblockedRatings(value: JSONObject) {
        if (!value.has("safetyRatings")) return
        val ratings = value.getJSONArray("safetyRatings")
        for (index in 0 until ratings.length()) {
            val rating = ratings.getJSONObject(index)
            if (!rating.has("blocked")) continue
            val blocked = rating.get("blocked") as? Boolean ?: throw GeminiException(ERROR_MALFORMED)
            if (blocked) throw GeminiException(ERROR_BLOCKED)
        }
    }

    internal fun httpErrorMessage(statusCode: Int): String = when (statusCode) {
        400 -> "Requête refusée par le service Gemini (requête invalide)."
        401 -> "Clé API refusée par le service Gemini. Vérifiez votre clé."
        403 -> "Accès refusé par le service Gemini. Votre clé n'a pas les droits nécessaires."
        404 -> "Modèle Gemini introuvable. Vérifiez le nom du modèle."
        429 -> "Limite de requêtes atteinte. Patientez avant de réessayer."
        in 500..599 -> "Le service Gemini est momentanément indisponible. Réessayez plus tard."
        else -> "Erreur inattendue du service Gemini (code $statusCode)."
    }

    private fun openConnection(model: String): HttpsURLConnection {
        val connection = URL(BASE_URL + model + ":generateContent").openConnection() as HttpsURLConnection
        connection.instanceFollowRedirects = false
        return connection
    }

    private fun writeRequest(connection: HttpsURLConnection, apiKey: String, request: JSONObject) {
        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        connection.setRequestProperty(API_KEY_HEADER, apiKey)
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.doOutput = true
        connection.outputStream.use { output ->
            output.write(request.toString().toByteArray(Charsets.UTF_8))
            output.flush()
        }
    }

    internal fun readBody(stream: InputStream, maxBytes: Int = MAX_RESPONSE_BYTES): String {
        try {
            val buffer = ByteArrayOutputStream()
            stream.use { input ->
                val chunk = ByteArray(READ_CHUNK_BYTES)
                while (true) {
                    val read = input.read(chunk)
                    if (read == -1) break
                    if (read > maxBytes - buffer.size()) {
                        throw GeminiException(ERROR_TOO_LARGE)
                    }
                    buffer.write(chunk, 0, read)
                }
            }
            return String(buffer.toByteArray(), Charsets.UTF_8)
        } catch (error: GeminiException) {
            throw GeminiException(error.message ?: ERROR_NETWORK)
        } catch (error: IOException) {
            throw GeminiException(ERROR_NETWORK)
        }
    }

    internal fun parseModelsJson(body: String): Pair<List<String>, String?> {
        if (body.isBlank()) throw GeminiException(ERROR_EMPTY)
        try {
            val root = JSONObject(body)
            if (root.has("error")) throw GeminiException(ERROR_MALFORMED)
            val models = if (root.has("models")) {
                root.optJSONArray("models") ?: throw GeminiException(ERROR_MALFORMED)
            } else {
                JSONArray()
            }
            val token = root.opt("nextPageToken")
            if (token != null && token !== JSONObject.NULL && token !is String) {
                throw GeminiException(ERROR_MALFORMED)
            }
            val pageToken = (token as? String)?.takeIf { it.isNotEmpty() }
            val names = mutableSetOf<String>()
            for (index in 0 until models.length()) {
                val model = models.optJSONObject(index) ?: continue
                val name = model.opt("name") as? String ?: continue
                if (!name.startsWith(MODEL_NAME_PREFIX)) continue
                val identifier = name.removePrefix(MODEL_NAME_PREFIX)
                if (identifier.isBlank() || !MODEL_PATTERN.matches(identifier)) continue
                val methods = model.optJSONArray("supportedGenerationMethods") ?: continue
                var supported = false
                for (methodIndex in 0 until methods.length()) {
                    if (methods.optString(methodIndex) == SUPPORTED_METHOD) {
                        supported = true
                        break
                    }
                }
                if (supported) names.add(identifier)
            }
            return Pair(names.sorted(), pageToken)
        } catch (error: JSONException) {
            throw GeminiException(ERROR_MALFORMED)
        }
    }

    internal fun buildModelsUrl(pageToken: String?): String {
        val url = StringBuilder(MODELS_URL).append("?pageSize=").append(PAGE_SIZE)
        if (!pageToken.isNullOrEmpty()) {
            url.append("&pageToken=").append(URLEncoder.encode(pageToken, "UTF-8"))
        }
        return url.toString()
    }

    private fun openModelsConnection(pageToken: String?): HttpsURLConnection {
        val connection = URL(buildModelsUrl(pageToken)).openConnection() as HttpsURLConnection
        connection.instanceFollowRedirects = false
        return connection
    }

    private fun writeModelsRequest(connection: HttpsURLConnection, apiKey: String) {
        connection.requestMethod = "GET"
        connection.setRequestProperty(API_KEY_HEADER, apiKey)
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
    }

    private fun mapRole(role: String): String = when (role) {
        "user" -> "user"
        "model", "assistant" -> "model"
        "function" -> "function"
        else -> throw GeminiException(ERROR_INVALID_ROLE)
    }

    companion object {
        private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models/"
        private const val MODELS_URL = "https://generativelanguage.googleapis.com/v1beta/models"
        private const val MODEL_NAME_PREFIX = "models/"
        private const val SUPPORTED_METHOD = "generateContent"
        private const val PAGE_SIZE = 1000
        private const val MAX_PAGES = 10
        private const val API_KEY_HEADER = "x-goog-api-key"
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val MAX_RESPONSE_BYTES = 2_000_000
        private const val MAX_SPEECH_RESPONSE_BYTES = 12_000_000
        private const val MAX_PCM_BASE64_CHARS = ((AudioTranscriber.MAX_PCM_BYTES + 2) / 3) * 4
        private const val READ_CHUNK_BYTES = 8_192
        private val MODEL_PATTERN = Regex("[a-zA-Z0-9._-]+")
        private val BLOCKED_FINISH_REASONS = setOf(
            "SAFETY", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII", "RECITATION",
            "IMAGE_SAFETY", "IMAGE_PROHIBITED_CONTENT", "IMAGE_RECITATION"
        )

        internal val SYSTEM_INSTRUCTION = "Tu es Mark LIV, une IA sophistiquée basée sur le projet Mark LIV de FatihMakes. " +
            "Tu réponds exclusivement en français. Tu as désormais la capacité d'interagir avec le téléphone Android de l'utilisateur " +
            "via des outils (functions). Tu peux régler le volume, lancer des applications, ouvrir des URLs et vérifier l'état du système. " +
            "Sois proactif, utile et adopte une personnalité élégante et futuriste."

        internal const val ERROR_MISSING_KEY = "Clé API manquante. Renseignez votre clé Gemini avant d'envoyer un message."
        internal const val ERROR_INVALID_KEY = "Format de clé API Gemini invalide."
        internal const val ERROR_INVALID_ROLE = "Rôle de message invalide : utilisez user, assistant ou model."
        internal const val ERROR_INVALID_MODEL = "Nom de modèle Gemini invalide."
        internal const val ERROR_EMPTY_CONVERSATION = "Conversation vide : envoyez au moins un message."
        internal const val ERROR_EMPTY_DRAFT = "Message vide : saisissez un texte avant l'envoi."
        internal const val ERROR_NETWORK = "Connexion impossible au service Gemini. Vérifiez votre accès internet."
        internal const val ERROR_MALFORMED = "Réponse illisible du service Gemini."
        internal const val ERROR_EMPTY = "Mark LIV n'a pas renvoyé de réponse."
        internal const val ERROR_BLOCKED = "La réponse a été bloquée par les filtres de sécurité du service Gemini."
        internal const val ERROR_TOO_LARGE = "Réponse du service Gemini trop volumineuse."
        internal const val ERROR_TOO_MANY_PAGES = "Liste des modèles trop longue pour être récupérée."
        internal const val ERROR_EMPTY_SPEECH_TEXT = "Texte vide : rien à lire à voix haute."
        internal const val ERROR_INVALID_AUDIO = "Réponse audio invalide du service Gemini."
        internal const val ERROR_EMPTY_AUDIO = "Enregistrement vide : rien à transcrire."
        internal const val ERROR_AUDIO_TOO_LARGE = "Enregistrement trop long pour être envoyé à Gemini."
    }
}
