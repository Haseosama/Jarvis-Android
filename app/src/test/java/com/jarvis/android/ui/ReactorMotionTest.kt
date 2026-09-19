package com.jarvis.android.ui

import com.jarvis.android.core.JarvisState
import com.jarvis.android.core.pcm16Level
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReactorMotionTest {
    private fun pcm(vararg samples: Int): ByteArray =
        ByteArray(samples.size * 2).also { out ->
            samples.forEachIndexed { i, v ->
                out[2 * i] = (v and 0xFF).toByte()
                out[2 * i + 1] = ((v shr 8) and 0xFF).toByte()
            }
        }

    @Test
    fun `silence and empty input have level zero`() {
        assertEquals(0f, pcm16Level(ByteArray(0)), 0f)
        assertEquals(0f, pcm16Level(pcm(0, 0, 0, 0)), 0f)
        assertEquals(0f, pcm16Level(ByteArray(1)), 0f)
    }

    @Test
    fun `level grows with amplitude and never exceeds one`() {
        val quiet = pcm16Level(pcm(1000, -1000, 1000, -1000))
        val loud = pcm16Level(pcm(12000, -12000, 12000, -12000))
        assertTrue(quiet > 0f)
        assertTrue(loud > quiet)
        assertEquals(1f, pcm16Level(pcm(32767, -32768, 32767, -32768)), 0f)
    }

    @Test
    fun `sign of samples does not matter`() {
        assertEquals(pcm16Level(pcm(5000, 5000)), pcm16Level(pcm(-5000, -5000)), 1e-6f)
    }

    @Test
    fun `only the speaking state follows the voice`() {
        JarvisState.values().forEach { state ->
            assertEquals(state == JarvisState.SPEAKING, reactorMotion(state).followsVoice)
        }
    }

    @Test
    fun `idle and error states do not pulse and active states do`() {
        assertEquals(0f, reactorMotion(JarvisState.ASLEEP).pulse, 0f)
        assertEquals(0f, reactorMotion(JarvisState.ERROR).pulse, 0f)
        listOf(JarvisState.CONNECTING, JarvisState.LISTENING, JarvisState.THINKING, JarvisState.SPEAKING)
            .forEach { assertTrue(reactorMotion(it).pulse > 0f) }
    }

    @Test
    fun `thinking breathes faster than listening`() {
        assertTrue(reactorMotion(JarvisState.THINKING).periodMs < reactorMotion(JarvisState.LISTENING).periodMs)
        assertFalse(reactorMotion(JarvisState.LISTENING).followsVoice)
    }
}
