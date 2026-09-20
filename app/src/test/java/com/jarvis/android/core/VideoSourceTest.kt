package com.jarvis.android.core

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class VideoSourceTest {
    @Test
    fun `the first frame goes out`() {
        assertTrue(FrameGate(1_500).shouldSend(0, 42))
    }

    @Test
    fun `frames closer than the interval are dropped`() {
        val gate = FrameGate(1_500)
        assertTrue(gate.shouldSend(1_000, 1))
        assertFalse(gate.shouldSend(2_000, 2))
        assertTrue(gate.shouldSend(2_600, 2))
    }

    @Test
    fun `an unchanged picture is not sent again`() {
        val gate = FrameGate(1_000)
        assertTrue(gate.shouldSend(0, 7))
        assertFalse(gate.shouldSend(5_000, 7))
        assertTrue(gate.shouldSend(6_000, 8))
    }

    @Test
    fun `reset forgets the last frame`() {
        val gate = FrameGate(1_000)
        gate.shouldSend(0, 7)
        gate.reset()
        assertTrue(gate.shouldSend(1, 7))
    }

    @Test
    fun `a video frame is a jpeg in realtime input`() {
        val jpeg = ByteArray(6) { it.toByte() }
        val video = LiveProtocol.buildRealtimeVideo(jpeg)["realtimeInput"]!!.jsonObject["video"]!!.jsonObject
        assertEquals("image/jpeg", video["mimeType"]!!.jsonPrimitive.content)
        assertEquals(jpeg.toList(), Base64.getDecoder().decode(video["data"]!!.jsonPrimitive.content).toList())
    }

    @Test
    fun `frames stay small`() {
        assertTrue(VIDEO_MAX_SIDE <= 1024)
        assertTrue(VIDEO_MIN_INTERVAL_MS >= 1_000)
    }
}
