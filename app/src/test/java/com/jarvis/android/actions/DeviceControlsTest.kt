package com.jarvis.android.actions

import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceControlsTest {
    @Test
    fun `settings pages are found by name in both languages`() {
        assertEquals(Settings.ACTION_BLUETOOTH_SETTINGS, settingsActionFor("bluetooth"))
        assertEquals(Settings.ACTION_BLUETOOTH_SETTINGS, settingsActionFor("  Bluetooth "))
        assertEquals(Settings.ACTION_AIRPLANE_MODE_SETTINGS, settingsActionFor("mode avion"))
        assertEquals(Settings.ACTION_BATTERY_SAVER_SETTINGS, settingsActionFor("batterie"))
        assertEquals(Settings.ACTION_LOCATION_SOURCE_SETTINGS, settingsActionFor("Localisation"))
        assertEquals(Settings.ACTION_DISPLAY_SETTINGS, settingsActionFor("luminosité"))
        assertNull(settingsActionFor("frigo"))
        assertNull(settingsActionFor(""))
    }

    @Test
    fun `media commands map to media keys and unknown ones do not`() {
        assertTrue(MEDIA_KEYS.containsKey("play_pause"))
        assertTrue(MEDIA_KEYS.containsKey("next"))
        assertNull(MEDIA_KEYS["rewind-to-1985"])
    }

    @Test
    fun `brightness percent maps to the system range and never goes black`() {
        assertEquals(255, brightnessToSystemValue(100))
        assertEquals(127, brightnessToSystemValue(50))
        assertEquals(5, brightnessToSystemValue(0))
        assertEquals(255, brightnessToSystemValue(500))
        assertEquals(5, brightnessToSystemValue(-20))
    }

    @Test
    fun `system brightness converts back to a percent`() {
        assertEquals(100, systemValueToPercent(255))
        assertEquals(50, systemValueToPercent(128))
        assertEquals(0, systemValueToPercent(-3))
    }
}
