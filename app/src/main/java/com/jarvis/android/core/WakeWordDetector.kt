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
/** Anything that can wake the assistant by voice while it sleeps. */
interface WakeDetector {
    val isAvailable: Boolean
    fun start()
    fun stop()
}

class WakeWordDetector(
    private val context: Context,
    private val onDetect: () -> Unit,
) : WakeDetector {
    private var recognizer: SpeechRecognizer? = null
    @Volatile private var running = false
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun start() {
        if (running) return
        running = true
        mainHandler.post { listenOnce() }
    }

    override fun stop() {
        if (!running && recognizer == null) return
        running = false
        mainHandler.post {
            try {
                recognizer?.destroy()
            } catch (_: Exception) {
            } finally {
                recognizer = null
            }
        }
    }

    override val isAvailable: Boolean
        get() = SpeechRecognizer.isRecognitionAvailable(context)

    private fun listenOnce() {
        if (!running) return
        val sr = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = sr

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-FR")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "fr-FR")
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
                try {
                    sr.destroy()
                } catch (_: Exception) {
                }
                if (running) mainHandler.postDelayed({ listenOnce() }, 250)
            }

            override fun onResults(results: Bundle?) {
                checkMatches(results)
                try {
                    sr.destroy()
                } catch (_: Exception) {
                }
                if (running) mainHandler.post { listenOnce() }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                if (checkMatches(partialResults)) {
                    try {
                        sr.stopListening()
                    } catch (_: Exception) {
                    }
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        sr.startListening(intent)
    }

    internal fun isWakePhrase(text: String): Boolean = Companion.isWakePhrase(text)

    private fun checkMatches(bundle: Bundle?): Boolean {
        val matches = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return false
        val hit = matches.any { isWakePhrase(it) }
        if (hit && running) {
            running = false
            try {
                recognizer?.destroy()
            } catch (_: Exception) {
            } finally {
                recognizer = null
            }
            mainHandler.post { onDetect() }
        }
        return hit
    }

    companion object {
        private val wakePhrases = listOf("hey jarvis", "hey, jarvis", "hé jarvis", "ok jarvis", "okay jarvis")

        internal fun isWakePhrase(text: String): Boolean {
            val lower = text.lowercase()
            return wakePhrases.any { lower.contains(it) }
        }
    }
}
