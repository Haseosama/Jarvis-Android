package com.jarvis.android.actions

import com.jarvis.android.offline.OfflineAction
import com.jarvis.android.offline.interpret
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class CallLogToolTest {
    private val zone = ZoneId.of("Europe/Paris")
    private val today = LocalDate.of(2026, 9, 26)
    private fun at(day: LocalDate, h: Int, m: Int) = day.atTime(h, m).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `a caller is their contact name, and an unknown number is never spelled out`() {
        assertEquals("Paul Durand", callerLabel(CallEntry("Paul Durand", "+33612345678", CallKind.MISSED, 0, 0)))
        assertEquals("numéro inconnu finissant par 78", callerLabel(CallEntry(null, "+33612345678", CallKind.MISSED, 0, 0)))
        assertEquals("numéro masqué", callerLabel(CallEntry(null, "", CallKind.MISSED, 0, 0)))
        assertFalse(callerLabel(CallEntry(null, "0612345678", CallKind.MISSED, 0, 0)).contains("1234"))
    }

    @Test
    fun `repeated calls from one number are grouped, newest first, whatever its format`() {
        val entries = listOf(
            CallEntry(null, "06 12 34 56 78", CallKind.MISSED, at(today, 9, 3), 0),
            CallEntry("Paul", "+33 6 99 99 99 99", CallKind.MISSED, at(today, 11, 0), 0),
            CallEntry(null, "+33612345678", CallKind.MISSED, at(today, 12, 30), 0),
        )
        val groups = groupCallers(entries)
        assertEquals(2, groups.size)
        assertEquals(2, groups[0].count)
        assertEquals(
            "1) numéro inconnu finissant par 78 (2 fois), aujourd'hui à 12 h 30\n2) Paul, aujourd'hui à 11 h",
            describeGroups(groups, today, zone),
        )
    }

    @Test
    fun `times read as today, yesterday or a date`() {
        assertEquals("hier à 18 h 12", whenLabel(at(today.minusDays(1), 18, 12), today, zone))
        assertEquals("le 21/09 à 14 h", whenLabel(at(LocalDate.of(2026, 9, 21), 14, 0), today, zone))
    }

    @Test
    fun `offline, asking who called is understood`() {
        assertEquals("missed", (interpret("Qui m'a appelé ?") as OfflineAction.ToolCall).args["action"])
        assertEquals("missed", (interpret("J'ai des appels manqués ?") as OfflineAction.ToolCall).args["action"])
        assertEquals("recent", (interpret("mes derniers appels") as OfflineAction.ToolCall).args["action"])
    }
}
