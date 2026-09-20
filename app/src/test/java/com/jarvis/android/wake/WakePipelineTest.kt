package com.jarvis.android.wake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakePipelineTest {
    private val chunk = ShortArray(WAKE_CHUNK) { (it % 100).toShort() }

    private class Recorder {
        var melCalls = 0
        var embedCalls = 0
        var classifyCalls = 0
        val melInputSizes = mutableListOf<Int>()
        var lastEmbedFrames = 0
        var lastClassifyCount = 0
    }

    private fun pipeline(r: Recorder, score: Float = 0.9f, framesPerChunk: Int = 8) = WakePipeline(
        melspec = { input ->
            r.melCalls++
            r.melInputSizes += input.size
            List(framesPerChunk) { FloatArray(MEL_BINS) }
        },
        embed = { window ->
            r.embedCalls++
            r.lastEmbedFrames = window.size
            FloatArray(EMBEDDING_SIZE)
        },
        classify = { list ->
            r.classifyCalls++
            r.lastClassifyCount = list.size
            score
        },
    )

    @Test
    fun `each step feeds the chunk with its context to the spectrogram`() {
        val r = Recorder()
        val p = pipeline(r)
        p.process(chunk)
        p.process(chunk)
        assertEquals(listOf(WAKE_CONTEXT + WAKE_CHUNK, WAKE_CONTEXT + WAKE_CHUNK), r.melInputSizes)
    }

    @Test
    fun `no score until 76 frames and 16 embeddings exist`() {
        val r = Recorder()
        val p = pipeline(r)
        val scores = (1..40).map { p.process(chunk) }
        // 8 frames per step: the first embedding needs 76 frames (step 10), the first score 16 embeddings (step 25).
        assertEquals(0, r.embedCalls.coerceAtMost(0))
        assertTrue(scores.take(24).all { it == 0f })
        assertEquals(0.9f, scores[24], 0f)
        assertEquals(MEL_WINDOW, r.lastEmbedFrames)
        assertEquals(EMBEDDING_WINDOW, r.lastClassifyCount)
    }

    @Test
    fun `the embedding count is capped`() {
        val r = Recorder()
        val p = pipeline(r)
        repeat(60) { p.process(chunk) }
        assertEquals(EMBEDDING_WINDOW, r.lastClassifyCount)
    }

    @Test
    fun `a chunk of the wrong size is refused`() {
        try {
            pipeline(Recorder()).process(ShortArray(100))
            org.junit.Assert.fail("erreur attendue")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun `reset starts the warm-up again`() {
        val r = Recorder()
        val p = pipeline(r)
        repeat(30) { p.process(chunk) }
        p.reset()
        assertEquals(0f, p.process(chunk), 0f)
    }

    @Test
    fun `a decision needs consecutive high scores`() {
        val d = WakeDecision(2)
        assertFalse(d.accept(0.9f, 0.5f))
        assertTrue(d.accept(0.8f, 0.5f))
        assertFalse(d.accept(0.9f, 0.5f))
        assertFalse(d.accept(0.1f, 0.5f))
        assertFalse(d.accept(0.9f, 0.5f))
        assertTrue(d.accept(0.6f, 0.5f))
    }

    @Test
    fun `low scores never decide`() {
        val d = WakeDecision()
        assertFalse((1..100).any { d.accept(0.3f) })
    }

    @Test
    fun `sensitivity lowers the score needed`() {
        assertTrue(wakeThresholdFor(0) > wakeThresholdFor(1))
        assertTrue(wakeThresholdFor(1) > wakeThresholdFor(2))
        assertEquals(WAKE_THRESHOLD, wakeThresholdFor(1), 0f)
        assertEquals(WAKE_THRESHOLD, wakeThresholdFor(99), 0f)
        val sensitive = WakeDecision()
        assertTrue((1..2).map { sensitive.accept(0.2f, wakeThresholdFor(2)) }.last())
        val normal = WakeDecision()
        assertFalse((1..10).any { normal.accept(0.2f, wakeThresholdFor(1)) })
    }

    @Test
    fun `model files are recognised by size and header`() {
        val good = ByteArray(200_000).also { "TFL3".toByteArray().copyInto(it, 4) }
        assertTrue(looksLikeTflite(good))
        assertFalse(looksLikeTflite(ByteArray(200_000)))
        assertFalse(looksLikeTflite(good.copyOf(50)))
        assertFalse(looksLikeTflite("<html>404</html>".toByteArray()))
    }
}
