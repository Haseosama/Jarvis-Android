package com.jarvis.android.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class NotificationLogTest {
    private fun n(key: String, app: String = "WhatsApp", title: String = "Paul", text: String = "Salut", at: Long = 0L) =
        SeenNotification(key, app, "pkg.$app", at, title, text)

    @Test
    fun `newest come first and a reposted notification replaces its old entry`() {
        val log = NotificationLog()
        log.add(n("a", text = "un"))
        log.add(n("b", text = "deux"))
        log.add(n("a", text = "trois"))
        assertEquals(listOf("trois", "deux"), log.recent(10).map { it.text })
    }

    @Test
    fun `the log is capped and can be filtered by app and limit`() {
        val log = NotificationLog(capacity = 3)
        (1..5).forEach { log.add(n("k$it", app = if (it % 2 == 0) "Gmail" else "WhatsApp", text = "m$it")) }
        assertEquals(3, log.recent(10).size)
        assertEquals(listOf("m4"), log.recent(10, "gmail").map { it.text })
        assertEquals(1, log.recent(1).size)
    }

    @Test
    fun `removing and clearing work`() {
        val log = NotificationLog()
        log.add(n("a"))
        log.add(n("b"))
        log.remove("a")
        assertEquals(1, log.recent(10).size)
        log.clear()
        assertTrue(log.recent(10).isEmpty())
    }

    @Test
    fun `messages with a one-time code or a password are masked`() {
        assertEquals("[message contenant un code, masqué]", redactSensitive("Banque", "Votre code de vérification est 482 913").second)
        assertEquals("[message contenant un code, masqué]", redactSensitive("Your OTP", "Use 123456 to sign in").second)
        assertEquals("On dîne à 20 h ?", redactSensitive("Paul", "On dîne à 20 h ?").second)
        // a number alone is not a secret, a word alone neither
        assertEquals("Colis n°12345678 livré", redactSensitive("Poste", "Colis n°12345678 livré").second)
        assertEquals("Le code postal a changé", redactSensitive("Poste", "Le code postal a changé").second)
    }

    @Test
    fun `lines show time, app and content and control characters are cleaned`() {
        val text = formatNotifications(listOf(n("a", title = "Paul", text = "Salut", at = 3_600_000L * 14)), ZoneOffset.UTC)
        assertEquals("14:00 WhatsApp — Paul : Salut", text)
        assertEquals("ab c", clean("ab\nc", 20))
    }
}
