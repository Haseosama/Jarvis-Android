package com.jarvis.android.offline

import com.jarvis.android.timers.TimerDurations
import java.text.Normalizer
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * What Jarvis understands without the network: a fixed set of spoken commands (in French), matched with rules, each one becoming a call to
 * a tool that already exists (so the safeguards of those tools, such as the confirmation of the volume, still apply) or a short answer
 * worked out on the phone. Nothing is guessed: what is not recognised is said so, with a reminder of what can be asked.
 */

internal sealed interface OfflineAction {
    /**
     * A call to the tool [name]. [say] is what is said when it worked; [format] can build it from the tool's answer instead.
     */
    data class ToolCall(val name: String, val args: Map<String, String>, val say: String, val format: ((String) -> String)? = null) : OfflineAction

    /** An answer that needs no tool. [end] closes the session after it. */
    data class Say(val text: String, val end: Boolean = false) : OfflineAction

    /** Nothing recognised. */
    data object Unknown : OfflineAction
}

/** Lower case, no accents, no punctuation, single spaces: "Éteins  la lampe !" becomes "eteins la lampe". */
internal fun normalize(text: String): String {
    val folded = Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
    return folded.replace('’', ' ').replace('\'', ' ').replace(Regex("[^a-z0-9%+ ]"), " ").replace(Regex("\\s+"), " ").trim()
}

internal const val OFFLINE_HELP =
    "Sans connexion, je sais ouvrir une application, appeler un contact, régler le volume ou la luminosité, allumer la lampe, " +
        "gérer la musique, lancer un minuteur, dire l’heure, la date et la batterie, ouvrir les réglages, verrouiller l’écran " +
        "et faire une capture d’écran."

private val END = Regex("^(au revoir|a plus|a plus tard|a bientot|bonne nuit|stop|arrete|arrete toi|termine|c est tout|mets toi en veille|en veille)( jarvis)?$")
private val END_IN = Regex("(arrete|ferme|termine|coupe) (la )?session|mets toi en veille|au revoir")
private val THANKS = Regex("^(merci|merci beaucoup|merci jarvis)$")
private val HELP = Regex("^(aide|de l aide|help|que sais tu faire|que peux tu faire|qu est ce que tu sais faire|qu est ce que tu peux faire|tu sais faire quoi)")
private val TIME = Regex("quelle heure|l heure qu il est|donne moi l heure|il est quelle heure")
private val DATE = Regex("quel jour|quelle est la date|la date d aujourd hui|on est quel jour|on est le combien|quelle date")
private val BATTERY = Regex("batterie|niveau de charge|combien de charge")
private val LAMP_ON = Regex("^(allume|active|mets|met) (la |ma )?(lampe torche|lampe|torche|lumiere|flash)")
private val LAMP_OFF = Regex("^(eteins|eteint|coupe|desactive|arrete) (la |ma )?(lampe torche|lampe|torche|lumiere|flash)")
private val VOLUME = Regex("volume (?:a |sur |de )?(\\d{1,3}) ?(?:%|pour cent|pourcent)?|(\\d{1,3}) ?(?:%|pour cent|pourcent) (?:de )?volume")
private val VOLUME_REL = Regex("(monte|augmente|baisse|diminue|reduis) (le )?(son|volume)")
private val BRIGHTNESS = Regex("luminosite (?:a |sur |de )?(\\d{1,3})|(\\d{1,3}) ?(?:%|pour cent|pourcent) (?:de )?luminosite")
private val MEDIA_PAUSE = Regex("en pause|^pause$|^(stop|arrete|coupe) (la )?(musique|lecture|video|morceau|chanson|son)")
private val MEDIA_PLAY = Regex("^(reprends|reprend|relance|joue|lance|remets|mets) (la )?(musique|lecture|video|morceau|chanson)|^(lecture|play)$")
private val MEDIA_NEXT = Regex("suivant|prochain|passe (le |ce )?(morceau|titre|chanson)|next")
private val MEDIA_PREV = Regex("precedent|morceau d avant|reviens (au|sur le) (morceau|titre)|previous")
private val LOCK = Regex("verrouille|bloque (l ecran|le telephone)")
private val SHOT = Regex("capture d ecran|screenshot|prends une capture")
private val SETTINGS = Regex("^(ouvre|ouvrir|affiche|va dans|va sur|montre) (les |le |la |l )?(reglages|reglage|parametres|parametre)( (?:du|de la|de l|des|d))? ?(.*)$")
private val WIFI = Regex("^(ouvre|ouvrir|active|affiche) (le |les )?(wifi|wi fi)$")
private val TIMER = Regex("(?:minuteur|minuterie|chronometre|timer|compte a rebours)(?: de| d| pour)? (.+)$")
private val SET_TIMER = Regex("(?:mets|met|lance|demarre|programme|regle|fais) (?:un |une |le )?(?:minuteur|minuterie|chronometre|timer|compte a rebours)(?: de| d| pour)? (.+)$")
private val CALL = Regex("^(?:appelle|appeler|telephone a|passe un appel a|contacte|joins|phone a) (.+)$")
private val SMS = Regex("^(?:ecris|envoie|redige) (?:un |une )?(?:sms|message|texto)(?: a| pour)? (.+)$")
private val OPEN = Regex("^(?:ouvre|ouvrir|lance|demarre|va sur|va dans|lancer|ouvre moi) (?:l application |l appli |l app |le |la |les |l |mon |ma |mes )?(.+)$")

private val SETTINGS_PAGES = mapOf(
    "wifi" to "wifi", "wi fi" to "wifi", "bluetooth" to "bluetooth", "mode avion" to "airplane", "avion" to "airplane",
    "batterie" to "battery", "son" to "sound", "sons" to "sound", "audio" to "sound", "affichage" to "display", "ecran" to "display",
    "luminosite" to "display", "localisation" to "location", "position" to "location", "applications" to "apps", "applis" to "apps",
    "stockage" to "storage", "memoire" to "storage", "nfc" to "nfc", "date" to "date", "heure" to "date", "langue" to "language",
    "langues" to "language", "accessibilite" to "accessibility", "securite" to "security", "reseau" to "network", "internet" to "network",
)

private val PAGE_NAMES = mapOf(
    "wifi" to "du Wi-Fi", "bluetooth" to "du Bluetooth", "airplane" to "du mode avion", "battery" to "de la batterie", "sound" to "du son",
    "display" to "de l’affichage", "location" to "de la localisation", "apps" to "des applications", "storage" to "du stockage",
    "nfc" to "du NFC", "date" to "de la date et de l’heure", "language" to "de la langue", "accessibility" to "de l’accessibilité",
    "security" to "de la sécurité", "network" to "du réseau",
)

/** Turns what was said into an action. [now] is a parameter so the time and the date can be tested. */
internal fun interpret(raw: String, now: LocalDateTime = LocalDateTime.now()): OfflineAction {
    val n = normalize(raw)
    if (n.isEmpty()) return OfflineAction.Unknown

    if (THANKS.containsMatchIn(n)) return OfflineAction.Say("Je vous en prie.")
    if (HELP.containsMatchIn(n)) return OfflineAction.Say(OFFLINE_HELP)

    // the music before "stop", which alone ends the session
    MEDIA_PAUSE.find(n)?.let { return media("pause", "Musique en pause.") }
    if (END.containsMatchIn(n) || END_IN.containsMatchIn(n)) return OfflineAction.Say("À bientôt.", end = true)

    if (TIME.containsMatchIn(n)) {
        val h = now.hour; val m = now.minute
        return OfflineAction.Say(if (m == 0) "Il est $h heures pile." else "Il est $h heures $m.")
    }
    if (DATE.containsMatchIn(n)) return OfflineAction.Say("Nous sommes le " + now.format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.FRENCH)) + ".")
    if (BATTERY.containsMatchIn(n)) {
        return OfflineAction.ToolCall("system_monitor", emptyMap(), "", ::batterySentence)
    }

    LAMP_OFF.find(n)?.let { return OfflineAction.ToolCall("device_settings", mapOf("action" to "flashlight", "value" to "0"), "J’éteins la lampe.") }
    LAMP_ON.find(n)?.let { return OfflineAction.ToolCall("device_settings", mapOf("action" to "flashlight", "value" to "1"), "J’allume la lampe.") }

    VOLUME.find(n)?.let { m ->
        val v = (m.groupValues[1].ifEmpty { m.groupValues[2] }).toIntOrNull()
        if (v == null || v > 100) return OfflineAction.Say("Dites un volume entre 0 et 100, par exemple : volume à 50.")
        return OfflineAction.ToolCall("device_settings", mapOf("action" to "set_volume", "value" to "$v"), "Volume à $v pour cent.")
    }
    if (VOLUME_REL.containsMatchIn(n)) return OfflineAction.Say("Dites un volume précis, par exemple : volume à 50.")
    BRIGHTNESS.find(n)?.let { m ->
        val v = (m.groupValues[1].ifEmpty { m.groupValues[2] }).toIntOrNull()
        if (v == null || v > 100) return OfflineAction.Say("Dites une luminosité entre 0 et 100, par exemple : luminosité à 60.")
        return OfflineAction.ToolCall("device_settings", mapOf("action" to "set_brightness", "value" to "$v"), "Luminosité à $v pour cent.")
    }

    if (MEDIA_PLAY.containsMatchIn(n)) return media("play", "Lecture.")
    if (MEDIA_NEXT.containsMatchIn(n) && !n.contains("ouvre")) return media("next", "Morceau suivant.")
    if (MEDIA_PREV.containsMatchIn(n)) return media("previous", "Morceau précédent.")

    if (LOCK.containsMatchIn(n)) return OfflineAction.ToolCall("device_settings", mapOf("action" to "lock_screen"), "J’ai verrouillé l’écran.")
    if (SHOT.containsMatchIn(n)) return OfflineAction.ToolCall("device_settings", mapOf("action" to "take_screenshot"), "Capture d’écran demandée.")

    val timerText = (SET_TIMER.find(n) ?: TIMER.find(n))?.groupValues?.get(1)
    if (timerText != null) {
        val seconds = try { TimerDurations.parse(timerText) } catch (_: IllegalArgumentException) { null }
            ?: return OfflineAction.Say("Je n’ai pas compris la durée. Dites par exemple : minuteur de 10 minutes.")
        return OfflineAction.ToolCall("timer", mapOf("action" to "create", "duration" to timerText), "Minuteur de ${TimerDurations.format(seconds)} lancé.")
    }

    WIFI.find(n)?.let { return settings("wifi") }
    SETTINGS.find(n)?.let { m ->
        val what = m.groupValues[5].trim()
        val page = SETTINGS_PAGES[what] ?: SETTINGS_PAGES[what.removePrefix("du ").removePrefix("de la ").removePrefix("de l ").trim()]
        return if (page != null) settings(page) else OfflineAction.Say("Quels réglages ? Par exemple : réglages du Wi-Fi, du Bluetooth ou de la batterie.")
    }

    CALL.find(n)?.let { m ->
        val who = m.groupValues[1].removePrefix("mon ").removePrefix("ma ").removePrefix("mes ").trim()
        if (who.isNotEmpty()) return OfflineAction.ToolCall("call_contact", mapOf("name" to who, "action" to "call"), "J’ouvre le numéroteur pour $who.")
    }
    SMS.find(n)?.let { m ->
        val who = m.groupValues[1].trim()
        if (who.isNotEmpty()) return OfflineAction.ToolCall("call_contact", mapOf("name" to who, "action" to "sms"), "J’ouvre un SMS pour $who.")
    }
    OPEN.find(n)?.let { m ->
        val app = m.groupValues[1].trim()
        if (app.isNotEmpty()) return OfflineAction.ToolCall("open_app", mapOf("app_name" to app), "J’ouvre $app.")
    }
    return OfflineAction.Unknown
}

private fun media(command: String, say: String) = OfflineAction.ToolCall("device_settings", mapOf("action" to "media", "command" to command), say)

private fun settings(page: String) =
    OfflineAction.ToolCall("device_settings", mapOf("action" to "open_settings", "page" to page), "J’ouvre les réglages ${PAGE_NAMES[page] ?: ""}".trim() + ".")

/** "Battery: 45% (charging). Storage: …" (the tool's answer) as a spoken sentence. */
internal fun batterySentence(result: String): String {
    val m = Regex("Battery: (\\d+)%").find(result) ?: return "Je n’ai pas pu lire la batterie."
    val charging = result.contains("(charging)")
    return "La batterie est à ${m.groupValues[1]} pour cent" + (if (charging) ", en charge." else ".")
}

private val FAILURE = Regex("failed|not available|not found|no app matching|introuvable|impossible|aucun|erreur|error|refus|denied|permission|pas autoris|n'est pas|n’est pas|expir|inchang", RegexOption.IGNORE_CASE)

/** What to say after a tool ran: the planned sentence, or an honest failure with what the tool said. */
internal fun spokenResult(action: OfflineAction.ToolCall, result: String): String {
    Regex("No app matching '(.+)' is installed").find(result)?.let { return "Je ne trouve pas d’application « ${it.groupValues[1]} »." }
    if (FAILURE.containsMatchIn(result)) return "Je n’ai pas pu le faire. " + result.take(200)
    return action.format?.invoke(result) ?: action.say
}
