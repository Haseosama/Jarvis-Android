package com.jarvis.android.messaging

import com.jarvis.android.device.MESSAGING_PACKAGES
import com.jarvis.android.device.ScreenElement
import com.jarvis.android.device.confirmationReason
import com.jarvis.android.device.isSendLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MessageSendingTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun element(label: String) = ScreenElement(index = 0, label = label, role = "bouton", clickable = true, left = 0, top = 0, right = 10, bottom = 10)

    // ── the limit ──────────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `only a few messages can go out in ten minutes`() {
        val limiter = SendLimiter(max = 3, windowMs = 600_000)
        val t = 1_000_000L
        assertTrue(limiter.tryAcquire(t)); assertTrue(limiter.tryAcquire(t + 1_000)); assertTrue(limiter.tryAcquire(t + 2_000))
        assertFalse(limiter.tryAcquire(t + 3_000))
        assertEquals(10, limiter.minutesToWait(t + 3_000))          // the first one leaves the window in 10 minutes less 3 s, rounded up
        assertEquals(1, limiter.minutesToWait(t + 599_000))
        assertTrue(limiter.tryAcquire(t + 600_500))                  // the first one is out of the window
        assertEquals(0, SendLimiter().minutesToWait())
    }

    @Test fun `a refused message does not use up the allowance`() {
        val limiter = SendLimiter(max = 1, windowMs = 60_000)
        assertTrue(limiter.tryAcquire(0))
        repeat(5) { assertFalse(limiter.tryAcquire(10)) }
        assertTrue(limiter.tryAcquire(60_000))
    }

    @Test fun `a message that did not go out gives its place back`() {
        val limiter = SendLimiter(max = 2, windowMs = 60_000)
        assertTrue(limiter.tryAcquire(0)); limiter.giveBack()
        assertTrue(limiter.tryAcquire(1)); assertTrue(limiter.tryAcquire(2))
        assertFalse(limiter.tryAcquire(3))
        limiter.giveBack()
        assertTrue(limiter.tryAcquire(4))
        SendLimiter().giveBack()                                     // nothing to give back is fine
    }

    // ── numbers for WhatsApp's links ───────────────────────────────────────────────────────────────────────────────────────

    @Test fun `numbers become digits with their country code`() {
        assertEquals("33612345678", internationalDigits("+33 6 12 34 56 78", null))
        assertEquals("33612345678", internationalDigits("0033612345678", null))
        assertEquals("33612345678", internationalDigits("06 12 34 56 78", "fr"))
        assertEquals("32475123456", internationalDigits("0475 12 34 56", "BE"))
        assertEquals("33612345678", internationalDigits("33612345678", null))
    }

    @Test fun `a national number of an unknown country and a too short one are refused`() {
        assertNull(internationalDigits("0612345678", null))
        assertNull(internationalDigits("0612345678", "ZZ"))
        assertNull(internationalDigits("123", "FR"))
        assertNull(internationalDigits("", "FR"))
    }

    // ── which button is a send button ──────────────────────────────────────────────────────────────────────────────────────

    @Test fun `send buttons are recognised in both languages`() {
        for (label in listOf("Envoyer", "envoyer", "Send", "Send SMS", "Envoyer un SMS", "  ENVOYER  ", "Send message")) assertTrue(label, isSendLabel(label))
    }

    @Test fun `other buttons and buttons that move money are not send buttons`() {
        for (label in listOf("", "Annuler", "Supprimer", "Payer", "Envoyer de l'argent", "Send money", "Send payment", "Envoyer un paiement", "Resend", "Sender", "Appeler")) {
            assertFalse(label, isSendLabel(label))
        }
    }

    // ── the confirmation is only waived where and for what it should be ────────────────────────────────────────────────────

    @Test fun `by default the send button is asked about, as before`() {
        assertNotNull(confirmationReason("com.whatsapp", element("Envoyer")))
        assertNotNull(confirmationReason("com.whatsapp", element("Envoyer"), allowMessageSend = false))
    }

    @Test fun `with automatic sending the send button of a messaging app is not asked about`() {
        for (pkg in listOf("com.whatsapp", "com.google.android.apps.messaging", "org.telegram.messenger")) {
            assertNull(pkg, confirmationReason(pkg, element("Envoyer"), allowMessageSend = true))
            assertNull(pkg, confirmationReason(pkg, element("Send"), allowMessageSend = true))
        }
    }

    @Test fun `nothing else is waived`() {
        // an email app, a bank, the system screens and the other sensitive words keep their confirmation
        assertNotNull(confirmationReason("com.google.android.gm", element("Envoyer"), allowMessageSend = true))
        assertNotNull(confirmationReason("com.whatsapp", element("Envoyer de l'argent"), allowMessageSend = true))
        assertNotNull(confirmationReason("com.whatsapp", element("Supprimer"), allowMessageSend = true))
        assertNotNull(confirmationReason("com.whatsapp", element("Payer"), allowMessageSend = true))
        assertNotNull(confirmationReason("com.android.settings", element("Envoyer"), allowMessageSend = true))
        assertNotNull(confirmationReason("com.android.systemui", element("Envoyer"), allowMessageSend = true))
        assertTrue("com.android.settings" !in MESSAGING_PACKAGES && "com.google.android.gm" !in MESSAGING_PACKAGES)
    }

    // ── what was sent ──────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `the history keeps the latest and forgets on request`() {
        val history = SentMessages(tmp.newFile("sent.json"), max = 3)
        assertTrue(history.recent(5).isEmpty())
        for (i in 1..5) history.add(SentMessage(i.toLong(), "Marie", "SMS", "message $i"))
        assertEquals(listOf("message 5", "message 4", "message 3"), history.recent(5).map { it.text })
        assertEquals(listOf("message 5", "message 4"), history.recent(2).map { it.text })
        history.clear()
        assertTrue(history.recent(5).isEmpty())
    }

    @Test fun `a long text is shortened in the history and a damaged file is an empty history`() {
        val file = tmp.newFile("sent2.json")
        val history = SentMessages(file)
        history.add(SentMessage(1, "Marc", "WhatsApp", "x".repeat(2000)))
        assertEquals(500, history.recent(1).single().text.length)
        file.writeText("pas du json")
        assertTrue(history.recent(5).isEmpty())
        history.add(SentMessage(2, "Marc", "SMS", "ok"))
        assertEquals("ok", history.recent(1).single().text)
    }
}
