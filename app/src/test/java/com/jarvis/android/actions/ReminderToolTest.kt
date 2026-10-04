package com.jarvis.android.actions

import com.jarvis.android.reminders.ReminderRecord
import com.jarvis.android.reminders.ReminderService
import com.jarvis.android.reminders.ReminderStatus
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ReminderToolTest {
    private val now = 1_800_000_000_000L

    private fun record(id: Int, status: ReminderStatus = ReminderStatus.SCHEDULED, triggerAt: Long = now + 60_000, text: String = "Appeler Léa", approximate: Boolean = true) =
        ReminderRecord(id, "t$id", text, "2027-01-15 09:30", "Europe/Paris", triggerAt, approximate, status)

    @Test
    fun `strict arguments take text as text and numbers as numbers, nothing else`() {
        val args = buildJsonObject {
            put("mode", "list")
            put("id", 4)
            put("idAsText", "4")
            put("offset", 2.5)
        }
        assertEquals("list", ReminderTool.strictString(args, "mode"))
        assertEquals("create", ReminderTool.strictString(args, "absent", "create"))
        assertThrows(IllegalArgumentException::class.java) { ReminderTool.strictString(args, "id") }

        assertEquals(4, ReminderTool.strictInt(args, "id"))
        assertEquals(0, ReminderTool.strictInt(args, "absent", 0))
        val textId = assertThrows(IllegalArgumentException::class.java) { ReminderTool.strictInt(args, "idAsText") }
        assertTrue(textId.message!!.contains("entier"))
        assertThrows(IllegalArgumentException::class.java) { ReminderTool.strictInt(args, "offset") }
        // no default: a missing id is an error, not 0
        assertThrows(IllegalArgumentException::class.java) { ReminderTool.strictInt(args, "absent") }
    }

    @Test
    fun `each status is said plainly, and a scheduled one past its time is not called scheduled`() {
        assertEquals("programmé", ReminderTool.status(record(1), now))
        assertEquals("échéance atteinte, livraison non confirmée", ReminderTool.status(record(1, triggerAt = now), now))
        val words = ReminderStatus.entries.map { ReminderTool.status(record(1, status = it), now) }
        assertEquals(words.size, words.toSet().size)
        assertTrue(words.all { it.isNotBlank() })
    }

    @Test
    fun `creating says the date, the zone, the text and how exact the alarm is`() {
        val approx = ReminderTool.created(record(7))
        assertTrue(approx.startsWith("Rappel #7 enregistré pour 2027-01-15 09:30 (Europe/Paris) : Appeler Léa."))
        assertTrue(approx.contains("Alarme approximative"))
        assertTrue(approx.endsWith(ReminderService.LIMITATION))
        assertTrue(ReminderTool.created(record(7, approximate = false)).contains("Alarme exacte demandée"))
    }

    @Test
    fun `an empty list and an offset past the end are told apart`() {
        assertTrue(ReminderTool.reminderListText(emptyList(), 0, null, now).startsWith("Aucun rappel enregistré."))
        assertTrue(ReminderTool.reminderListText(listOf(record(1)), 5, null, now).startsWith("Aucun rappel à ce décalage (1 au total)."))
    }

    @Test
    fun `the list goes ten at a time and says how to get the rest`() {
        val records = (1..23).map { record(it) }
        val first = ReminderTool.reminderListText(records, 0, null, now)
        assertTrue(first.startsWith("Rappels 1 à 10 sur 23 :"))
        assertTrue(first.contains("#10 —"))
        assertFalse(first.contains("#11 —"))
        assertTrue(first.contains("Suite : mode=list, offset=10."))

        val last = ReminderTool.reminderListText(records, 20, null, now)
        assertTrue(last.startsWith("Rappels 21 à 23 sur 23 :"))
        assertFalse(last.contains("Suite :"))
    }

    @Test
    fun `each line keeps to one line and carries the status, the precision and a warning when there is one`() {
        val text = ReminderTool.reminderListText(listOf(record(3, text = "acheter\ndu pain\r"), record(4, approximate = false)), 0, "Notifications désactivées.", now)
        assertTrue(text.contains("#3 — 2027-01-15 09:30 (Europe/Paris) — programmé — approximatif — acheter du pain "))
        assertTrue(text.contains("#4 — 2027-01-15 09:30 (Europe/Paris) — programmé — exact demandé"))
        assertTrue(text.contains("\nNotifications désactivées."))
        assertTrue(text.contains(ReminderService.LIMITATION))
    }
}
