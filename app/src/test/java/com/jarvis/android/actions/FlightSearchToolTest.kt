package com.jarvis.android.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FlightSearchToolTest {
    @Test
    fun `query combines cities date and price intent`() {
        assertEquals(
            "vol Paris New York 12 décembre prix billet",
            buildFlightQuery("Paris", "New York", "12 décembre"),
        )
        assertEquals(
            "vol Paris Tokyo prix billet",
            buildFlightQuery("Paris", "Tokyo", ""),
        )
    }

    @Test
    fun `flights url encodes accents and spaces`() {
        val url = buildGoogleFlightsUrl("vol Paris New York 12 décembre prix billet")
        assertTrue(url.startsWith("https://www.google.com/travel/flights?q="))
        assertTrue(url.contains("d%C3%A9cembre"))
        assertTrue(!url.contains(" "))
    }
}
