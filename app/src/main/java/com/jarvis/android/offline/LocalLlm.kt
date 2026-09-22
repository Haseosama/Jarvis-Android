package com.jarvis.android.offline

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/*
 * Runs the imported Gemma model entirely on the phone (MediaPipe's LLM Inference API, `com.google.mediapipe:tasks-genai`), so the offline
 * mode can answer a real question instead of only the fixed commands of OfflineIntents.kt. The model itself is never bundled with the app
 * (see LocalModelStore.kt): this class only loads whatever .task file the user imported. I could not obtain the gated model weights myself
 * to run this end to end, so this is unverified beyond the API compiling and running against a well-formed .task file's documented shape.
 */

internal const val LOCAL_LLM_MAX_TOKENS = 512
internal const val LOCAL_LLM_TIMEOUT_MS = 60_000L

internal class LocalLlm(private val context: Context) {
    @Volatile private var engine: LlmInference? = null
    @Volatile private var loadError: String? = null

    /** Loads the model if it is not already loaded. Null on success, otherwise what to tell the user. Safe to call every turn. */
    private suspend fun ensureLoaded(modelPath: String): String? = withContext(Dispatchers.Default) {
        if (engine != null) return@withContext null
        loadError?.let { return@withContext it }
        try {
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelPath)
                .setMaxTokens(LOCAL_LLM_MAX_TOKENS)
                .build()
            engine = LlmInference.createFromOptions(context, options)
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: OutOfMemoryError) {
            val msg = "Le modèle local est trop gros pour la mémoire disponible sur ce téléphone."
            loadError = msg
            msg
        } catch (e: Exception) {
            val msg = "Le modèle local n’a pas pu démarrer : ${e.message ?: e.javaClass.simpleName}."
            loadError = msg
            msg
        }
    }

    /**
     * One answer to [prompt] (see [buildLocalPrompt]). Null when the model could not answer in time or at all; [reason] then explains why.
     */
    suspend fun reply(prompt: String, modelPath: String): LocalReply {
        ensureLoaded(modelPath)?.let { return LocalReply(null, it) }
        val eng = engine ?: return LocalReply(null, "Le modèle local n’est pas chargé.")
        return try {
            val text = withTimeoutOrNull(LOCAL_LLM_TIMEOUT_MS) {
                withContext(Dispatchers.Default) {
                    LlmInferenceSession.createFromOptions(
                        eng,
                        LlmInferenceSession.LlmInferenceSessionOptions.builder().setTopK(40).setTemperature(0.7f).build(),
                    ).use { session ->
                        session.addQueryChunk(prompt)
                        session.generateResponse()
                    }
                }
            }
            if (text == null) LocalReply(null, "Je n’ai pas pu réfléchir à temps.") else LocalReply(text, null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LocalReply(null, "Le modèle local n’a pas pu répondre : ${e.message ?: e.javaClass.simpleName}.")
        }
    }

    /** Frees the model's memory; called when the offline session ends. Safe to call even if nothing was loaded. */
    fun unload() {
        try { engine?.close() } catch (_: Exception) { }
        engine = null
        loadError = null
    }
}

internal data class LocalReply(val text: String?, val reason: String?)
