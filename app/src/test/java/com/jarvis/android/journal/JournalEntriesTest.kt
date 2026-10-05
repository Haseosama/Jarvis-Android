package com.jarvis.android.journal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JournalEntriesTest {
    private val day = 86_400_000L
    private val now = 100 * day

    @Test fun `entries are written and read back`() {
        val list = listOf(JournalEntry(now - 3_600_000L, DRIVE, endAt = now - 1_800_000L), JournalEntry(now, AlertKind.PARCEL, "Colis Zalando : livré"))
        assertEquals(list, decodeJournal(encodeJournal(list)))
    }

    @Test fun `an unreadable journal or entry is left out`() {
        assertTrue(decodeJournal(null).isEmpty())
        assertTrue(decodeJournal("pas du json").isEmpty())
        assertEquals(listOf(JournalEntry(5, "colis")), decodeJournal("""[{"kind":"colis"},{"at":5,"kind":"colis"},3]"""))
    }

    @Test fun `five weeks are kept, and no more than the cap`() {
        val old = JournalEntry(now - 36 * day, AlertKind.FUEL, "vieux")
        val recent = JournalEntry(now - 2 * day, AlertKind.FUEL, "récent")
        assertEquals(listOf(recent, JournalEntry(now, AlertKind.RAIN)), appendEntry(listOf(old, recent), JournalEntry(now, AlertKind.RAIN), now))
        val many = (1..JOURNAL_MAX).map { JournalEntry(now - it, AlertKind.AIR) }
        val next = appendEntry(many, JournalEntry(now, AlertKind.AIR, "dernier"), now)
        assertEquals(JOURNAL_MAX, next.size)
        assertEquals("dernier", next.last().text) // the oldest goes
    }
}
