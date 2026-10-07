package com.jarvis.android.offline

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/*
 * Runs the installed local model entirely on the phone, so the offline mode can answer a real question instead of only the fixed commands
 * of OfflineIntents.kt. Two engines, chosen by the file's format (see LocalModelStore.kt): a MediaPipe .task bundle goes to MediaPipe's LLM
 * Inference API (`com.google.mediapipe:tasks-genai`), a .litertlm file (Gemma 4, the uncensored conversions) to its successor LiteRT-LM
 * (`com.google.ai.edge.litertlm:litertlm-android`). The model itself is never bundled with the app: this class only loads whatever file the
 * user downloaded or imported. Unverified on a real phone beyond the APIs compiling: I could not run either engine with real weights.
 */

internal const val LOCAL_LLM_MAX_TOKENS = 512
internal const val LOCAL_LLM_TIMEOUT_MS = 60_000L

internal class LocalLlm(private val context: Context) {
    @Volatile private var mediapipe: LlmInference? = null
    @Volatile private var litert: Engine? = null
    @Volatile private var loadedPath: String? = null
    @Volatile private var loadError: String? = null

    /** Loads the model if it is not already loaded. Null on success, otherwise what to tell the user. Safe to call every turn. */
    private suspend fun ensureLoaded(modelPath: String): String? = withContext(Dispatchers.Default) {
        if (loadedPath != modelPath) unload()
        if (mediapipe != null || litert != null) return@withContext null
        loadError?.let { return@withContext it }
        try {
            if (modelPath.endsWith(".litertlm")) {
                val engine = Engine(EngineConfig(modelPath = modelPath, backend = Backend.CPU(), cacheDir = context.cacheDir.path))
                engine.initialize()
                litert = engine
            } else {
                val options = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(modelPath)
                    .setMaxTokens(LOCAL_LLM_MAX_TOKENS)
                    .build()
                mediapipe = LlmInference.createFromOptions(context, options)
            }
            loadedPath = modelPath
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: OutOfMemoryError) {
            val msg = "Le modèle local est trop gros pour la mémoire disponible sur ce téléphone."
            loadError = msg
            loadedPath = modelPath
            msg
        } catch (e: Exception) {
            val msg = "Le modèle local n’a pas pu démarrer : ${e.message ?: e.javaClass.simpleName}."
            loadError = msg
            loadedPath = modelPath
            msg
        }
    }

    /**
     * One answer to [prompt] (see [buildLocalPrompt]). Null when the model could not answer in time or at all; [reason] then explains why.
     */
    suspend fun reply(prompt: String, modelPath: String): LocalReply {
        ensureLoaded(modelPath)?.let { return LocalReply(null, it) }
        val mp = mediapipe
        val lm = litert
        if (mp == null && lm == null) return LocalReply(null, "Le modèle local n’est pas chargé.")
        return try {
            val text = withTimeoutOrNull(LOCAL_LLM_TIMEOUT_MS) {
                withContext(Dispatchers.Default) {
                    if (lm != null) {
                        lm.createConversation(
                            ConversationConfig(samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.7)),
                        ).use { conversation -> conversation.sendMessage(prompt).toString() }
                    } else {
                        LlmInferenceSession.createFromOptions(
                            mp!!,
                            LlmInferenceSession.LlmInferenceSessionOptions.builder().setTopK(40).setTemperature(0.7f).build(),
                        ).use { session ->
                            session.addQueryChunk(prompt)
                            session.generateResponse()
                        }
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
        try { mediapipe?.close() } catch (_: Exception) { }
        try { litert?.close() } catch (_: Exception) { }
        mediapipe = null
        litert = null
        loadedPath = null
        loadError = null
    }
}

internal data class LocalReply(val text: String?, val reason: String?)
