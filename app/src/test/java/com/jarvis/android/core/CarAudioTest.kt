package com.jarvis.android.core

import android.media.AudioDeviceInfo
import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarAudioTest {
    @Test fun `automatic follows what is detected, the other settings decide alone`() {
        assertFalse(carModeActive(CAR_AUTO, uiModeCar = false, projection = false))
        assertTrue(carModeActive(CAR_AUTO, uiModeCar = true, projection = false))
        assertTrue(carModeActive(CAR_AUTO, uiModeCar = false, projection = true))
        assertTrue(carModeActive(CAR_ON, false, false))
        assertFalse(carModeActive(CAR_OFF, true, true))
    }

    @Test fun `in a car the focus is light and the echo tail longer`() {
        assertEquals(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK, focusGainFor(true))
        assertEquals(AudioManager.AUDIOFOCUS_GAIN, focusGainFor(false))
        assertTrue(echoTailMs(true) > echoTailMs(false))
        assertEquals(350L, echoTailMs(false))
    }

    @Test fun `a car's bluetooth or usb output reaches the microphone, a headset does not`() {
        val a2dp = listOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
        assertFalse(voiceReachesMicrophone(a2dp, null, car = false))
        assertTrue(voiceReachesMicrophone(a2dp, null, car = true))
        assertTrue(voiceReachesMicrophone(a2dp, AudioDeviceInfo.TYPE_USB_HEADSET, car = true))
        assertTrue(voiceReachesMicrophone(listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER), null, car = false))
        assertTrue(voiceReachesMicrophone(a2dp, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, car = false))
        assertFalse(voiceReachesMicrophone(a2dp, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, car = false))
    }
}
