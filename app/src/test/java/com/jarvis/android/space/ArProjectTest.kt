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

    @Test fun `the guide turns the shorter way and says it in words`() {
        val (turn, raise) = arGuide(10.0, 350.0, 30.0, 20.0)
        assertEquals(30.0, turn, 1e-9) // across north, to the right
        assertEquals(20.0, raise, 1e-9)
        assertEquals(-90.0, arGuide(0.0, 90.0, 0.0, 0.0).first, 1e-9)
        assertEquals("tournez à droite de 30°, levez le téléphone de 20°", arGuideWords(30.0, 20.0))
        assertEquals("tournez à gauche de 90°", arGuideWords(-90.0, 1.0))
        assertEquals("baissez le téléphone de 15°", arGuideWords(2.0, -15.0))
        assertEquals("c’est au centre", arGuideWords(1.0, -2.0))
    }

    @Test fun `the object asked for is found by its name`() {
        assertTrue(arNameMatches("Lune", "moon", "la Lune"))
        assertTrue(arNameMatches("Lune (sous l’horizon)", "moon", "lune"))
        assertTrue(arNameMatches("Jupiter", "pl:JUPITER", "jupiter"))
        assertTrue(arNameMatches("Vénus", "pl:VENUS", "venus"))
        assertTrue(arNameMatches("ISS", "sat:25544", "l’ISS"))
        assertTrue(arNameMatches("ISS", "sat:25544", "la station spatiale internationale"))
        assertTrue(arNameMatches("Sirius", "star:2491", "Sirius"))
        assertTrue(!arNameMatches("Saturne", "pl:SATURN", "Jupiter"))
        assertTrue(!arNameMatches("Lune", "moon", ""))
    }

    @Test fun `lying flat the camera looks down and sees nothing of the sky`() {
        assertEquals(-90.0, arPointing(flat, 0.0).first, 1e-6)
        assertNull(arProject(flat, 0.0, 45.0, 0.0, 1000.0, 1080.0, 1920.0))
        assertTrue(arProject(flat, 0.0, -80.0, 0.0, 1000.0, 1080.0, 1920.0) != null)
    }
}
