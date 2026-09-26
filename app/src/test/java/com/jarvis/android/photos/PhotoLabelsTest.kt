package com.jarvis.android.photos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

class PhotoLabelsTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `the labels asked for are split and folded`() {
        assertEquals(listOf("dog", "puppy"), labelTerms(" Dog, puppy ; dog"))
        assertEquals(emptyList<String>(), labelTerms(" , a"))
    }

    @Test
    fun `a label matches as a whole word`() {
        assertTrue(matchesLabels(listOf("Dog", "Grass"), listOf("dog")))
        assertTrue(matchesLabels(listOf("Sports car"), listOf("car")))
        assertFalse(matchesLabels(listOf("Cartoon"), listOf("car")))
        assertFalse(matchesLabels(emptyList(), listOf("dog")))
    }

    @Test
    fun `the index keeps labels between runs, an unreadable photo included`() {
        val index = PhotoLabelIndex(File(tmp.root, "labels.json"))
        index.putAll(mapOf(1L to listOf("Dog"), 2L to emptyList()))
        val again = PhotoLabelIndex(File(tmp.root, "labels.json"))
        assertEquals(listOf("Dog"), again.get(1))
        assertEquals(emptyList<String>(), again.get(2))
        assertNull(again.get(3))
        assertEquals(2, again.size())
    }

    @Test
    fun `the answer names what was looked for`() {
        val zone = ZoneId.of("Europe/Paris")
        val d = LocalDate.of(2026, 8, 3)
        val p = Photo(1, d.atTime(12, 0).atZone(zone).toInstant().toEpochMilli(), "Camera")
        assertEquals("1 photo « chien » le 3 août 2026, dans Camera.", describePhotos(listOf(p), zone, subject = "chien"))
        assertEquals("Aucune photo « chien » prise près de Brest pour cette période.", describePhotos(emptyList(), zone, "Brest", "chien"))
    }
}
