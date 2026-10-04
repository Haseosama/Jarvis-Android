package com.jarvis.android.ui

import com.jarvis.android.core.JarvisState
import com.jarvis.android.i18n.Lang
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The texts the HUD and the settings cards show, decided apart from the composables that draw them. */
class CardTextsTest {
    @After fun french() {
        Lang.code = Lang.FRENCH
    }

    @Test
    fun `each assistant state has its own label, in both languages`() {
        val french = JarvisState.entries.map { stateLabel(it) }
        assertEquals(french.size, french.toSet().size)
        assertEquals("En veille", stateLabel(JarvisState.ASLEEP))
        assertEquals("À l’écoute", stateLabel(JarvisState.LISTENING))

        Lang.code = Lang.ENGLISH_CODE
        val english = JarvisState.entries.map { stateLabel(it) }
        assertEquals("Asleep", stateLabel(JarvisState.ASLEEP))
        assertEquals("Listening", stateLabel(JarvisState.LISTENING))
        assertTrue("libellés non traduits : ${french.intersect(english.toSet())}", french.intersect(english.toSet()).isEmpty())
    }

    @Test
    fun `teaching a wake word waits for the models, then the microphone, then a quiet engine, then no meeting`() {
        assertNull(wakeLearnBlocker(modelsReady = true, micAllowed = true, state = JarvisState.ASLEEP, meetingRecording = false))
        assertTrue(wakeLearnBlocker(false, false, JarvisState.LISTENING, true)!!.contains("openWakeWord"))
        assertEquals("Le micro n’est pas autorisé.", wakeLearnBlocker(true, false, JarvisState.LISTENING, true))
        assertTrue(wakeLearnBlocker(true, true, JarvisState.SPEAKING, true)!!.startsWith("Fermez la session vocale"))
        assertEquals("Un enregistrement de réunion est en cours.", wakeLearnBlocker(true, true, JarvisState.ASLEEP, true))
        // any state but asleep holds the microphone
        JarvisState.entries.filter { it != JarvisState.ASLEEP }.forEach {
            assertTrue(it.name, wakeLearnBlocker(true, true, it, false) != null)
        }
    }

    @Test
    fun `the photos card says which access is missing`() {
        assertEquals("Accès aux photos et à leur position : accordé ✓", photosAccessText(photos = true, places = true, videos = true))
        assertTrue(photosAccessText(true, true, false).contains("pas aux vidéos"))
        assertTrue(photosAccessText(true, false, true).contains("pas à leur position"))
        assertTrue(photosAccessText(true, false, false).contains("pas à leur position"))
        assertEquals("Accès aux photos : non accordé.", photosAccessText(false, true, true))
    }

    @Test
    fun `the health card counts the permissions given`() {
        val wanted = setOf("steps", "distance", "sleep", "heart")
        assertEquals("Accès à Health Connect : accordé ✓", healthAccessText(wanted, wanted))
        assertEquals("Accès à Health Connect : accordé ✓", healthAccessText(wanted + "other", wanted))
        assertEquals("Accès à Health Connect : non accordé.", healthAccessText(emptySet(), wanted))
        assertEquals("Accès à Health Connect : partiel (2 sur 4).", healthAccessText(setOf("steps", "sleep", "other"), wanted))
        Lang.code = Lang.ENGLISH_CODE
        assertEquals("Access to Health Connect: partial (1 of 4).", healthAccessText(setOf("heart"), wanted))
    }
}
