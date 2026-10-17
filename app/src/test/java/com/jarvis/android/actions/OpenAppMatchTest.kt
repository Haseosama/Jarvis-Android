package com.jarvis.android.actions

import com.jarvis.android.tool.Outcome
import com.jarvis.android.tool.outcomeOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAppMatchTest {
    private val apps = listOf("WPS Office", "WhatsApp", "Appareil photo", "Spotify", "Météo")

    @Test
    fun `exact names win, accents and case aside`() {
        assertEquals(1, matchAppLabel("whatsapp", apps))
        assertEquals(4, matchAppLabel("meteo", apps))
        assertEquals(2, matchAppLabel("appareil photo", apps))
    }

    @Test
    fun `a part of the name is enough from four letters`() {
        assertEquals(3, matchAppLabel("spot", apps))
        assertEquals(2, matchAppLabel("photo", apps))
    }

    @Test
    fun `under four letters only the exact name counts`() {
        assertNull(matchAppLabel("wp", apps))
        assertNull(matchAppLabel("wha", apps))
        assertEquals(0, matchAppLabel("abc", listOf("ABC")))
        assertNull(matchAppLabel("", apps))
    }

    @Test
    fun `the volume read back decides the outcome`() {
        assertEquals(Outcome.DONE, outcomeOf(volumeOutcome(50, 7, 7, 15)))
        val held = volumeOutcome(50, 7, 3, 15)
        assertEquals(Outcome.UNVERIFIED, outcomeOf(held))
        assertTrue(held.contains("20%"))
    }
}
