package com.jarvis.android.people

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PersonRemindersTest {
    @get:Rule val tmp = TemporaryFolder()

    private val paul = PersonReminder(1, "Paul Durand", listOf("612345678", "298000000"), "lui parler du week-end", 0)
    private val marie = PersonReminder(2, "Marie", listOf("699999999"), "lui rendre son livre", 0)

    @Test
    fun `a call matches whatever way the number is written`() {
        assertEquals(listOf(paul), remindersForNumber(listOf(paul, marie), "+33 6 12 34 56 78"))
        assertEquals(listOf(paul), remindersForNumber(listOf(paul, marie), "0298000000"))
        assertTrue(remindersForNumber(listOf(paul, marie), "0611111111").isEmpty())
        assertTrue(remindersForNumber(listOf(paul, marie), "12").isEmpty())
    }

    @Test
    fun `a message matches the sender's name as the app shows it, not a longer name`() {
        assertEquals(listOf(paul), remindersForSender(listOf(paul, marie), "Paul Durand"))
        assertEquals(listOf(paul), remindersForSender(listOf(paul, marie), "Paul Durand (2 messages)"))
        assertEquals(listOf(marie), remindersForSender(listOf(paul, marie), "marie"))
        assertTrue(remindersForSender(listOf(paul, marie), "Marie-Claire").isEmpty())
        assertTrue(remindersForSender(listOf(paul, marie), "Paul").isEmpty())
    }

    @Test
    fun `stored, shown once per half hour, and removed when done`() {
        val store = PersonReminderStore(File(tmp.root, "p.json"))
        val r = store.add("Paul Durand", listOf("06 12 34 56 78", "+33612345678", "12"), "lui parler du week-end", now = 1_000)
        assertEquals(listOf("612345678"), r.keys)
        val all = PersonReminderStore(File(tmp.root, "p.json")).all()
        assertEquals(1, store.takeDue(all, now = 10_000_000).size)
        assertTrue(store.takeDue(store.all(), now = 10_000_000 + 60_000).isEmpty())
        assertEquals(1, store.takeDue(store.all(), now = 10_000_000 + PERSON_REMINDER_QUIET_MS).size)
        store.remove(r.id)
        assertTrue(store.all().isEmpty())
    }
}
