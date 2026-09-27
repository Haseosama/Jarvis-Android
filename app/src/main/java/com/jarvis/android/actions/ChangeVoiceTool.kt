package com.jarvis.android.actions

import com.jarvis.android.JarvisContainer
import com.jarvis.android.core.JarvisState
import com.jarvis.android.core.SessionTrigger
import com.jarvis.android.core.SpokenAlert
import com.jarvis.android.memory.ConfigStore
import com.jarvis.android.memory.Voice
import com.jarvis.android.offline.normalize
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject

/**
 * Changes the assistant's voice by voice: a voice named ("la voix Leda"), or one of a kind ("une voix féminine", "une voix masculine
 * grave", "une autre voix douce": the next matching one after the current, so asking again goes through them). A live session takes a
 * voice only when it opens: the session is closed after a short word and opened again at once, and the new voice introduces itself.
 */
object ChangeVoiceTool : Tool {
    override val name = "change_voice"
    override val description =
        "Changer la voix de l’assistant (voix de Gemini) quand l’utilisateur le demande : une voix nommée (voice, par exemple « Leda »), " +
            "ou un genre (gender : « female » ou « male ») et/ou un caractère (style : « douce », « chaleureuse », « jeune », « grave »…). " +
            "Sans nom, c’est la voix suivante qui correspond, donc redemander en donne une autre. list = « true » pour énumérer les voix. " +
            "Pendant une session vocale, la session redémarre aussitôt avec la nouvelle voix : dites seulement, en une phrase courte, que vous changez de voix."
    override val parameters = objectSchema {
        string("voice", "Nom d’une voix Gemini (Kore, Aoede, Leda, Zephyr, Puck, Charon…), si l’utilisateur en nomme une.")
        string("gender", "'female' ou 'male', si l’utilisateur demande une voix féminine ou masculine.")
        string("style", "Un mot du caractère voulu, dans la langue de l’utilisateur (douce, chaleureuse, jeune, claire, ferme, posée…).")
        string("list", "'true' pour obtenir la liste des voix, sans rien changer.")
    }

    override suspend fun run(args: JsonObject, ctx: JarvisContainer): String {
        if (args.stringArg("list").trim().lowercase() in setOf("true", "oui", "yes", "1")) {
            return "Voix disponibles : " + ConfigStore.VOICES.joinToString(" ; ") { it.label(false) } +
                ". Elles s’écoutent aussi dans les réglages (Voix, bouton ▶)."
        }
        val current = ctx.configStore.snapshotVoice()
        val chosen = pickVoice(ConfigStore.VOICES, current, args.stringArg("voice"), args.stringArg("gender"), args.stringArg("style"))
            ?: return "Aucune voix ne correspond à cette demande. " +
                "Voix disponibles : " + ConfigStore.VOICES.joinToString(", ") { it.name } + ". Rien n’est changé."
        if (chosen.name == current) return "C’est déjà la voix utilisée (${chosen.label(false)}). Rien n’est changé."
        ctx.configStore.setVoice(chosen.name)
        val engine = ctx.engine
        val live = engine.state.value != JarvisState.ASLEEP && engine.state.value != JarvisState.ERROR
        if (!live) return "Voix ${chosen.label(false)} choisie : elle sera utilisée dès la prochaine réponse parlée."
        // the voice is part of how a session is opened: close it after the short word, open it again, and let the new voice speak
        engine.requestEndSession {
            ctx.appScope.launch {
                delay(400)
                engine.start(SessionTrigger.APP_BUTTON)
                withTimeoutOrNull(15_000) { while (!engine.sessionReady.value) delay(200) }
                if (engine.sessionReady.value) {
                    engine.announce(SpokenAlert.sessionAnnouncement(
                        if (com.jarvis.android.i18n.Lang.isEnglish) "This is my new voice. What do you think?" else "Voici ma nouvelle voix. Qu’en pensez-vous ?"))
                }
            }
        }
        return "Voix ${chosen.label(false)} choisie. La session redémarre avec elle dans un instant : dites seulement, en une phrase courte " +
            "et dans la langue de l’utilisateur, que vous changez de voix."
    }
}

/**
 * The voice asked for: the one named (case and accents aside), else among those of the [gender] and [style] asked (a style word matched
 * in French or English, as a prefix, so "chaleureux" finds "chaleureuse"), the first one after [current] in the list, so asking again
 * gives another; null when nothing matches.
 */
internal fun pickVoice(voices: List<Voice>, current: String, name: String, gender: String, style: String): Voice? {
    val n = normalize(name)
    if (n.isNotEmpty()) {
        voices.firstOrNull { normalize(it.name) == n }?.let { return it }
        voices.firstOrNull { n.contains(normalize(it.name)) }?.let { return it }
    }
    val g = normalize(gender)
    val wantFemale = when {
        g.startsWith("f") || g.startsWith("wom") || g.contains("femin") -> true
        g.startsWith("m") || g.startsWith("hom") || g.contains("mascul") -> false
        else -> null
    }
    val s = normalize(style).take(6)
    val deep = s.startsWith("grave") || s.startsWith("deep")
    val matching = voices.filter { v ->
        (wantFemale == null || v.female == wantFemale) &&
            (s.isEmpty() || deep || normalize(v.styleFr).startsWith(s) || normalize(v.styleEn).startsWith(s))
    }.let { list -> if (deep) list.filter { !it.female } else list }
    if (matching.isEmpty()) return null
    val at = matching.indexOfFirst { it.name == current }
    return matching[(at + 1) % matching.size]
}
