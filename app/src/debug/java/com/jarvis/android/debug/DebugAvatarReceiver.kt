package com.jarvis.android.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.jarvis.android.JarvisApp
import com.jarvis.android.avatar.Mood
import com.jarvis.android.avatar.textToVisemes
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * Debug builds only (protected by the DUMP permission, so only adb can send it): drives the avatar without a live session.
 *   adb shell am broadcast -a com.jarvis.android.DEBUG_AVATAR -p <package> --es mood listening   (--es model 0|1|2 picks the face)
 *   adb shell am broadcast -a com.jarvis.android.DEBUG_AVATAR -p <package> --es speak "Bonjour, je suis Jarvis"
 * `speak` builds a synthetic voice from the text (one vowel-like tone per sound) and feeds it, with the text, through
 * the same path as the assistant's real voice: formant analysis, transcript fusion, playback clock.
 */
class DebugAvatarReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val controller = (context.applicationContext as JarvisApp).container.avatar
        intent.getStringExtra("model")?.toIntOrNull()?.let { controller.model = it }
        intent.getStringExtra("mood")?.let { name ->
            controller.debugMood = when (name.lowercase()) {
                "idle" -> Mood.IDLE
                "listening" -> Mood.LISTENING
                "thinking" -> Mood.THINKING
                "asleep" -> Mood.ASLEEP
                else -> null
            }
        }
        intent.getStringExtra("speak")?.let { text ->
            controller.onTranscript(text)
            controller.onSpeech(synthesize(text))
        }
    }

    /** 24 kHz 16-bit PCM: a tone pair per vowel, hiss for fricatives, silence for closures and pauses. */
    private fun synthesize(text: String): ByteArray {
        val rate = 24_000
        val out = ArrayList<Short>()
        val formants = mapOf(
            "AA" to (800.0 to 1200.0), "E" to (500.0 to 2000.0), "I" to (300.0 to 2500.0),
            "O" to (500.0 to 900.0), "U" to (300.0 to 800.0),
        )
        for ((shape, weight) in textToVisemes(text)) {
            val samples = (rate * 0.075 * weight).toInt()
            for (i in 0 until samples) {
                val t = i.toDouble() / rate
                val env = sin(PI * i / samples).coerceAtLeast(0.0)
                val v = when {
                    formants.containsKey(shape) -> {
                        val (f1, f2) = formants.getValue(shape)
                        (sin(2 * PI * f1 * t) * 0.6 + sin(2 * PI * f2 * t) * 0.4) * 6000 * env
                    }
                    shape == "S" || shape == "FV" || shape == "K" || shape == "TD" || shape == "L" || shape == "R" ->
                        (Random.nextDouble() - 0.5) * 5000 * env
                    else -> 0.0 // closure or pause
                }
                out += v.toInt().toShort()
            }
        }
        val bytes = ByteArray(out.size * 2)
        out.forEachIndexed { i, s ->
            bytes[2 * i] = (s.toInt() and 0xFF).toByte()
            bytes[2 * i + 1] = (s.toInt() shr 8).toByte()
        }
        return bytes
    }
}
