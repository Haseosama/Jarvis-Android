package com.jarvis.android.wake

/** Samples per step: 80 ms at 16 kHz. */
internal const val WAKE_CHUNK = 1280
internal const val WAKE_CONTEXT = 480
internal const val MEL_WINDOW = 76
internal const val MEL_BINS = 32
internal const val EMBEDDING_SIZE = 96
internal const val EMBEDDING_WINDOW = 16
internal const val WAKE_THRESHOLD = 0.5f
internal const val WAKE_CONSECUTIVE = 2

/** 0 = prudent, 1 = normal, 2 = sensitive. Higher sensitivity means a lower score is enough. */
internal const val DEFAULT_WAKE_SENSITIVITY = 1

/**
 * Score needed to wake the assistant. Normal is the openWakeWord default. Measured with synthetic
 * voices: an English voice scores about 1.0, a French voice reading English words about 0.2, so
 * French speakers may need "sensitive"; "hey travis" reached 0.38 to 0.75 depending on the voice
 * speed, hence the prudent level for noisy places.
 */
internal fun wakeThresholdFor(sensitivity: Int): Float = when (sensitivity) {
    0 -> 0.75f
    2 -> 0.15f
    else -> WAKE_THRESHOLD
}

/**
 * The openWakeWord chain: audio, then a mel spectrogram, then an embedding of the last 76 frames,
 * then a classifier over the last 16 embeddings. The three models are passed in as functions so the
 * bookkeeping can be tested without them.
 *
 * It needs about two seconds of audio after starting before a score can appear.
 */
internal class WakePipeline(
    private val melspec: (FloatArray) -> List<FloatArray>,
    private val embed: (List<FloatArray>) -> FloatArray,
    private val classify: (List<FloatArray>) -> Float,
) {
    private var context = FloatArray(WAKE_CONTEXT)
    private val frames = ArrayDeque<FloatArray>()
    private val embeddings = ArrayDeque<FloatArray>()

    /** The embedding computed by the last [process] call, or null while the models are still warming up. */
    var latestEmbedding: FloatArray? = null
        private set

    /** Feeds exactly [WAKE_CHUNK] samples and returns the current wake score (0 while warming up). */
    fun process(chunk: ShortArray): Float {
        require(chunk.size == WAKE_CHUNK) { "Un pas fait $WAKE_CHUNK échantillons." }
        val input = FloatArray(WAKE_CONTEXT + WAKE_CHUNK)
        context.copyInto(input)
        for (i in chunk.indices) input[WAKE_CONTEXT + i] = chunk[i].toFloat()
        context = input.copyOfRange(input.size - WAKE_CONTEXT, input.size)

        latestEmbedding = null
        frames.addAll(melspec(input))
        while (frames.size > MEL_WINDOW + 16) frames.removeFirst()
        if (frames.size >= MEL_WINDOW) {
            val fresh = embed(frames.toList().takeLast(MEL_WINDOW))
            latestEmbedding = fresh
            embeddings.addLast(fresh)
            while (embeddings.size > EMBEDDING_WINDOW) embeddings.removeFirst()
        }
        return if (embeddings.size >= EMBEDDING_WINDOW) classify(embeddings.toList()) else 0f
    }

    fun reset() {
        context = FloatArray(WAKE_CONTEXT)
        frames.clear()
        embeddings.clear()
        latestEmbedding = null
    }
}

/** Turns a stream of scores into wake decisions: [needed] scores in a row at or above [threshold]. */
internal class WakeDecision(private val needed: Int = WAKE_CONSECUTIVE) {
    private var streak = 0

    fun accept(score: Float, threshold: Float = WAKE_THRESHOLD): Boolean {
        streak = if (score >= threshold) streak + 1 else 0
        if (streak >= needed) {
            streak = 0
            return true
        }
        return false
    }
}
