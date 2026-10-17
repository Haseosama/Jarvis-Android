package com.jarvis.android.rest

import kotlinx.serialization.json.JsonObject

/** True when the other model can take [request]: no Gemini-only tool, no audio answer, no file but pictures. */
internal fun portableRequest(request: JsonObject): Boolean = try {
    functionDeclarationsOf(request)
    toOpenAiRequest("", request, emptyList(), brief = false)
    true
} catch (_: NotForOtherModel) {
    false
}

/**
 * Whether a Gemini failure is one the other model can stand in for: out of quota (every key and every model of the ladder), not
 * answering, every key refused, no key at all, or no way to reach Google. A blocked answer or a bad request would not be better
 * elsewhere, and are not hidden.
 */
internal fun geminiCannotAnswer(e: RestChatException): Boolean =
    e.httpCode in setOf(401, 403, 404, 429, 500, 502, 503, 504) || e.message in setOf(ERROR_NO_KEY, ERROR_NETWORK, ERROR_TIMEOUT)

/**
 * Chooses, call by call, between Gemini and the other model (after Mark LVI, where the assistant's own requests can go to another
 * model when the text quota is gone, without the voice changing):
 *  - off, or a request only Gemini can do (a speech or image model, Google Search, audio, a PDF): Gemini, as before;
 *  - "always" (or no Gemini key at all): the other model, and Gemini if it fails and a Gemini key exists;
 *  - "fallback": Gemini, and the other model when Gemini cannot answer (see [geminiCannotAnswer]); if both fail, Gemini's error is the
 *    one shown, with the other model's reason after it.
 */
internal class RoutingTransport(
    private val gemini: GenerateTransport,
    private val other: GenerateTransport,
    private val settings: suspend () -> OtherModelConfig,
    private val hasGeminiKey: () -> Boolean,
    private val log: (String) -> Unit = {},
) : GenerateTransport {
    private enum class Route { GEMINI, OTHER_FIRST, GEMINI_FIRST }

    private suspend fun route(model: String, request: JsonObject): Route {
        val c = settings()
        if (!c.configured || c.use == OTHER_MODEL_OFF) return Route.GEMINI
        if (!ModelLadder.shared.replaceable(model) || !portableRequest(request)) return Route.GEMINI
        return if (c.use == OTHER_MODEL_ALWAYS || !hasGeminiKey()) Route.OTHER_FIRST else Route.GEMINI_FIRST
    }

    override suspend fun generate(model: String, request: JsonObject): JsonObject = when (route(model, request)) {
        Route.GEMINI -> gemini.generate(model, request)
        Route.OTHER_FIRST -> otherThenGemini(model, request)
        Route.GEMINI_FIRST -> try {
            gemini.generate(model, request)
        } catch (e: RestChatException) {
            if (!geminiCannotAnswer(e)) throw e
            standIn(model, request, e)
        }
    }

    override suspend fun stream(model: String, request: JsonObject, onEvent: suspend (JsonObject) -> Unit) {
        when (route(model, request)) {
            Route.GEMINI -> gemini.stream(model, request, onEvent)
            Route.OTHER_FIRST -> onEvent(otherThenGemini(model, request))
            Route.GEMINI_FIRST -> {
                var delivered = false
                try {
                    gemini.stream(model, request) { delivered = true; onEvent(it) }
                } catch (e: RestChatException) {
                    // half an answer from one and the rest from the other is worse than an error
                    if (delivered || !geminiCannotAnswer(e)) throw e
                    onEvent(standIn(model, request, e))
                }
            }
        }
    }

    private suspend fun otherThenGemini(model: String, request: JsonObject): JsonObject = try {
        other.generate(model, request)
    } catch (e: RestChatException) {
        if (!hasGeminiKey()) throw e
        log("Autre modèle indisponible (${e.message}) : Gemini répond")
        gemini.generate(model, request)
    }

    private suspend fun standIn(model: String, request: JsonObject, geminiError: RestChatException): JsonObject {
        log("Gemini indisponible (${geminiError.httpCode ?: geminiError.message}) : l’autre modèle répond")
        return try {
            other.generate(model, request)
        } catch (o: RestChatException) {
            // left as is, so the chat still hands these two to the phone's own model
            if (geminiError.message == ERROR_NETWORK || geminiError.message == ERROR_NO_KEY) throw geminiError
            throw RestChatException("${geminiError.message} (${o.message})", geminiError.httpCode)
        }
    }
}
