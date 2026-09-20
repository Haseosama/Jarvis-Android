package com.jarvis.android.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareInboxTest {
    @Test
    fun `text is trimmed and capped, nothing shared clears the inbox`() {
        val inbox = ShareInbox()
        inbox.offer("  bonjour  ", null)
        assertEquals("bonjour", inbox.current.value!!.text)
        inbox.offer("x".repeat(ShareInbox.MAX_SHARED_CHARS + 500), null)
        assertEquals(ShareInbox.MAX_SHARED_CHARS, inbox.current.value!!.text!!.length)
        inbox.offer("   ", null)
        assertNull(inbox.current.value)
    }

    @Test
    fun `a file alone is enough and clear empties it`() {
        val inbox = ShareInbox()
        inbox.offer(null, "photo.jpg")
        assertEquals("photo.jpg", inbox.current.value!!.fileName)
        inbox.clear()
        assertNull(inbox.current.value)
    }

    @Test
    fun `prompt carries the shared text after the instruction`() {
        val prompt = sharePrompt(ShareAction.SUMMARIZE, Shared("Le texte", null))!!
        assertTrue(prompt.startsWith("Résume"))
        assertTrue(prompt.endsWith("Le texte"))
    }

    @Test
    fun `prompt for a file names the action and text plus file mentions both`() {
        assertTrue(sharePrompt(ShareAction.TRANSLATE, Shared(null, "a.pdf"))!!.contains("fichier joint"))
        assertTrue(sharePrompt(ShareAction.EXPLAIN, Shared("t", "a.pdf"))!!.contains("fichier joint"))
    }

    @Test
    fun `nothing shared gives no prompt`() {
        assertNull(sharePrompt(ShareAction.EXPLAIN, Shared(null, null)))
    }
}
