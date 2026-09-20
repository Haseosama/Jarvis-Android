package com.jarvis.android.wake

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeClassifierTest {
    private fun fake(size: Int, magic: String = "TFL3") = ByteArray(size).also { magic.toByteArray().copyInto(it, 4) }

    @Test
    fun `a small tflite classifier is accepted`() {
        assertTrue(looksLikeClassifier(fake(200_000)))
    }

    @Test
    fun `wrong header, tiny or huge files are refused`() {
        assertFalse(looksLikeClassifier(fake(200_000, "NOPE")))
        assertFalse(looksLikeClassifier(fake(500)))
        assertFalse(looksLikeClassifier(fake(6_000_000)))
    }

    @Test
    fun `presets include the default and have distinct files`() {
        assertTrue(WAKE_PRESETS.first().file == WAKE_FILE_CLASSIFIER)
        assertTrue(WAKE_PRESETS.map { it.file }.toSet().size == WAKE_PRESETS.size)
    }
}
