package com.jarvis.android.watch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WatchTest {
    @get:Rule val folder = TemporaryFolder()

    private val bitcoinAbove = Watch(1, KIND_CRYPTO, "bitcoin", 70_000.0, above = true)

    @Test
    fun `a price alerts once when it crosses and again only after it cleared`() {
        val first = evaluateWatch(bitcoinAbove, 71_000.0, 1L)
        assertNotNull(first.alert)
        assertTrue(first.watch.triggered)
        val second = evaluateWatch(first.watch, 72_000.0, 2L)
        assertNull(second.alert)
        assertTrue(second.watch.triggered)
        // back just under the threshold, within the margin: still armed as triggered, no new alert
        val hovering = evaluateWatch(second.watch, 69_800.0, 3L)
        assertNull(hovering.alert)
        assertTrue(hovering.watch.triggered)
        val cleared = evaluateWatch(hovering.watch, 60_000.0, 4L)
        assertNull(cleared.alert)
        assertFalse(cleared.watch.triggered)
        assertNotNull(evaluateWatch(cleared.watch, 70_500.0, 5L).alert)
    }

    @Test
    fun `a below watch alerts when the value falls`() {
        val memory = Watch(2, KIND_MEMORY, "", 400.0, above = false)
        assertNull(evaluateWatch(memory, 900.0, 1L).alert)
        val low = evaluateWatch(memory, 300.0, 2L)
        assertNotNull(low.alert)
        assertTrue(low.watch.triggered)
    }

    @Test
    fun `a site alerts when it goes down and when it is back`() {
        val site = Watch(3, KIND_SITE, "https://example.org")
        assertNull(evaluateWatch(site, 1.0, 1L).alert)
        val down = evaluateWatch(site, 0.0, 2L)
        assertNotNull(down.alert)
        assertNull(evaluateWatch(down.watch, 0.0, 3L).alert)
        val back = evaluateWatch(down.watch, 1.0, 4L)
        assertNotNull(back.alert)
        assertFalse(back.watch.triggered)
    }

    @Test
    fun `a failed measure changes nothing`() {
        val result = evaluateWatch(bitcoinAbove.copy(triggered = true, lastValue = 71_000.0), null, 9L)
        assertNull(result.alert)
        assertTrue(result.watch.triggered)
        assertEquals(71_000.0, result.watch.lastValue!!, 0.0)
        assertEquals(9L, result.watch.lastCheck)
    }

    @Test
    fun `coin prices are read from the answer`() {
        assertEquals(61234.5, parseCoinPrice("{\"bitcoin\":{\"eur\":61234.5}}", "bitcoin")!!, 0.001)
        assertNull(parseCoinPrice("{\"bitcoin\":{\"eur\":1}}", "ethereum"))
        assertNull(parseCoinPrice("pas du json", "bitcoin"))
    }

    @Test
    fun `sites and coins are checked`() {
        assertEquals("https://example.org/page", normaliseSite("example.org/page"))
        assertEquals("http://exemple.fr", normaliseSite("http://exemple.fr"))
        assertNull(normaliseSite("localhost"))
        assertNull(normaliseSite("javascript:alert(1)"))
        assertNull(normaliseSite("a b.com"))
        assertTrue(validCoinId("bitcoin"))
        assertFalse(validCoinId("Bitcoin!"))
        assertTrue(statusMeansUp(200))
        assertTrue(statusMeansUp(403))
        assertFalse(statusMeansUp(500))
        assertFalse(statusMeansUp(404))
    }

    @Test
    fun `watches are stored added removed and updated`() {
        val store = WatchStore(java.io.File(folder.newFolder(), "watches.json"))
        assertTrue(store.load().isEmpty())
        val a = store.add(bitcoinAbove.copy(id = 0))
        val b = store.add(Watch(0, KIND_SITE, "https://example.org"))
        assertEquals(listOf(1, 2), listOf(a.id, b.id))
        store.replace(listOf(b.copy(triggered = true)))
        assertTrue(store.load().first { it.id == 2 }.triggered)
        assertFalse(store.load().first { it.id == 1 }.triggered)
        assertTrue(store.remove(1))
        assertFalse(store.remove(1))
        assertEquals(listOf(2), store.load().map { it.id })
    }

    @Test
    fun `there is a limit to the number of watches`() {
        val store = WatchStore(java.io.File(folder.newFolder(), "watches.json"))
        repeat(MAX_WATCHES) { store.add(Watch(0, KIND_TEMPERATURE, "", 40.0)) }
        try { store.add(Watch(0, KIND_TEMPERATURE, "", 40.0)); throw AssertionError("should refuse") } catch (_: IllegalArgumentException) { }
    }
}
