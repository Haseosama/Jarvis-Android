package com.jarvis.android.actions

import com.jarvis.android.watch.KIND_CRYPTO
import com.jarvis.android.watch.KIND_MEMORY
import com.jarvis.android.watch.KIND_SITE
import com.jarvis.android.watch.KIND_TEMPERATURE
import com.jarvis.android.watch.Watch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchToolTest {
    private fun args(vararg pairs: Pair<String, String>): JsonObject = buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } }

    @Test
    fun `a crypto watch needs a CoinGecko id and a price, and goes above unless told below`() {
        val (up, noProblem) = watchFromArgs(args("kind" to "Crypto", "target" to " Bitcoin ", "threshold" to "60000,5"))
        assertNull(noProblem)
        assertEquals(Watch(0, KIND_CRYPTO, "bitcoin", 60000.5, true, ""), up)

        val (down, _) = watchFromArgs(args("kind" to "crypto", "target" to "ethereum", "threshold" to "2000", "direction" to "en dessous"))
        assertEquals(false, down!!.above)

        val (badCoin, why) = watchFromArgs(args("kind" to "crypto", "target" to "bit coin", "threshold" to "1"))
        assertNull(badCoin)
        assertTrue(why!!.contains("CoinGecko"))

        val (noPrice, why2) = watchFromArgs(args("kind" to "crypto", "target" to "bitcoin", "threshold" to "-3"))
        assertNull(noPrice)
        assertEquals("Indiquez le prix seuil en euros.", why2)
    }

    @Test
    fun `a site watch takes a normalised address and refuses a malformed one`() {
        val (site, _) = watchFromArgs(args("kind" to "site", "target" to "example.com", "label" to "Mon blog"))
        assertEquals(Watch(0, KIND_SITE, "https://example.com", 0.0, true, "Mon blog"), site)
        val (bad, why) = watchFromArgs(args("kind" to "site", "target" to "pas une adresse"))
        assertNull(bad)
        assertEquals("Adresse de site invalide.", why)
    }

    @Test
    fun `battery and memory watches have sensible defaults, and an unknown kind is refused`() {
        assertEquals(Watch(0, KIND_TEMPERATURE, "", 42.0, true, ""), watchFromArgs(args("kind" to "temperature")).first)
        assertEquals(Watch(0, KIND_MEMORY, "", 400.0, false, ""), watchFromArgs(args("kind" to "memory")).first)
        assertEquals(45.0, watchFromArgs(args("kind" to "temperature", "threshold" to "45")).first!!.threshold, 0.0)
        val (none, why) = watchFromArgs(args("kind" to "météo"))
        assertNull(none)
        assertNotNull(why)
        assertTrue(why!!.startsWith("Type inconnu"))
    }

    @Test
    fun `the label is kept short`() {
        val (w, _) = watchFromArgs(args("kind" to "memory", "label" to "x".repeat(200)))
        assertEquals(60, w!!.label.length)
    }

    @Test
    fun `values read with French decimals, small ones to the cent`() {
        assertEquals("3,14", formatWatchValue(3.14159))
        assertEquals("42,00", formatWatchValue(42.0))
        assertEquals("150", formatWatchValue(150.4))
    }

    @Test
    fun `each kind of watch is described in words`() {
        assertEquals("bitcoin au-dessus de 0,50 €", describeWatch(Watch(1, KIND_CRYPTO, "bitcoin", 0.5, true)))
        assertEquals("BTC en dessous de 0,50 €", describeWatch(Watch(1, KIND_CRYPTO, "bitcoin", 0.5, false, "BTC")))
        assertEquals("site https://example.com (alerte s’il ne répond plus)", describeWatch(Watch(1, KIND_SITE, "https://example.com")))
        assertEquals("température de la batterie au-dessus de 42,00 °C", describeWatch(Watch(1, KIND_TEMPERATURE, "", 42.0)))
        assertEquals("mémoire libre en dessous de 400 Mo", describeWatch(Watch(1, KIND_MEMORY, "", 400.0, false)))
    }
}
