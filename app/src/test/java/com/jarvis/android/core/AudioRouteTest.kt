package com.jarvis.android.core

import android.media.AudioDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioRouteTest {
    private data class Dev(val type: Int, val name: String, val address: String = "")

    private fun key(d: Dev) = deviceKey(d.type, d.name, d.address)

    @Test
    fun `a saved device is found among the connected ones`() {
        val phone = Dev(AudioDeviceInfo.TYPE_BUILTIN_MIC, "Micro")
        val headset = Dev(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "Casque de Sam", "AA:BB")
        assertEquals(headset, pickByKey(listOf(phone, headset), key(headset), ::key))
    }

    @Test
    fun `blank choice means automatic and an absent device is ignored`() {
        val phone = Dev(AudioDeviceInfo.TYPE_BUILTIN_MIC, "Micro")
        assertNull(pickByKey(listOf(phone), "", ::key))
        assertNull(pickByKey(listOf(phone), key(Dev(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "Casque", "AA")), ::key))
    }

    @Test
    fun `the key ignores the changing numeric id and tells devices apart`() {
        assertEquals("7|Casque|AA", deviceKey(7, "Casque", "AA"))
        assert(deviceKey(7, "Casque", "AA") != deviceKey(7, "Casque", "BB"))
    }

    @Test
    fun `labels name the kind and the device`() {
        assertEquals("Micro du téléphone", deviceLabel(AudioDeviceInfo.TYPE_BUILTIN_MIC, ""))
        assertEquals("Micro du téléphone", deviceLabel(AudioDeviceInfo.TYPE_BUILTIN_MIC, "micro du téléphone"))
        assertEquals("Bluetooth (appel) : Casque de Sam", deviceLabel(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, " Casque de Sam "))
    }
}
