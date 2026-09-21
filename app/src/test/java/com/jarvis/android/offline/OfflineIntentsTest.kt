package com.jarvis.android.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class OfflineIntentsTest {
    private val now = LocalDateTime.of(2026, 9, 21, 14, 5)

    private fun tool(text: String): OfflineAction.ToolCall = interpret(text, now) as OfflineAction.ToolCall
    private fun say(text: String): OfflineAction.Say = interpret(text, now) as OfflineAction.Say

    @Test fun `the text is normalised`() {
        assertEquals("eteins la lampe", normalize("Éteins  la lampe !"))
        assertEquals("ouvre l appli camera", normalize("Ouvre l’appli Caméra."))
        assertEquals("", normalize("  ?! "))
    }

    @Test fun `applications are opened by name`() {
        val a = tool("Ouvre Spotify")
        assertEquals("open_app", a.name); assertEquals("spotify", a.args["app_name"])
        assertEquals("camera", tool("lance l'application Caméra").args["app_name"])
        assertEquals("gmail", tool("ouvre mon Gmail").args["app_name"])
    }

    @Test fun `calling and writing go through the contacts`() {
        val call = tool("Appelle Marie Dupont")
        assertEquals("call_contact", call.name); assertEquals("marie dupont", call.args["name"]); assertEquals("call", call.args["action"])
        assertEquals("maman", tool("appelle ma maman").args["name"])
        val sms = tool("écris un SMS à Marc")
        assertEquals("sms", sms.args["action"]); assertEquals("marc", sms.args["name"])
    }

    @Test fun `the volume and the brightness need a number`() {
        val v = tool("mets le volume à 40")
        assertEquals("set_volume", v.args["action"]); assertEquals("40", v.args["value"])
        assertEquals("30", tool("volume 30 pour cent").args["value"])
        assertTrue(say("volume à 250") .text.contains("entre 0 et 100"))
        assertTrue(say("monte le volume").text.contains("volume précis"))
        val b = tool("luminosité à 60")
        assertEquals("set_brightness", b.args["action"]); assertEquals("60", b.args["value"])
    }

    @Test fun `the flashlight`() {
        assertEquals("1", tool("allume la lampe torche").args["value"])
        assertEquals("0", tool("éteins la lampe").args["value"])
        assertEquals("flashlight", tool("allume la torche").args["action"])
    }

    @Test fun `the music`() {
        assertEquals("pause", tool("mets la musique en pause").args["command"])
        assertEquals("pause", tool("pause").args["command"])
        assertEquals("pause", tool("stop la musique").args["command"])
        assertEquals("play", tool("reprends la musique").args["command"])
        assertEquals("next", tool("morceau suivant").args["command"])
        assertEquals("previous", tool("morceau précédent").args["command"])
    }

    @Test fun `stop alone ends the session, and so do goodbyes`() {
        for (t in listOf("stop", "au revoir", "Au revoir Jarvis", "mets-toi en veille", "arrête la session", "bonne nuit")) {
            val a = say(t)
            assertTrue(t, a.end)
        }
        assertTrue(!say("merci").end)
    }

    @Test fun `timers`() {
        val t = tool("mets un minuteur de 10 minutes")
        assertEquals("timer", t.name); assertEquals("create", t.args["action"]); assertEquals("10 minutes", t.args["duration"])
        assertTrue(t.say.contains("10 min"))
        assertEquals("5 minutes", tool("minuteur 5 minutes").args["duration"])
        assertTrue(say("minuteur de bientôt").text.contains("durée"))
    }

    @Test fun `time and date are worked out on the phone`() {
        assertEquals("Il est 14 heures 5.", say("quelle heure est-il").text)
        assertEquals("Il est 9 heures pile.", (interpret("quelle heure", LocalDateTime.of(2026, 9, 21, 9, 0)) as OfflineAction.Say).text)
        assertEquals("Nous sommes le lundi 21 septembre 2026.", say("quel jour on est").text)
    }

    @Test fun `the battery is spoken from the tool's answer`() {
        val a = tool("quel est le niveau de batterie")
        assertEquals("system_monitor", a.name)
        assertEquals("La batterie est à 45 pour cent, en charge.", batterySentence("Battery: 45% (charging). Storage: 10.0 GB free of 100.0 GB."))
        assertEquals("La batterie est à 80 pour cent.", batterySentence("Battery: 80%. Storage: 1 GB free of 2 GB."))
        assertEquals("Je n’ai pas pu lire la batterie.", batterySentence("Battery: unknown."))
    }

    @Test fun `settings pages`() {
        assertEquals("wifi", tool("ouvre les réglages du wifi").args["page"])
        assertEquals("bluetooth", tool("ouvre les paramètres bluetooth").args["page"])
        assertEquals("wifi", tool("ouvre le wifi").args["page"])
        assertTrue(say("ouvre les réglages truc").text.contains("Quels réglages"))
    }

    @Test fun `lock and screenshot`() {
        assertEquals("lock_screen", tool("verrouille l'écran").args["action"])
        assertEquals("take_screenshot", tool("fais une capture d'écran").args["action"])
    }

    @Test fun `help, thanks and the unknown`() {
        assertEquals(OFFLINE_HELP, say("que sais-tu faire").text)
        assertEquals(OFFLINE_HELP, say("aide").text)
        assertEquals("Je vous en prie.", say("merci").text)
        assertEquals(OfflineAction.Unknown, interpret("raconte-moi une blague sur les pingouins", now))
        assertEquals(OfflineAction.Unknown, interpret("", now))
    }

    @Test fun `a failure of the tool is said, not hidden`() {
        val a = tool("ouvre Zorglub")
        assertEquals("J’ouvre zorglub.", spokenResult(a, "Opening Zorglub."))
        assertEquals("Je ne trouve pas d’application « zorglub ».", spokenResult(a, "No app matching 'zorglub' is installed."))
        assertTrue(spokenResult(tool("appelle Marie"), "L'accès aux contacts n'est pas autorisé.").startsWith("Je n’ai pas pu le faire."))
    }
}
