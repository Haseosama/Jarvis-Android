package com.jarvis.android.sos

import com.jarvis.android.offline.OfflineAction
import com.jarvis.android.offline.interpret
import com.jarvis.android.weather.Fix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SosTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `the message carries a map link to the position, or says it is unknown`() {
        val now = 1_800_000_000_000L
        val msg = sosMessage(Fix(48.390392, -4.486076, now - 30_000, 12.6f), now)
        assertTrue(msg, msg.contains("https://maps.google.com/?q=48.39039,-4.48608"))
        assertTrue(msg, msg.contains("à 12 m près)"))
        assertTrue(sosMessage(Fix(48.0, -4.0, now - 5 * 60_000, 30f), now).contains("il y a 5 min"))
        assertTrue(sosMessage(null, now).endsWith("Position indisponible."))
    }

    @Test
    fun `at most three contacts, never the same number twice`() {
        var d = SosData()
        d = withContact(d, SosContact("Marie", "06 12 34 56 78"))
        d = withContact(d, SosContact("Marie bis", "+33612345678"))
        d = withContact(d, SosContact("", "0699999999"))
        d = withContact(d, SosContact("Léa", "0611111111"))
        d = withContact(d, SosContact("Tom", "0622222222"))
        assertEquals(listOf("Marie", "0699999999", "Léa"), d.contacts.map { it.name })
        assertEquals(d, withContact(d, SosContact("Court", "12")))
    }

    @Test
    fun `no alert without contacts, nor twice in a minute, and names read naturally`() {
        val store = SosStore(File(tmp.root, "sos.json"))
        assertEquals(SosArm.NoContacts, sosRefusal(store.load(), armed = false, nowMs = 1_000_000))
        val set = store.update { withContact(it, SosContact("Marie", "0612345678")) }
        assertEquals("Marie", SosStore(File(tmp.root, "sos.json")).load().contacts.single().name)
        assertEquals(null, sosRefusal(set, armed = false, nowMs = 1_000_000))
        assertEquals(SosArm.AlreadyArmed, sosRefusal(set, armed = true, nowMs = 1_000_000))
        assertEquals(SosArm.TooSoon(20), sosRefusal(set.copy(lastSentAt = 980_000), armed = false, nowMs = 1_000_000))
        assertEquals(null, sosRefusal(set.copy(lastSentAt = 900_000), armed = false, nowMs = 1_000_000))
        assertFalse(SosAlarm.armed)
        assertEquals("Marie, Paul et Léa", namesList(listOf("Marie", "Paul", "Léa")))
        assertEquals("Marie et Paul", namesList(listOf("Marie", "Paul")))
    }

    @Test
    fun `offline, a cry for help raises the alert and only explicit words cancel it`() {
        for (s in listOf("Au secours !", "SOS", "à l'aide", "Jarvis, urgence")) {
            assertEquals(s, "alert", (interpret(s) as OfflineAction.ToolCall).args["action"])
        }
        assertEquals("cancel", (interpret("Annule l'alerte") as OfflineAction.ToolCall).args["action"])
        assertEquals("cancel", (interpret("fausse alerte") as OfflineAction.ToolCall).args["action"])
        // with no alert counting down, "stop" still ends the session
        assertTrue((interpret("stop") as OfflineAction.Say).end)
    }
}
