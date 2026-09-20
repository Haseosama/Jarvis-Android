package com.jarvis.android.wake

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.jarvis.android.core.AudioRoute
import com.jarvis.android.core.WakeDetector
import org.tensorflow.lite.Interpreter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The three TFLite models of openWakeWord, wrapped as the functions [WakePipeline] needs. */
internal class OpenWakeWordModels(dir: File, classifier: String = WAKE_FILE_CLASSIFIER) : AutoCloseable {
    // XNNPACK cannot prepare the spectrogram model, whose input length is set at run time.
    private val options get() = Interpreter.Options().setUseXNNPACK(false).setNumThreads(1)
    private val mel = Interpreter(load(File(dir, WAKE_FILE_MEL), patchLength = WAKE_CONTEXT + WAKE_CHUNK), options)
    private val emb = Interpreter(load(File(dir, WAKE_FILE_EMBEDDING)), options)
    private val cls = Interpreter(load(File(dir, classifier)), options)

    private fun load(file: File, patchLength: Int? = null): ByteBuffer {
        val bytes = file.readBytes().let { if (patchLength != null) withInputLength(it, patchLength) else it }
        return ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).put(bytes).also { it.rewind() }
    }

    private fun floats(values: FloatArray): ByteBuffer =
        ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder()).also { b ->
            values.forEach { b.putFloat(it) }
            b.rewind()
        }

    private fun run(interpreter: Interpreter, input: ByteBuffer): FloatArray {
        val size = interpreter.getOutputTensor(0).shape().fold(1) { a, b -> a * b }
        val out = ByteBuffer.allocateDirect(size * 4).order(ByteOrder.nativeOrder())
        interpreter.run(input, out)
        out.rewind()
        return FloatArray(size) { out.float }
    }

    fun melspectrogram(samples: FloatArray): List<FloatArray> {
        mel.resizeInput(0, intArrayOf(1, samples.size))
        mel.allocateTensors()
        val flat = run(mel, floats(samples))
        return (0 until flat.size / MEL_BINS).map { f -> FloatArray(MEL_BINS) { flat[f * MEL_BINS + it] / 10f + 2f } }
    }

    fun embedding(window: List<FloatArray>): FloatArray {
        val flat = FloatArray(MEL_WINDOW * MEL_BINS)
        window.forEachIndexed { i, row -> row.copyInto(flat, i * MEL_BINS) }
        return run(emb, floats(flat))
    }

    fun classify(embeddings: List<FloatArray>): Float {
        val flat = FloatArray(EMBEDDING_WINDOW * EMBEDDING_SIZE)
        embeddings.forEachIndexed { i, row -> row.copyInto(flat, i * EMBEDDING_SIZE) }
        return run(cls, floats(flat)).firstOrNull() ?: 0f
    }

    fun pipeline() = WakePipeline(::melspectrogram, ::embedding, ::classify)

    override fun close() {
        mel.close()
        emb.close()
        cls.close()
    }
}

/**
 * Real on-device "hey Jarvis" detection with the openWakeWord models, the same ones the desktop
 * version uses. It listens to the microphone in 80 ms steps and needs no network.
 */
internal class OpenWakeWordDetector(
    private val context: Context,
    private val modelDir: File,
    private val classifier: String,
    private val threshold: () -> Float,
    private val onProblem: (String) -> Unit = {},
    private val onDetect: () -> Unit,
) : WakeDetector {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    private var thread: Thread? = null

    override val isAvailable: Boolean get() = modelDir.isDirectory

    @Synchronized
    override fun start() {
        if (running) return
        running = true
        thread = Thread({ listen() }, "JarvisWakeWord").also { it.start() }
    }

    @Synchronized
    override fun stop() {
        running = false
        thread?.join(1_500)
        thread = null
    }

    private fun problem(message: String) {
        main.post { onProblem(message) }
    }

    @SuppressLint("MissingPermission")
    private fun listen() {
        var record: AudioRecord? = null
        var models: OpenWakeWordModels? = null
        try {
            models = OpenWakeWordModels(modelDir, classifier)
            val pipeline = models.pipeline()
            val decision = WakeDecision()
            val min = AudioRecord.getMinBufferSize(16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            record = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(16_000)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(min, WAKE_CHUNK * 8))
                .build()
            AudioRoute.input(context)?.let { record.preferredDevice = it }
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                problem("Mot d’activation : le micro est occupé par une autre application (enregistrement refusé).")
                running = false
                return
            }
            val chunk = ShortArray(WAKE_CHUNK)
            var silentChunks = 0
            var silenceReported = false
            while (running) {
                var filled = 0
                while (filled < WAKE_CHUNK && running) {
                    val read = record.read(chunk, filled, WAKE_CHUNK - filled)
                    if (read < 0) throw IllegalStateException("Lecture micro : $read")
                    filled += read
                }
                if (filled < WAKE_CHUNK) break
                // Android (or another app) can hand a background app a microphone that only returns zeros.
                if (chunk.all { it.toInt() == 0 }) silentChunks++ else silentChunks = 0
                if (silentChunks == SILENT_CHUNKS_LIMIT && !silenceReported) {
                    silenceReported = true
                    problem("Mot d’activation : le micro ne renvoie que du silence (bloqué par Android ou par une autre application).")
                }
                if (decision.accept(pipeline.process(chunk), threshold())) {
                    running = false
                    main.post { onDetect() }
                }
            }
        } catch (e: Exception) {
            Log.w("JarvisWake", "Détection arrêtée", e)
            running = false
            problem("Mot d’activation arrêté : ${e.javaClass.simpleName} ${e.message.orEmpty()}".take(200))
        } finally {
            try {
                record?.stop()
            } catch (_: Exception) {
            }
            record?.release()
            models?.close()
        }
    }
}

/** About five seconds of pure silence in a row (80 ms per chunk) means the microphone is not really delivering sound. */
private const val SILENT_CHUNKS_LIMIT = 62
