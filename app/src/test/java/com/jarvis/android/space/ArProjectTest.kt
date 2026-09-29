package com.jarvis.android.space

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArProjectTest {
    // A phone held upright facing north: device X is east, device Y is up, device Z points back to the user (south).
    // Android's matrix takes device to world (East, North, Up), row by row.
    private val facingNorth = floatArrayOf(
        1f, 0f, 0f,
        0f, 0f, -1f,
        0f, 1f, 0f,
    )

    // Lying flat, screen up, top towards north: the camera looks straight down.
    private val flat = floatArrayOf(
        1f, 0f, 0f,
        0f, 1f, 0f,
        0f, 0f, 1f,
    )

    @Test fun `what is straight ahead falls in the middle, higher up higher, east to the right`() {
        val (x, y) = arProject(facingNorth, 0.0, 0.0, 0.0, 1000.0, 1080.0, 1920.0)!!
        assertEquals(540.0, x, 1e-6)
        assertEquals(960.0, y, 1e-6)
        val up = arProject(facingNorth, 0.0, 10.0, 0.0, 1000.0, 1080.0, 1920.0)!!
        assertEquals(960.0 - 1000 * kotlin.math.tan(Math.toRadians(10.0)), up.second, 1e-6)
        val east = arProject(facingNorth, 0.0, 0.0, 10.0, 1000.0, 1080.0, 1920.0)!!
        assertTrue(east.first > 540)
        assertNull(arProject(facingNorth, 0.0, 0.0, 180.0, 1000.0, 1080.0, 1920.0)) // behind
    }

    @Test fun `the magnetic declination turns the picture`() {
        // 2° east declination: magnetic north is 2° east of true north, so true north appears 2° to the left of the middle
        val (x, _) = arProject(facingNorth, 2.0, 0.0, 0.0, 1000.0, 1080.0, 1920.0)!!
        assertEquals(540.0 - 1000 * kotlin.math.tan(Math.toRadians(2.0)), x, 1e-6)
        val (el, az) = arPointing(facingNorth, 2.0)
        assertEquals(0.0, el, 1e-6)
        assertEquals(2.0, az, 1e-6)
    }

    @Test fun `lying flat the camera looks down and sees nothing of the sky`() {
        assertEquals(-90.0, arPointing(flat, 0.0).first, 1e-6)
        assertNull(arProject(flat, 0.0, 45.0, 0.0, 1000.0, 1080.0, 1920.0))
        assertTrue(arProject(flat, 0.0, -80.0, 0.0, 1000.0, 1080.0, 1920.0) != null)
    }
}
