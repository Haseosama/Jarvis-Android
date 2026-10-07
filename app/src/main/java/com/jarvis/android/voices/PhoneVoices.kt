package com.jarvis.android.voices

import android.content.Context
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Every voice of every speech engine installed on the phone (Google's, Samsung's, and any one the user adds, such as SherpaTTS for
 * Piper voices or RHVoice), the ones that work without the network first, in [language] first.
 */
internal suspend fun listPhoneVoices(context: Context, language: String): List<PhoneVoice> = withContext(Dispatchers.Main) {
    val probe = open(context, null) ?: return@withContext emptyList()
    val engines = try {
        probe.engines.map { it.name to it.label }
    } finally {
        probe.shutdown()
    }
    val found = mutableListOf<PhoneVoice>()
    for ((pkg, label) in engines) {
        val tts = open(context, pkg) ?: continue
        try {
            tts.voices.orEmpty().forEach { v ->
                found += PhoneVoice(pkg, label, v.name, v.locale.toLanguageTag(), !v.isNetworkConnectionRequired, v.quality)
            }
        } catch (_: Exception) {
        } finally {
            tts.shutdown()
        }
    }
    sortPhoneVoices(found, language)
}

private suspend fun open(context: Context, engine: String?): TextToSpeech? {
    val ready = CompletableDeferred<Boolean>()
    val tts = if (engine == null) TextToSpeech(context) { ready.complete(it == TextToSpeech.SUCCESS) }
    else TextToSpeech(context, { ready.complete(it == TextToSpeech.SUCCESS) }, engine)
    val ok = withTimeoutOrNull(8_000) { ready.await() } == true
    if (!ok) tts.shutdown()
    return if (ok) tts else null
}
