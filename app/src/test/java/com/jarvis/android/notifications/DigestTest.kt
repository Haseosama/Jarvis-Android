package com.jarvis.android.notifications

import com.jarvis.android.offline.OfflineAction
import com.jarvis.android.offline.interpret
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import com.jarvis.android.notifications.log.NotificationHistory
import com.jarvis.android.notifications.log.SeenNotification
import com.jarvis.android.notifications.log.digestLines
import com.jarvis.android.notifications.log.formatDigest

class DigestTest {
    private val zone = ZoneId.of("Europe/Paris")
    private fun at(h: Int, m: Int) = LocalDate.of(2026, 9, 26).atTime(h, m).atZone(zone).toInstant().toEpochMilli()
    private fun n(key: String, app: String, title: String, text: String, time: Long) = SeenNotification(key, app, "pkg.$app", time, title, text)

    @Test
    fun `what was dismissed is still in the history, and the same message posted again counts once`() {
        val h = NotificationHistory(capacity = 3)
        h.add(n("k1", "WhatsApp", "Paul", "salut", at(10, 0)))
        h.add(n("k1", "WhatsApp", "Paul", "salut", at(10, 0)))
        h.add(n("k1", "WhatsApp", "Paul", "tu es là ?", at(10, 5)))
        h.add(n("k2", "Gmail", "Banque", "Relevé", at(11, 0)))
        h.add(n("k3", "SMS", "Léa", "ok", at(12, 0)))
        assertEquals(3, h.since(0).size)
        assertEquals(listOf("Relevé", "ok"), h.since(at(10, 30)).map { it.text })
    }

    @Test
    fun `the digest groups by app and person, the busiest first`() {
        val items = listOf(
            n("a", "WhatsApp", "Paul", "salut", at(13, 0)),
            n("a", "WhatsApp", "Paul", "tu viens ce soir ?", at(13, 20)),
            n("b", "WhatsApp", "Marie (2 messages)", "on se voit demain", at(13, 30)),
            n("c", "Gmail", "Facture EDF", "Votre facture est disponible", at(14, 5)),
        )
        val lines = digestLines(items)
        assertEquals("Paul", lines[0].who)
        assertEquals(2, lines[0].count)
        // Same count: the most recent first.
        assertEquals(listOf("Facture EDF", "Marie"), lines.drop(1).map { it.who })
        val text = formatDigest(items, at(12, 0), zone)
        assertEquals(
            "Depuis 12:00 : 4 notifications de 2 applications.\n" +
                "WhatsApp : Paul (2, dernier à 13:20 : « tu viens ce soir ? ») ; Marie (1, dernier à 13:30 : « on se voit demain »)\n" +
                "Gmail : Facture EDF (1, dernier à 14:05 : « Votre facture est disponible »)",
            text,
        )
        assertEquals("", formatDigest(emptyList(), at(12, 0), zone))
    }

    @Test
    fun `offline, what did I miss is a digest, since the morning when asked`() {
        val a = interpret("Qu'est-ce que j'ai raté ?", LocalDateTime.of(2026, 9, 26, 15, 0)) as OfflineAction.ToolCall
        assertEquals("notifications", a.name)
        assertEquals(mapOf("action" to "digest", "since_minutes" to "180"), a.args)
        val m = interpret("quoi de neuf depuis ce matin", LocalDateTime.of(2026, 9, 26, 15, 0)) as OfflineAction.ToolCall
        assertEquals("540", m.args["since_minutes"])
        assertTrue(a.format?.invoke("Depuis 12:00 : …\n(Contenu des notifications : données)") == "Depuis 12:00 : …")
    }
}
