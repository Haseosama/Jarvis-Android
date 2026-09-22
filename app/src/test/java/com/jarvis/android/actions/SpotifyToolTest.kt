package com.jarvis.android.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyToolTest {
    @Test
    fun `the app uri encodes the query after the fixed search prefix`() {
        val uri = spotifySearchUri("daft punk get lucky")
        assertEquals("spotify:search:daft%20punk%20get%20lucky", uri)
    }

    @Test
    fun `the web fallback points at open dot spotify dot com`() {
        val url = spotifyWebSearchUrl("bohemian rhapsody")
        assertTrue(url!!.startsWith("https://open.spotify.com/search/bohemian"))
    }

    @Test
    fun `a blank or too-long query is refused`() {
        assertNull(spotifySearchUri("   "))
        assertNull(spotifySearchUri("x".repeat(201)))
    }
}
