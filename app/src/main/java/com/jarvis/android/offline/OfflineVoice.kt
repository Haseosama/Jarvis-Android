package com.jarvis.android.offline

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume

/** What listening gave. */
internal sealed interface Heard {
    data class Text(val text: String) : Heard
    /** Nothing was said, or nothing understood. */
    data object Silence : Heard
    /** Listening is impossible: [reason] says why, in words for the user. */
    data class Failed(val reason: String) : Heard
}

/**
 * The phone's own speech recognition and speech synthesis, asked to work without the network. Both must run on the main thread.
 * They only work offline if the language pack of the recognition and the voice of the synthesis are installed on the phone: the errors
 * say so when they are not.
 */
internal class OfflineVoice(private val context: Context) {
    private var tts: TextToSpeech? = null

    /** Starts the synthesis. False when the phone has none or has no voice for [locale]. */
    suspend fun init(locale: Locale): Boolean {
        val ready = CompletableDeferred<Boolean>()
        val engine = TextToSpeech(context) { status -> ready.complete(status == TextToSpeech.SUCCESS) }
        tts = engine
        if (!ready.await()) return false
        val result = engine.setLanguage(locale)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) return false
        // a voice that does not need the network, when the phone has one
        engine.voices
            ?.filter { it.locale.language == locale.language && !it.isNetworkConnectionRequired }
            ?.maxByOrNull { it.quality }
            ?.let { engine.voice = it }
        return true
    }

    /** Says [text] and returns when it is finished (or was cut). */
    suspend fun speak(text: String) {
        val engine = tts ?: return
        val done = CompletableDeferred<Unit>()
        val id = "jarvis-" + System.nanoTime()
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { done.complete(Unit) }
            @Deprecated("Deprecated in Java") override fun onError(utteranceId: String?) { done.complete(Unit) }
            override fun onStop(utteranceId: String?, interrupted: Boolean) { done.complete(Unit) }
        })
        if (engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) return
        try {
            done.await()
        } finally {
            if (!done.isCompleted) engine.stop()
        }
    }

    /** Listens to one sentence. */
    suspend fun listen(languageTag: String): Heard = suspendCancellableCoroutine { cont ->
        val recognizer = when {
            Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context) -> SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            SpeechRecognizer.isRecognitionAvailable(context) -> SpeechRecognizer.createSpeechRecognizer(context)
            else -> {
                cont.resume(Heard.Failed("Ce téléphone n’a pas de reconnaissance vocale."))
                return@suspendCancellableCoroutine
            }
        }
        fun finish(result: Heard) {
            try { recognizer.destroy() } catch (_: Exception) { }
            if (cont.isActive) cont.resume(result)
        }
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onResults(results: Bundle?) {
                val best = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull { it.isNotBlank() }
                finish(if (best != null) Heard.Text(best) else Heard.Silence)
            }
            override fun onError(error: Int) = finish(heardFromError(error))
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1_200L)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        cont.invokeOnCancellation { try { recognizer.cancel(); recognizer.destroy() } catch (_: Exception) { } }
        recognizer.startListening(intent)
    }

    fun shutdown() {
        try { tts?.stop(); tts?.shutdown() } catch (_: Exception) { }
        tts = null
    }
}

/** The recognizer's error codes, as what to do about them. */
internal fun heardFromError(error: Int): Heard = when (error) {
    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> Heard.Silence
    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> Heard.Failed("L’autorisation du microphone manque.")
    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> Heard.Failed("La reconnaissance vocale du téléphone est occupée.")
    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
        Heard.Failed("Le français hors ligne n’est pas installé : Réglages du téléphone > Applications > Services vocaux Google > Reconnaissance vocale hors ligne.")
    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER, SpeechRecognizer.ERROR_SERVER_DISCONNECTED ->
        Heard.Failed("La reconnaissance vocale a besoin du réseau : le pack de langue hors ligne n’est pas installé.")
    else -> Heard.Failed("La reconnaissance vocale a échoué (code $error).")
}
