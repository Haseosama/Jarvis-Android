package com.jarvis.android.core

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.jarvis.android.JarvisApp
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal object SpokenAlert {
    fun reminderSpokenText(text: String): String {
        val clean = text.trim()
        return if (clean.lowercase().startsWith("rappel")) clean else "Rappel : $clean"
    }

    fun timerSpokenText(label: String): String {
        val clean = label.trim()
        return if (clean.lowercase().startsWith("minuteur")) clean else "Minuteur terminé : $clean"
    }

    fun sessionAnnouncement(text: String): String =
        "Annonce immédiate à l’utilisateur, à voix haute : « $text »."

    fun announce(context: Context, text: String) {
        try {
            val engine = (context.applicationContext as? JarvisApp)?.container?.engine
            if (engine != null && engine.sessionReady.value) {
                val sent = try {
                    runBlocking { withTimeout(ANNOUNCE_TIMEOUT_MS) { engine.announce(sessionAnnouncement(text)) } }
                } catch (_: Exception) {
                    false
                }
                if (sent) return
            }
            speakTts(context, text)
        } catch (_: Exception) {
        }
    }

    private fun speakTts(context: Context, text: String) {
        val initLatch = CountDownLatch(1)
        var tts: TextToSpeech? = null
        try {
            tts = TextToSpeech(context.applicationContext) { initLatch.countDown() }
            if (!initLatch.await(INIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) return
            val engine = tts ?: return
            val french = engine.setLanguage(Locale.FRANCE)
            if (french == TextToSpeech.LANG_MISSING_DATA || french == TextToSpeech.LANG_NOT_SUPPORTED) {
                if (engine.setLanguage(Locale.FRENCH) == TextToSpeech.LANG_MISSING_DATA) return
            }
            val done = CountDownLatch(1)
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    done.countDown()
                }
                override fun onError(utteranceId: String?) {
                    done.countDown()
                }
            })
            val params = Bundle().apply {
                putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, UTTERANCE_ID)
            }
            if (engine.speak(text, TextToSpeech.QUEUE_FLUSH, params, UTTERANCE_ID) != TextToSpeech.SUCCESS) return
            done.await(SPEAK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
        } finally {
            try {
                tts?.shutdown()
            } catch (_: Exception) {
            }
        }
    }

    private const val UTTERANCE_ID = "jarvis-alert"
    private const val INIT_TIMEOUT_MS = 3_000L
    private const val SPEAK_TIMEOUT_MS = 15_000L
    private const val ANNOUNCE_TIMEOUT_MS = 8_000L
}
