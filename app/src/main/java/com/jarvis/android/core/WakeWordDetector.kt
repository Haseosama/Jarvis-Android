package com.jarvis.android.core

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.os.Handler
import android.os.Looper

/**
 * Local "Hey Jarvis" wake word gate.
 *
 * Mark-LIII uses `openwakeword` — a small offline ONNX model that runs on raw
 * mic frames with no network call. That model has no Android/TFLite build we
 * can ship here, so this is an Android-appropriate approximation: Android's
 * on-device [SpeechRecognizer] restarted in short bursts, checking each
 * partial result for the wake phrase. It is *not* a true always-on offline
 * detector — on most OEMs `SpeechRecognizer` briefly touches Google's speech
 * service even for "offline" recognition — so treat this as a stopgap.
 * Swapping in a real on-device model (e.g. Porcupine, or a TFLite port of
 * openWakeWord) later only means replacing this one class.
 */
class WakeWordDetector(
    private val context: Context,
    private val onDetect: () -> Unit,
) {
    private var recognizer: SpeechRecognizer? = null
    @Volatile private var running = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val wakePhrases = listOf("hey jarvis", "hey, jarvis", "ok jarvis", "okay jarvis")

    fun start() {
        if (running) return
        running = true
        mainHandler.post { listenOnce() }
    }

    fun stop() {
        running = false
        mainHandler.post {
            recognizer?.destroy()
            recognizer = null
        }
    }

    val isAvailable: Boolean
        get() = SpeechRecognizer.isRecognitionAvailable(context)

    private fun listenOnce() {
        if (!running) return
        val sr = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = sr

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }

        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}

            override fun onError(error: Int) {
                sr.destroy()
                if (running) mainHandler.postDelayed({ listenOnce() }, 250)
            }

            override fun onResults(results: Bundle?) {
                checkMatches(results)
                sr.destroy()
                if (running) mainHandler.post { listenOnce() }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                if (checkMatches(partialResults)) {
                    sr.stopListening()
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        sr.startListening(intent)
    }

    private fun checkMatches(bundle: Bundle?): Boolean {
        val matches = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return false
        val hit = matches.any { m -> wakePhrases.any { m.lowercase().contains(it) } }
        if (hit) {
            mainHandler.post { onDetect() }
        }
        return hit
    }
}
