package com.jarvis.android.wake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoWakeListenerTest {
    /** Scores each 80 ms step by its first sample, and remembers what it was given. */
    private class FakeScorer : WakeScorer {
        val chunks = mutableListOf<ShortArray>()
        var resets = 0
        var closed = false
        override fun score(chunk: ShortArray): Float { chunks += chunk.copyOf(); return chunk[0] / 1000f }
        override fun limit(setting: Float) = setting
        override fun reset() { resets++ }
        override fun close() { closed = true }
    }

    /** [samples] 16-bit samples, all [value], little endian. */
    private fun pcm(samples: Int, value: Int): ByteArray = ByteArray(samples * 2).also {
        for (i in 0 until samples) { it[2 * i] = (value and 0xFF).toByte(); it[2 * i + 1] = (value shr 8).toByte() }
    }

    @Test fun `frames are gathered into 80 ms steps of samples read little endian`() {
        val s = FakeScorer()
        val l = VideoWakeListener({ s }, { 0.5f })
        // 40 ms frames: two make a step
        assertFalse(l.feed(pcm(640, -2)))
        assertEquals(0, s.chunks.size)
        assertFalse(l.feed(pcm(640, -2)))
        assertEquals(1, s.chunks.size)
        assertEquals(WAKE_CHUNK, s.chunks[0].size)
        assertEquals((-2).toShort(), s.chunks[0][WAKE_CHUNK - 1])
    }

    @Test fun `the word is heard after two high scores in a row, then listening starts afresh`() {
        val s = FakeScorer()
        val l = VideoWakeListener({ s }, { 0.5f })
        assertFalse(l.feed(pcm(WAKE_CHUNK, 900)))   // one step at 0.9: not yet
        assertFalse(l.feed(pcm(WAKE_CHUNK, 100)))   // broken streak
        assertFalse(l.feed(pcm(WAKE_CHUNK, 900)))
        assertTrue(l.feed(pcm(WAKE_CHUNK, 900)))
        assertEquals(1, s.resets)
        l.reset(); l.reset()                        // nothing heard since: nothing to forget
        assertEquals(1, s.resets)
        l.close(); assertTrue(s.closed)
    }

    @Test fun `without a model it says so and hears nothing`() {
        var tries = 0
        val l = VideoWakeListener({ tries++; null }, { 0.5f })
        assertTrue(l.available)
        assertFalse(l.feed(pcm(WAKE_CHUNK, 900)))
        assertFalse(l.available)
        l.feed(pcm(WAKE_CHUNK, 900))
        assertEquals(1, tries)
    }
}
