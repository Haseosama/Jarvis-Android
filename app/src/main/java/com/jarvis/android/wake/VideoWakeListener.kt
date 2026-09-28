package com.jarvis.android.wake

import java.io.File

/** Scores 80 ms steps of sound for the wake word (see [OpenWakeScorer]); [limit] is the score needed at a sensitivity's [setting]. */
internal interface WakeScorer : AutoCloseable {
    fun score(chunk: ShortArray): Float
    fun limit(setting: Float): Float
    fun reset()
}

/** The openWakeWord models on the phone, as a [WakeScorer]: the built-in word, or the one the user taught. */
internal class OpenWakeScorer(dir: File, classifier: String) : WakeScorer {
    private val models = OpenWakeWordModels(dir, classifier)
    private val pipeline = models.pipeline()

    override fun score(chunk: ShortArray): Float = pipeline.process(chunk)
    override fun limit(setting: Float): Float = models.learned?.let { adjustLearnedThreshold(it.threshold, setting) } ?: setting
    override fun reset() = pipeline.reset()
    override fun close() = models.close()
}

/**
 * "Jarvis" heard over a video's sound. While the video's sound is on, the session's microphone does not reach the assistant (it
 * would answer the film); its frames (16 kHz, 16-bit little endian) come here instead, to the same chain as the wake word, and the
 * word opens the floor (see VideoPanel.openFloor). [open] loads the models the first time they are needed (null: none installed).
 */
internal class VideoWakeListener(private val open: () -> WakeScorer?, private val threshold: () -> Float) : AutoCloseable {
    private var scorer: WakeScorer? = null
    private var tried = false
    private val chunk = ShortArray(WAKE_CHUNK)
    private var filled = 0
    private var dirty = false
    private val decision = WakeDecision()

    /** False once it is known that no wake word model is installed. */
    val available: Boolean get() = !tried || scorer != null

    /** Takes a frame of sound; true when the wake word has just been heard (listening then starts afresh). */
    fun feed(pcm: ByteArray): Boolean {
        if (!tried) {
            tried = true
            scorer = try { open() } catch (_: Exception) { null }
        }
        val s = scorer ?: return false
        dirty = true
        var i = 0
        while (i + 1 < pcm.size) {
            chunk[filled++] = ((pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)).toShort()
            i += 2
            if (filled == WAKE_CHUNK) {
                filled = 0
                if (decision.accept(s.score(chunk), s.limit(threshold()))) {
                    reset()
                    return true
                }
            }
        }
        return false
    }

    /** Forgets what was heard (the sound went off, or the floor opened): the word has to be said again, whole. */
    fun reset() {
        if (!dirty) return
        dirty = false
        filled = 0
        scorer?.reset()
    }

    override fun close() {
        scorer?.close()
        scorer = null
    }
}
