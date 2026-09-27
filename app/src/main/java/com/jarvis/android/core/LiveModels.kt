package com.jarvis.android.core

import com.jarvis.android.rest.ModelLadder

/**
 * The Live models to open a voice session on: the one chosen in the settings, then two that Mark LV checked against real keys in
 * September 2026. Deliberately a short list: Live models are not interchangeable the way text models are (they differ in the setup
 * fields they accept, and the same key reaches transcription and translation models that are no assistants at all). The session moves
 * to the next one only when the model itself is at fault, out of quota or not there for this key; never for a dropped network or a
 * refused key, which would walk the whole list into the same wall.
 */
internal object LiveModels {
    val FALLBACKS = listOf("models/gemini-3.1-flash-live-preview", "models/gemini-2.5-flash-native-audio-preview-12-2025")

    val ladder = ModelLadder(FALLBACKS, textOnly = false)

    /** Why a session could not be had from its model, read from how the connection ended; null when it is not the model's doing. */
    fun failure(detail: String): ModelLadder.Failure? {
        val d = detail.lowercase()
        return when {
            "resource_exhausted" in d || "quota" in d || "(http 429)" in d -> ModelLadder.Failure.QUOTA
            "(http 404)" in d || "not found" in d || "is not supported" in d || "not supported for bidigeneratecontent" in d ||
                ("(1008)" in d && "model" in d) -> ModelLadder.Failure.GONE
            else -> null
        }
    }
}
