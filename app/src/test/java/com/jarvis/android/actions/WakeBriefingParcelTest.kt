package com.jarvis.android.actions

import com.jarvis.android.parcels.Carrier
import com.jarvis.android.parcels.Parcel
import com.jarvis.android.wakeup.BRIEFING_SECTIONS
import com.jarvis.android.wakeup.WAKE_NOTIFY
import com.jarvis.android.wakeup.WAKE_OFF
import com.jarvis.android.wakeup.WAKE_SPEAK
import com.jarvis.android.wakeup.WakeData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class WakeBriefingParcelTest {
    @Test
    fun `each briefing mode is said in words`() {
        assertTrue(WakeBriefingTool.modeWords(WAKE_SPEAK).startsWith("à voix haute"))
        assertTrue(WakeBriefingTool.modeWords(WAKE_NOTIFY).startsWith("en notification"))
        assertEquals("désactivé", WakeBriefingTool.modeWords(WAKE_OFF))
    }

    @Test
    fun `the briefing status lists what it says, and what was removed`() {
        val all = WakeBriefingTool.statusWords(WakeData(mode = WAKE_SPEAK))
        assertTrue(all.startsWith("Briefing du matin : à voix haute"))
        BRIEFING_SECTIONS.values.forEach { assertTrue(it, all.contains(it)) }
        assertFalse(all.contains("Retirés"))
        assertFalse(all.contains("en voiture"))

        val trimmed = WakeBriefingTool.statusWords(WakeData(mode = WAKE_NOTIFY, car = true, off = listOf("mails", "meteo")))
        assertTrue(trimmed.contains("; et en voiture le matin"))
        assertTrue(trimmed.endsWith(" Retirés : les mails importants, la météo."))
        val said = trimmed.substringAfter("Il dit : ").substringBefore(". Retirés")
        assertFalse(said.contains("les mails importants"))
        assertFalse(said.contains("la météo,"))
    }

    @Test
    fun `an unknown key switched off is not named among the removed parts`() {
        val text = WakeBriefingTool.statusWords(WakeData(off = listOf("inconnu", "uv")))
        assertTrue(text.endsWith(" Retirés : les UV élevés."))
    }

    @Test
    fun `a parcel line names it by its label, else its number, with where it is`() {
        val zone = ZoneId.of("Europe/Paris")
        val withStatus = Parcel("6A12345678901", Carrier.LAPOSTE, label = "chaussures", status = "En cours de livraison", statusAt = "2026-10-04T08:00:00Z")
        assertEquals("1) chaussures — ${Carrier.LAPOSTE.label} : En cours de livraison (le 4/10 à 10 h)", ParcelTool.line(0, withStatus, zone))

        val waiting = Parcel("6A12345678901", Carrier.LAPOSTE)
        assertEquals("2) 6A12345678901 — ${Carrier.LAPOSTE.label} : pas encore d'information", ParcelTool.line(1, waiting, zone))

        val elsewhere = Parcel("1Z999AA10123456784", Carrier.UPS, label = "livre")
        assertEquals("3) livre — UPS : à suivre sur le site de UPS (« ouvre le suivi »)", ParcelTool.line(2, elsewhere, zone))
    }

    @Test
    fun `a parcel status with an unreadable date keeps the status alone`() {
        val p = Parcel("X", Carrier.DHL, status = "Livré", statusAt = "hier")
        assertEquals("1) X — DHL : Livré", ParcelTool.line(0, p, ZoneId.of("UTC")))
    }
}
