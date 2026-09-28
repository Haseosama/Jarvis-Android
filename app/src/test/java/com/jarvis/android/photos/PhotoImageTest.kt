package com.jarvis.android.photos

import org.junit.Assert.assertEquals
import org.junit.Test

class PhotoImageTest {
    @Test fun `a photo is divided by a power of two, never below the size asked for`() {
        assertEquals(1, sampleFor(1600, 1200, 1600))
        assertEquals(2, sampleFor(4000, 3000, 1600))
        assertEquals(4, sampleFor(8000, 6000, 1600))
        assertEquals(1, sampleFor(800, 600, 1600))
        assertEquals(2, sampleFor(3000, 4000, 1500))
    }
}
