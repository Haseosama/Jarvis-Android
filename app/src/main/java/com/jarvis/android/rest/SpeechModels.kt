package com.jarvis.android.rest

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Finds a speech (TTS) model the key can use by reading ListModels, and remembers it for the
 * process. Used only when no model is set in the settings.
 */
internal class SpeechModelResolver(
    private val client: OkHttpClient,
    private val apiKey: () -> String?,
) {
    @Volatile private var cached: String? = null

    suspend fun resolve(): String {
        cached?.let { return it }
        val key = apiKey()?.takeIf { it.isNotBlank() } ?: throw RestChatException(ERROR_NO_KEY)
        val found = withContext(Dispatchers.IO) {
            var token: String? = null
            var pages = 0
            try {
                do {
                    val url = "https://generativelanguage.googleapis.com/v1beta/models".toHttpUrl().newBuilder()
                        .addQueryParameter("pageSize", "200")
                        .apply { token?.let { addQueryParameter("pageToken", it) } }
                        .build()
                    val request = Request.Builder().url(url).header("x-goog-api-key", key).build()
                    val body = client.newCall(request).await().use { response ->
                        if (!response.isSuccessful) throw RestChatException(httpErrorMessage(response.code))
                        response.body?.string().orEmpty()
                    }
                    parseSpeechModelNames(body).firstOrNull()?.let { return@withContext it }
                    token = kotlinx.serialization.json.Json.parseToJsonElement(body)
                        .let { it as? kotlinx.serialization.json.JsonObject }
                        ?.get("nextPageToken")?.let { it as? kotlinx.serialization.json.JsonPrimitive }?.content
                        ?.takeIf { it.isNotBlank() }
                } while (token != null && ++pages < 10)
                null
            } catch (_: IOException) {
                throw RestChatException(ERROR_NETWORK)
            }
        } ?: throw RestChatException(ERROR_NO_TTS_MODEL)
        cached = found
        return found
    }
}
