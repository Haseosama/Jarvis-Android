package com.jarvis.android.quiet

import com.jarvis.android.offline.OfflineAction
import com.jarvis.android.offline.interpret
import com.jarvis.android.text.normalize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId

class QuietTest {
    @get:Rule val tmp = TemporaryFolder()
    private val zone = ZoneId.of("Europe/Paris")
    private val now = LocalDateTime.of(2026, 9, 26, 13, 40)
    private fun ms(t: LocalDateTime) = t.atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `the end is the next time it is that hour, or a duration, kept between 5 minutes and 12 hours`() {
        assertEquals(ms(now.withHour(15).withMinute(0)), quietUntil("15:00", 0, now, zone))
        assertEquals(ms(now.withHour(15).withMinute(30)), quietUntil("15h30", 0, now, zone))
        // 8 h has passed today: tomorrow 8 h — but that is more than 12 hours away, so it stops at the cap.
        assertEquals(ms(now.plusHours(12)), quietUntil("8", 0, now, zone))
        val night = now.withHour(22).withMinute(30)
        assertEquals(ms(night.plusDays(1).withHour(8).withMinute(0)), quietUntil("8", 0, night, zone))
        assertEquals(ms(now.plusMinutes(90)), quietUntil("", 90, now, zone))
        assertEquals(ms(now.plusMinutes(5)), quietUntil("", 1, now, zone))
        assertEquals(ms(now.plusHours(12)), quietUntil("", 5000, now, zone))
        assertNull(quietUntil("", 0, now, zone))
        assertNull(nextClock(25, 0, now, zone))
        assertEquals("15 h", clockWords(ms(now.withHour(15).withMinute(0)), zone))
        assertEquals("15 h 05", clockWords(ms(now.withHour(15).withMinute(5)), zone))
    }

    @Test
    fun `offline phrasings of the start and the end`() {
        assertEquals(QuietAsk(until = "15:00"), quietStartPhrase(normalize("Je suis en réunion jusqu'à 15 h")))
        assertEquals(QuietAsk(until = "15:30"), quietStartPhrase(normalize("je suis en réunion jusqu'à 15h30")))
        assertEquals(QuietAsk(until = "9:00"), quietStartPhrase(normalize("ne me dérange pas jusqu'à 9 heures")))
        assertEquals(QuietAsk(minutes = 120), quietStartPhrase(normalize("Ne me dérange pas pendant 2 heures")))
        assertEquals(QuietAsk(minutes = 30), quietStartPhrase(normalize("ne me dérange pas pendant une demi-heure")))
        assertEquals(QuietAsk(minutes = 60), quietStartPhrase(normalize("mode ne pas déranger")))
        assertNull(quietStartPhrase(normalize("je suis en réunion avec Paul")))
        assertTrue(quietStopPhrase(normalize("J'ai fini ma réunion")))
        assertTrue(quietStopPhrase(normalize("tu peux me déranger")))
        assertFalse(quietStopPhrase(normalize("ne me dérange pas")))
        val a = interpret("je suis en réunion jusqu'à 15 h") as OfflineAction.ToolCall
        assertEquals(mapOf("action" to "start", "until" to "15:00"), a.args)
    }

    @Test
    fun `the automatic answer is off until switched on, and the saved state survives`() {
        val store = QuietStore(File(tmp.root, "q.json"))
        assertFalse(store.load().autoReply)
        assertTrue(DEFAULT_QUIET_REPLY.contains("{heure}"))
        store.update { it.copy(state = QuietState(1, 2, "réunion", previousFilter = 1, previousPolicy = SavedPolicy(1, 2, 3, 4))) }
        assertEquals(SavedPolicy(1, 2, 3, 4), QuietStore(File(tmp.root, "q.json")).load().state?.previousPolicy)
    }
}
