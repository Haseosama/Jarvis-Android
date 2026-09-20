package com.jarvis.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceServiceControlTest {
    @Test
    fun `standby needs the wake word and the microphone`() {
        assertTrue(wantsStandby(wakeEnabled = true, micGranted = true))
        assertFalse(wantsStandby(wakeEnabled = true, micGranted = false))
        assertFalse(wantsStandby(wakeEnabled = false, micGranted = true))
        assertFalse(wantsStandby(wakeEnabled = false, micGranted = false))
    }

    @Test
    fun `a sleeping assistant in standby says it is listening for the wake word`() {
        assertEquals(
            "En attente du mot d’activation · microphone actif",
            voiceNotificationText(JarvisState.ASLEEP, standby = true, video = VideoSource.OFF),
        )
        assertEquals("Jarvis · écoute du mot d’activation", voiceNotificationTitle(JarvisState.ASLEEP, true))
        assertEquals("Désactiver l’écoute", voiceNotificationAction(JarvisState.ASLEEP, true))
    }

    @Test
    fun `without standby a sleeping assistant says the microphone is off`() {
        assertEquals("En veille · microphone arrêté", voiceNotificationText(JarvisState.ASLEEP, false, VideoSource.OFF))
        assertEquals("Jarvis · session vocale", voiceNotificationTitle(JarvisState.ASLEEP, false))
        assertEquals("Arrêter", voiceNotificationAction(JarvisState.ASLEEP, false))
    }

    @Test
    fun `during a session the button ends the session even in standby`() {
        assertEquals("Arrêter", voiceNotificationAction(JarvisState.LISTENING, true))
        assertEquals("À l’écoute · microphone actif", voiceNotificationText(JarvisState.LISTENING, true, VideoSource.OFF))
    }

    @Test
    fun `shared views are mentioned`() {
        assertEquals("À l’écoute · microphone actif · écran partagé", voiceNotificationText(JarvisState.LISTENING, false, VideoSource.SCREEN))
        assertEquals("À l’écoute · microphone actif · caméra partagée", voiceNotificationText(JarvisState.LISTENING, false, VideoSource.CAMERA))
    }
}
