package com.jarvis.android.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherLocationTest {
    @Test
    fun `no city or an explicit here means the position`() {
        listOf("", "  ", "ici", "Ici", "ma position", "Chez moi", "où je suis", "here", "my location", "autour de moi")
            .forEach { assertTrue(it, isHereRequest(it)) }
        assertTrue(isHereRequest(null))
    }

    @Test
    fun `a named place is never replaced by the position`() {
        listOf("Paris", "Lyon", "New York", "ici-bas-sur-mer").forEach { assertFalse(it, isHereRequest(it)) }
    }

    private val now = 10 * 3_600_000L

    @Test
    fun `old or impossible fixes are dropped and the newest wins`() {
        val old = Fix(45.0, 4.0, now - 2 * 3_600_000, 50f)
        val recent = Fix(45.1, 4.1, now - 5 * 60_000, 900f)
        val newer = Fix(45.2, 4.2, now - 60_000, 2000f)
        val bogus = Fix(123.0, 4.0, now, 10f)
        assertEquals(newer, pickFreshest(listOf(old, recent, newer, bogus), now))
        assertNull(pickFreshest(listOf(old, bogus), now))
        assertNull(pickFreshest(emptyList(), now))
    }

    @Test
    fun `at the same minute the more accurate fix wins`() {
        val rough = Fix(45.0, 4.0, now - 30_000, 3000f)
        val precise = Fix(45.5, 4.5, now - 20_000, 15f)
        assertEquals(precise, pickFreshest(listOf(rough, precise), now))
        assertEquals(precise, pickFreshest(listOf(precise, rough), now))
    }

    @Test
    fun `a fix from the future is ignored`() {
        assertNull(pickFreshest(listOf(Fix(1.0, 1.0, now + 3_600_000, 10f)), now))
    }

    @Test
    fun `the label mentions the town when it is known`() {
        assertEquals("votre position (Lyon)", positionLabel(" Lyon "))
        assertEquals("votre position", positionLabel(null))
        assertEquals("votre position", positionLabel("  "))
    }
}
