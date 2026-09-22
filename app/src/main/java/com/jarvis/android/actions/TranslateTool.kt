package com.jarvis.android.actions

import com.jarvis.android.JarvisContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Translates a piece of text into a named language, without switching the language of the rest of
 * the conversation (see LANGUAGE in the system prompt — that is decided by what the user speaks,
 * never by a tool call). A one-shot Gemini `generateContent` REST call, same shape as `code_helper`:
 * translation quality needs no bespoke logic Jarvis doesn't already have through the model itself.
 */
internal fun buildTranslatePrompt(text: String, targetLanguage: String, sourceLanguage: String): String {
    val from = sourceLanguage.trim().takeIf { it.isNotEmpty() }?.let { " depuis le $it" } ?: ""
    return "Traduis le texte suivant en $targetLanguage$from. Réponds uniquement avec la traduction, sans guillemets, " +
        "sans commentaire et sans répéter le texte d'origine. Si une phrase ou un mot n'a pas d'équivalent exact, " +
        "choisis la traduction la plus naturelle plutôt qu'une traduction mot à mot.\n\nTexte :\n$text"
}

internal fun parseTranslateResponse(body: String): String? {
    val root = Json.parseToJsonElement(body).jsonObject
    return root["candidates"]?.jsonArray?.getOrNull(0)?.jsonObject
        ?.get("content")?.jsonObject?.get("parts")?.jsonArray?.getOrNull(0)?.jsonObject
        ?.get("text")?.jsonPrimitive?.contentOrNull?.trim()?.trim('"', '«', '»')?.trim()
}

object TranslateTool : Tool {
    override val name = "translate"
    override val description =
        "Traduire un texte dans une langue donnée. Ne change PAS la langue de la conversation elle-même (voir LANGUAGE) " +
            "— seul ce texte précis est traduit, et vous continuez de parler la langue de l'utilisateur pour le reste. " +
            "target_language est le nom de la langue visée (« anglais », « espagnol », « japonais »…), tel que l'utilisateur l'a dit."
    override val parameters = objectSchema(required = listOf("text", "target_language")) {
        string("text", "Le texte à traduire.")
        string("target_language", "La langue visée, par exemple « anglais » ou « espagnol ».")
        string("source_language", "Facultatif : la langue d'origine, si elle n'est pas évidente.")
    }

    override suspend fun run(args: JsonObject, ctx: JarvisContainer): String = withContext(Dispatchers.IO) {
        val apiKey = ctx.configStore.getApiKey() ?: return@withContext "Aucune clé Gemini configurée."
        val text = args.stringArg("text").trim()
        if (text.isEmpty()) return@withContext "Indiquez le texte à traduire."
        if (text.length > 8_000) return@withContext "Texte trop long (8 000 caractères maximum)."
        val target = args.stringArg("target_language").trim()
        if (target.isEmpty()) return@withContext "Indiquez la langue visée."

        val payload = buildJsonObject {
            putJsonArray("contents") {
                addJsonObject {
                    putJsonArray("parts") { addJsonObject { put("text", buildTranslatePrompt(text, target, args.stringArg("source_language"))) } }
                }
            }
        }
        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.6-flash:generateContent?key=$apiKey")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        try {
            ctx.http.newCall(request).execute().use { resp ->
                val text2 = resp.body?.string() ?: return@withContext "Pas de réponse du modèle."
                if (!resp.isSuccessful) return@withContext "Traduction impossible : HTTP ${resp.code}."
                parseTranslateResponse(text2) ?: "Aucune traduction renvoyée."
            }
        } catch (e: Exception) {
            "Traduction impossible : ${e.message}"
        }
    }
}
