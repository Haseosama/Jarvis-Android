package com.jarvis.android.places

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PlaceRemindersTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `leaving is recognised however it is said, arriving is the default`() {
        assertEquals(PlaceTrigger.LEAVE, parseTrigger("en partant"))
        assertEquals(PlaceTrigger.LEAVE, parseTrigger("quand je quitte"))
        assertEquals(PlaceTrigger.LEAVE, parseTrigger("leave"))
        assertEquals(PlaceTrigger.ARRIVE, parseTrigger("en arrivant"))
        assertEquals(PlaceTrigger.ARRIVE, parseTrigger(""))
    }

    @Test
    fun `place names fold articles and the usual synonyms`() {
        assertEquals("maison", placeKey("à la maison"))
        assertEquals("maison", placeKey("chez moi"))
        assertEquals("bureau", placeKey("au boulot"))
        assertEquals("boulangerie", placeKey("la Boulangerie"))
        assertTrue(isHere("ici"))
        assertFalse(isHere("la boulangerie"))
    }

    @Test
    fun `a saved place is found by any way of naming it`() {
        val places = listOf(SavedPlace("maison", 48.8, 2.3), SavedPlace("bureau", 48.9, 2.2))
        assertEquals("maison", findPlace(places, "chez moi")?.name)
        assertEquals("bureau", findPlace(places, "au travail")?.name)
        assertNull(findPlace(places, "la boulangerie"))
    }

    @Test
    fun `a repeating reminder waits out its cooldown, a one-off always fires`() {
        val r = PlaceReminder(1, "pain", "boulangerie", 0.0, 0.0, repeat = true, lastFiredAt = 1_000_000)
        assertFalse(shouldFire(r, 1_000_000 + REPEAT_COOLDOWN_MS - 1))
        assertTrue(shouldFire(r, 1_000_000 + REPEAT_COOLDOWN_MS))
        assertTrue(shouldFire(r.copy(repeat = false), 1_000_001))
    }

    @Test
    fun `reminders are designated by number or by words`() {
        val all = listOf(
            PlaceReminder(1, "acheter du pain", "boulangerie", 0.0, 0.0),
            PlaceReminder(2, "appeler Paul", "bureau", 0.0, 0.0, trigger = PlaceTrigger.LEAVE),
        )
        assertEquals(listOf(2), matchReminders(all, "#2").map { it.id })
        assertEquals(listOf(1), matchReminders(all, "pain").map { it.id })
        assertEquals(listOf(2), matchReminders(all, "au bureau").map { it.id })
        assertTrue(matchReminders(all, "").isEmpty())
        assertEquals("#2 — en partant bureau : appeler Paul, une fois", describeReminder(all[1]))
    }

    @Test
    fun `the store keeps radii within what Android handles and replaces a place of the same name`() {
        val s = PlaceStore(tmp.newFile())
        val r = s.add(PlaceReminder(0, "  pain  ", "boulangerie", 48.0, 2.0, radiusM = 20))!!
        assertEquals(1, r.id)
        assertEquals(MIN_RADIUS_M, r.radiusM)
        assertEquals("pain", r.text)
        s.savePlace("La maison", 1.0, 1.0, "vieille adresse")
        s.savePlace("chez moi", 2.0, 2.0, "nouvelle adresse")
        assertEquals(listOf("nouvelle adresse"), s.load().places.map { it.label })
        s.remove(listOf(r.id))
        assertTrue(s.load().reminders.isEmpty())
    }
}
