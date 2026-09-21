package com.jarvis.android.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.jarvis.android.JarvisApp
import com.jarvis.android.wake.LearnResult
import com.jarvis.android.wake.OpenWakeWordModels
import com.jarvis.android.wake.TeachClip
import com.jarvis.android.wake.TeachStep
import com.jarvis.android.wake.WAKE_CHUNK
import com.jarvis.android.wake.WAKE_FILE_CLASSIFIER
import com.jarvis.android.wake.WakeDecision
import com.jarvis.android.wake.adjustLearnedThreshold
import com.jarvis.android.wake.learnWord
import com.jarvis.android.wake.windowEndingAt
import java.io.File
import java.util.Random
import kotlin.concurrent.thread
import kotlin.math.sqrt

/**
 * Debug builds only (protected by the DUMP permission, so only adb can send it): teaches a wake word from wav files (16 kHz, 16-bit, mono)
 * through the real openWakeWord models, then scores test files. Files are read from files/wakewav/ of the app.
 *   adb shell am broadcast -a com.jarvis.android.DEBUG_WAKE -p <package> --es pos a.wav,b.wav --es bg c.wav --es test d.wav,e.wav --es label "Debout Jarvis"
 * The result goes to logcat (tag DebugWake).
 */
class DebugWakeReceiver : BroadcastReceiver() {
    private val tag = "DebugWake"

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        thread(name = "DebugWake") {
            try {
                run(context, intent)
            } catch (e: Throwable) {
                Log.e(tag, "failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    private fun names(intent: Intent, key: String) = intent.getStringExtra(key).orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }

    private fun samples(file: File): ShortArray {
        val bytes = file.readBytes()
        val n = (bytes.size - 44) / 2
        return ShortArray(n) { ((bytes[44 + 2 * it].toInt() and 0xFF) or (bytes[45 + 2 * it].toInt() shl 8)).toShort() }
    }

    private fun run(context: Context, intent: Intent) {
        val container = (context.applicationContext as JarvisApp).container
        val dir = File(context.filesDir, "wakewav")
        val rng = Random(5)
        fun noise(count: Int, sigma: Double) = ShortArray(count) { (rng.nextGaussian() * sigma).toInt().toShort() }
        val models = OpenWakeWordModels(container.wakeModel.dir, WAKE_FILE_CLASSIFIER)
        try {
            /** The steps the teacher would record for [audio]: loudness and embedding of each 80 ms. */
            fun stepsOf(audio: ShortArray): List<TeachStep> {
                val pipeline = models.pipeline()
                val out = ArrayList<TeachStep>()
                val chunk = ShortArray(WAKE_CHUNK)
                var i = 0
                while (i + WAKE_CHUNK <= audio.size) {
                    audio.copyInto(chunk, 0, i, i + WAKE_CHUNK)
                    var sum = 0.0
                    for (s in chunk) sum += s.toDouble() * s
                    pipeline.process(chunk)
                    out += TeachStep(sqrt(sum / WAKE_CHUNK).toFloat(), pipeline.latestEmbedding)
                    i += WAKE_CHUNK
                }
                return out
            }

            val ambientSteps = 37                                  // 3 s of room before the invitation
            val clips = names(intent, "pos").map { name ->
                val word = samples(File(dir, name))
                val audio = noise(ambientSteps * WAKE_CHUNK, 40.0) + noise(11_200, 40.0) + word + noise(3 * 16_000, 40.0)
                val steps = stepsOf(audio.let { a -> a + noise(maxOf(0, (ambientSteps + 38) * WAKE_CHUNK - a.size), 40.0) })
                val start = maxOf(0, ambientSteps - 20)
                TeachClip(steps.subList(start, steps.size), ambientSteps - start)
            }
            val background = names(intent, "bg").flatMap { name ->
                stepsOf(noise(16_000, 40.0) + samples(File(dir, name)) + noise(16_000, 40.0)).drop(0)
            }
            val result = learnWord(intent.getStringExtra("label") ?: "mot", clips, background)
            when (result) {
                is LearnResult.Failed -> {
                    Log.i(tag, "FAILED: ${result.reason}")
                    return
                }
                is LearnResult.Learned -> {
                    Log.i(tag, "LEARNED reps=${result.repetitions} quality=${result.quality} posMin=${"%.2f".format(result.posMin)} negMax=${"%.2f".format(result.negMax)} threshold=${"%.2f".format(result.word.threshold)}")
                    val limit = adjustLearnedThreshold(result.word.threshold, com.jarvis.android.wake.WAKE_THRESHOLD)
                    for (name in names(intent, "test")) {
                        val steps = stepsOf(noise(2 * 16_000, 40.0) + samples(File(dir, name)) + noise(24_000, 40.0))
                        val decision = WakeDecision()
                        var peak = 0f
                        var fired = false
                        for (i in 15 until steps.size) {
                            val window = windowEndingAt(steps, i) ?: continue
                            val score = result.word.score(window)
                            peak = maxOf(peak, score)
                            if (decision.accept(score, limit)) fired = true
                        }
                        Log.i(tag, "TEST $name peak=${"%.2f".format(peak)} fired=$fired")
                    }
                }
            }
        } finally {
            models.close()
        }
    }
}
