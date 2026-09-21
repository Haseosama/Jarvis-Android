package com.jarvis.android.wake

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.jarvis.android.core.AudioRoute
import com.jarvis.android.i18n.tr
import com.jarvis.android.i18n.trf
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import kotlin.math.sqrt

/** True while the microphone is used to teach a word: the wake-word detector must not listen meanwhile. */
internal object WakeTeaching {
    val active = MutableStateFlow(false)
}

/**
 * Reads the microphone step by step (80 ms) through the openWakeWord models and keeps the whole timeline: loudness and embedding of each step.
 * Used to record the repetitions of a new wake word, the user's ordinary speech, and a live test. Blocking: call it off the main thread.
 */
internal class WakeTeacher(private val context: Context, private val modelDir: File) : AutoCloseable {
    private var record: AudioRecord? = null
    private var models: OpenWakeWordModels? = null
    private var pipeline: WakePipeline? = null
    private val chunk = ShortArray(WAKE_CHUNK)

    /** Every step read since [open]. */
    val steps = ArrayList<TeachStep>()

    /** Opens the microphone and the models; null when it worked, otherwise what to tell the user. */
    @SuppressLint("MissingPermission")
    fun open(): String? {
        return try {
            val m = OpenWakeWordModels(modelDir, WAKE_FILE_CLASSIFIER)
            models = m
            pipeline = m.pipeline()
            val min = AudioRecord.getMinBufferSize(16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val r = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(
                    AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(16_000).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build(),
                )
                .setBufferSizeInBytes(maxOf(min, WAKE_CHUNK * 8))
                .build()
            AudioRoute.input(context)?.let { r.preferredDevice = it }
            r.startRecording()
            record = r
            if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) tr("Le micro est occupé par une autre application.") else null
        } catch (e: Exception) {
            trf("Micro ou modèles indisponibles : {0}", e.message ?: e.javaClass.simpleName).take(200)
        }
    }

    /** Reads [count] steps, calling [onStep] with each; returns false if the microphone failed. */
    fun read(count: Int, onStep: (TeachStep) -> Unit = {}): Boolean {
        val r = record ?: return false
        val p = pipeline ?: return false
        repeat(count) {
            var filled = 0
            while (filled < WAKE_CHUNK) {
                val n = r.read(chunk, filled, WAKE_CHUNK - filled)
                if (n < 0) return false
                filled += n
            }
            var sum = 0.0
            for (s in chunk) sum += s.toDouble() * s
            p.process(chunk)
            val step = TeachStep(sqrt(sum / WAKE_CHUNK).toFloat(), p.latestEmbedding)
            steps += step
            onStep(step)
        }
        return true
    }

    /** The repetition recorded from step [from] to the end, with the invitation to speak at step [prompt] of the timeline. */
    fun clip(prompt: Int): TeachClip {
        val start = maxOf(0, prompt - TEACH_LEAD_STEPS)
        return TeachClip(steps.subList(start, steps.size).toList(), prompt - start)
    }

    override fun close() {
        try { record?.stop() } catch (_: Exception) { }
        record?.release()
        record = null
        models?.close()
        models = null
    }
}
