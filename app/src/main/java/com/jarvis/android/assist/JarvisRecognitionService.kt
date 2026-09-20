package com.jarvis.android.assist

import android.content.Intent
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/**
 * Android only lists an app as a possible "digital assistant" when its voice interaction service also names a
 * recognition service. Jarvis does its own listening through Gemini, so this one recognises nothing: it answers
 * every request with an error and is never offered to other apps as a speech recogniser.
 */
class JarvisRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        try {
            listener?.error(SpeechRecognizer.ERROR_CLIENT)
        } catch (_: Exception) {
        }
    }

    override fun onCancel(listener: Callback?) {}

    override fun onStopListening(listener: Callback?) {}
}
