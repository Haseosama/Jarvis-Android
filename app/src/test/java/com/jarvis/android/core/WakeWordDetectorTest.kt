package com.jarvis.android.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeWordDetectorTest {
    @Test
    fun `english and french wake phrases match`() {
        listOf("Hey Jarvis", "HEY JARVIS", "hey, jarvis", "hé jarvis", "Hé Jarvis").forEach {
            assertTrue(it, WakeWordDetector.isWakePhrase(it))
        }
    }

    @Test
    fun `ok variants match inside longer sentences`() {
        listOf("ok jarvis quelle heure est-il", "dis-moi okay jarvis s’il te plaît").forEach {
            assertTrue(it, WakeWordDetector.isWakePhrase(it))
        }
    }

    @Test
    fun `partial words and unrelated speech do not match`() {
        listOf("", "hey", "jarvis", "hey jean", "ok google", "bonjour").forEach {
            assertFalse(it, WakeWordDetector.isWakePhrase(it))
        }
    }
}
