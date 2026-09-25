package com.jarvis.android.actions

import com.jarvis.android.JarvisContainer
import com.jarvis.android.offline.normalize
import kotlinx.serialization.json.JsonObject

/*
 * Interpreter mode: a conversation between two people who do not share a language, with Jarvis in the middle — every
 * sentence heard in one language is said again in the other, nothing added, until "arrête de traduire". The translating is
 * the Live model's own; this tool only switches the mode on and off and states the rules, in its answer, for the model to
 * follow from the next turn on (a tool's answer stays in the conversation, so the rules survive until it is switched off).
 */

private val LANGUAGE_NAMES = mapOf(
    "anglais" to "anglais", "english" to "anglais", "espagnol" to "espagnol", "spanish" to "espagnol", "allemand" to "allemand",
    "german" to "allemand", "italien" to "italien", "italian" to "italien", "portugais" to "portugais", "arabe" to "arabe",
    "chinois" to "chinois", "mandarin" to "chinois", "japonais" to "japonais", "russe" to "russe", "tagalog" to "tagalog",
    "neerlandais" to "néerlandais", "turc" to "turc", "polonais" to "polonais", "coreen" to "coréen", "francais" to "français",
)

/** A language as said ("English", "l'espagnol") to its French name, or the text itself when unknown (the model knows it). */
internal fun languageName(text: String): String {
    val n = normalize(text).removePrefix("l ").removePrefix("le ").removePrefix("en ").trim()
    return LANGUAGE_NAMES[n] ?: text.trim()
}

/** The rules the model follows while the mode is on. */
internal fun interpreterRules(first: String, second: String): String =
    "MODE INTERPRÈTE ACTIVÉ entre le $first et le $second. À partir du prochain tour et jusqu'à ce qu'on te dise « arrête de traduire », " +
        "« fin de la traduction » ou « stop interprète » : chaque phrase que tu entends en $first, tu la répètes à voix haute en $second ; " +
        "chaque phrase en $second, tu la répètes en $first. Traduction fidèle et naturelle, à la première personne comme la personne l'a dite " +
        "(« I'm hungry » devient « J'ai faim », pas « Il dit qu'il a faim »). N'ajoute rien, ne réponds pas toi-même aux questions, ne commente pas, " +
        "n'appelle aucun autre outil. Cette règle passe avant la règle LANGUAGE. Pour arrêter, appelle interpreter avec action=stop. " +
        "Annonce maintenant, en une phrase dans chacune des deux langues, que tu es prêt à traduire."

object InterpreterTool : Tool {
    override val name = "interpreter"
    override val description =
        "Mode interprète : traduire en continu une conversation entre l'utilisateur et quelqu'un qui parle une autre langue (« traduis ma conversation " +
            "avec ce monsieur en anglais », « sois mon interprète en espagnol »). action start avec language (l'autre langue) et, si ce n'est pas le " +
            "français, my_language ; action stop pour « arrête de traduire ». Seulement en session vocale."
    override val parameters = objectSchema {
        string("action", "'start' (défaut) ou 'stop'.")
        string("language", "Pour start : la langue de l'autre personne (anglais, espagnol…).")
        string("my_language", "Pour start : la langue de l'utilisateur, français par défaut.")
    }

    override suspend fun run(args: JsonObject, ctx: JarvisContainer): String {
        if (args.stringArg("action").trim().lowercase() == "stop") {
            ctx.interpreterPair = null
            return "MODE INTERPRÈTE ARRÊTÉ. Reprends ton rôle habituel et la règle LANGUAGE normale ; dis en une phrase, dans la langue de l'utilisateur, que la traduction est terminée."
        }
        val other = args.stringArg("language").trim().ifEmpty { return "Indiquez la langue de l'autre personne (anglais, espagnol…)." }
        val mine = languageName(args.stringArg("my_language").ifBlank { "français" })
        val theirs = languageName(other)
        if (normalize(mine) == normalize(theirs)) return "Les deux langues sont les mêmes : indiquez la langue de l'autre personne."
        ctx.interpreterPair = mine to theirs
        return interpreterRules(mine, theirs)
    }
}
