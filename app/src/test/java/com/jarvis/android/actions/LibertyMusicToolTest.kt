package com.jarvis.android.actions

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibertyMusicToolTest {
    @Test
    fun `control words map to media keys`() {
        assertEquals(KeyEvent.KEYCODE_MEDIA_NEXT, libertyKeyCode("Next"))
        assertEquals(KeyEvent.KEYCODE_MEDIA_PREVIOUS, libertyKeyCode("précédent"))
        assertEquals(KeyEvent.KEYCODE_MEDIA_PAUSE, libertyKeyCode(" pause "))
        assertNull(libertyKeyCode("volume"))
    }

    @Test
    fun `only youtube music links Liberty declares are accepted`() {
        assertTrue(isLibertyLink("https://music.youtube.com/watch?v=abc"))
        assertTrue(isLibertyLink("https://music.youtube.com/playlist?list=PL1"))
        assertTrue(isLibertyLink("https://youtu.be/abc"))
        assertFalse(isLibertyLink("https://music.youtube.com/search?q=daft"))
        assertFalse(isLibertyLink("http://music.youtube.com/watch?v=abc"))
        assertFalse(isLibertyLink("https://evil.example/watch?v=abc"))
        assertFalse(isLibertyLink("intent://x#Intent;end"))
    }
}
