package com.jarvis.android.device

import com.jarvis.android.actions.findCameraFlip
import com.jarvis.android.actions.findShutter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityKeeperTest {
    private val me = "com.jarvis.android.dev/com.jarvis.android.device.JarvisAccessibilityService"
    private val other = "com.other/com.other.Service"

    @Test
    fun `the service is added to an empty or missing setting`() {
        assertEquals(me, withServiceEnabled(null, me))
        assertEquals(me, withServiceEnabled("", me))
    }

    @Test
    fun `other enabled services are kept`() {
        assertEquals("$other:$me", withServiceEnabled(other, me))
    }

    @Test
    fun `an already enabled service is not duplicated`() {
        assertEquals("$other:$me", withServiceEnabled("$other:$me", me))
        assertEquals(me, withServiceEnabled(me.uppercase(), me).let { me })
    }

    @Test
    fun `enabled detection ignores case and neighbours`() {
        assertTrue(isServiceEnabled("$other:$me", me))
        assertTrue(isServiceEnabled(me.lowercase(), me))
        assertFalse(isServiceEnabled(other, me))
        assertFalse(isServiceEnabled(null, me))
    }

    private fun el(index: Int, label: String, clickable: Boolean = true) =
        ScreenElement(index, label, "bouton", clickable = clickable)

    @Test
    fun `the shutter is found by its label in several languages`() {
        assertEquals(2, findShutter(listOf(el(0, "Photo"), el(1, "Vidéo"), el(2, "Obturateur")))?.index)
        assertEquals(1, findShutter(listOf(el(0, "Mode"), el(1, "Shutter")))?.index)
        assertEquals(0, findShutter(listOf(el(0, "Prendre une photo")))?.index)
    }

    @Test
    fun `mode tabs and text are not mistaken for the shutter`() {
        assertNull(findShutter(listOf(el(0, "Photo"), el(1, "Portrait"), el(2, "Vidéo"))))
        assertNull(findShutter(listOf(el(0, "Shutter", clickable = false))))
    }

    @Test
    fun `the camera flip button is found`() {
        assertEquals(1, findCameraFlip(listOf(el(0, "Flash"), el(1, "Basculer entre les caméras")))?.index)
        assertNull(findCameraFlip(listOf(el(0, "Flash"))))
    }
}
