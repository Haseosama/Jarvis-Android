package com.jarvis.android.weekly

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime

class WeekRecapTest {
    // Monday 28 September 2026 to Sunday 4 October
    private fun at(day: Int, h: Int, m: Int = 0) = LocalDateTime.of(2026, 9, 28, h, m).plusDays(day.toLong())

    @Test fun `the reminders that rang`() {
        assertNull(pastRemindersWords(emptyList()))
        assertEquals("rappels : appeler le garage mardi, sortir les poubelles jeudi",
            pastRemindersWords(listOf(RecapReminder(at(3, 20), "sortir les poubelles"), RecapReminder(at(1, 9), "appeler le garage"))))
        val many = (0..3).map { RecapReminder(at(it, 8), "r$it") } + RecapReminder(at(5, 10), "payer la cantine", shown = false)
        assertEquals("rappels : 4 sonnés, 1 non affiché faute de notifications (payer la cantine)", pastRemindersWords(many))
        assertEquals("rappels : aucun n’a pu s’afficher, 2 non affichés faute de notifications (a, b)",
            pastRemindersWords(listOf(RecapReminder(at(0, 8), "a", false), RecapReminder(at(1, 8), "b", false))))
    }

    @Test fun `the reminders to come`() {
        assertNull(nextRemindersWords(emptyList()))
        assertEquals("rappels prévus : lundi 9 h dentiste ; jeudi 18 h 30 sortir les poubelles",
            nextRemindersWords(listOf(RecapReminder(at(10, 18, 30), "sortir les poubelles"), RecapReminder(at(7, 9), "dentiste"))))
        val seven = (0..6).map { RecapReminder(at(7 + it, 8), "r$it") }
        assertEquals("rappels prévus : lundi 8 h r0 ; mardi 8 h r1 ; mercredi 8 h r2 ; jeudi 8 h r3 ; vendredi 8 h r4 (+2)", nextRemindersWords(seven))
        val long = "acheter " + "du pain ".repeat(20)
        assertEquals("rappels prévus : lundi 8 h acheter du pain du pain du pain du pain du pain du pain du…", nextRemindersWords(listOf(RecapReminder(at(7, 8), long))))
    }

    @Test fun `the drives`() {
        assertNull(drivesWords(emptyList()))
        assertEquals("en voiture : un trajet, mardi (25 min)", drivesWords(listOf(at(1, 8) to 25L)))
        assertEquals("en voiture : 3 trajets, 2 h 15 au volant, le plus long jeudi (1 h 05)", drivesWords(listOf(at(0, 8) to 40L, at(3, 9) to 65L, at(4, 18) to 30L)))
        assertEquals("2 h", durationWords(120))
    }

    @Test fun `the alerts`() {
        assertNull(alertsWords(emptyList()))
        assertEquals("une alerte : carburant (Gazole à 1,62 € chez Leclerc)", alertsWords(listOf("carburant" to "Gazole à 1,62 € chez Leclerc.")))
        assertEquals("une alerte : pluie", alertsWords(listOf("pluie" to "")))
        val week = listOf("carburant" to "a", "colis" to "b", "vigilance météo" to "c", "colis" to "d", "vigilance météo" to "e", "colis" to "f")
        assertEquals("6 alertes reçues : colis (3), vigilance météo (2), carburant", alertsWords(week))
    }

    @Test fun `the summary with nothing from the week`() {
        assertEquals("Bilan de la semaine. Pas de données sur la semaine passée (santé, dépenses, rappels, trajets, alertes). La semaine prochaine : rien à l’agenda.",
            summaryWords(emptyList(), listOf("rien à l’agenda")))
    }
}
