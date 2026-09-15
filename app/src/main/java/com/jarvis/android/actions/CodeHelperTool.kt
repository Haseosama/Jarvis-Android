package com.jarvis.android.actions

import com.jarvis.android.JarvisContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Code review / debugging / generation — Android port of `actions/code_helper.py`.
 * Uses a plain (non-Live) Gemini `generateContent` REST call, since this is a
 * one-shot text task rather than a realtime conversation turn.
 */
object CodeHelperTool : Tool {
    override val name = "code_helper"
    override val description = "Review, debug, explain, or generate a snippet of code."
    override val parameters = objectSchema(required = listOf("request")) {
        string("request", "The code question, plus any code to look at.")
    }

    override suspend fun run(args: JsonObject, ctx: JarvisContainer): String = withContext(Dispatchers.IO) {
        val apiKey = ctx.configStore.getApiKey() ?: return@withContext "No Gemini API key configured."
        val request = args.stringArg("request")
        if (request.isBlank()) return@withContext "What would you like help with?"

        val payload = buildJsonObject {
            putJsonArray("contents") {
                addJsonObject {
                    putJsonArray("parts") { addJsonObject { put("text", request) } }
                }
            }
        }
        val body = payload.toString().toRequestBody("application/json".toMediaType())
        val httpRequest = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=$apiKey")
            .post(body)
            .build()

        try {
            ctx.http.newCall(httpRequest).execute().use { resp ->
                val text = resp.body?.string() ?: return@withContext "No response from the model."
                if (!resp.isSuccessful) return@withContext "Code helper failed: HTTP ${resp.code}."
                val root = Json.parseToJsonElement(text).jsonObject
                val out = root["candidates"]?.jsonArray?.getOrNull(0)?.jsonObject
                    ?.get("content")?.jsonObject?.get("parts")?.jsonArray?.getOrNull(0)?.jsonObject
                    ?.get("text")?.jsonPrimitive?.contentOrNull
                out ?: "No answer returned."
            }
        } catch (e: Exception) {
            "Code helper failed: ${e.message}"
        }
    }
}
