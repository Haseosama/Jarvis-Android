package com.jarvis.android.quiet

/*
 * The offline phrasings of the quiet mode, on normalized text ("je suis en reunion jusqu a 15 h 30"): the end as
 * "HH:MM" or a number of minutes; an hour when nothing is said.
 */

internal data class QuietAsk(val until: String = "", val minutes: Int = 0)

private val START = Regex(
    "^(?:jarvis )?(?:je suis en reunion|je suis occupee?|je suis en rendez vous|ne me derange pas|ne pas deranger|" +
        "(?:active|mets|lance)(?: le mode)? ne pas deranger|mode ne pas deranger)(.*)$",
)
private val STOP = Regex(
    "^(?:jarvis )?(?:fin de (?:la|ma) reunion|j ai fini (?:ma|la) reunion|la reunion est finie|tu peux me deranger|je suis disponible|" +
        "(?:desactive|arrete|coupe|enleve|termine)(?: le mode)? ne pas deranger)$",
)
private val UNTIL = Regex("^ jusqu a (\\d{1,2})(?: ?h| heures?)?(?: ?(\\d{2}))?$")
private val FOR_TIME = Regex("^ (?:pendant|pour) (\\d{1,3}|une|un|deux|trois) (minutes?|min|heures?|h)$")
private val WORD_NUMBERS = mapOf("un" to 1, "une" to 1, "deux" to 2, "trois" to 3)

/** A request to start the quiet mode, or null. */
internal fun quietStartPhrase(n: String): QuietAsk? {
    val rest = START.matchEntire(n)?.groupValues?.get(1) ?: return null
    if (rest.isBlank()) return QuietAsk(minutes = 60)
    UNTIL.matchEntire(rest)?.let { m ->
        return QuietAsk(until = m.groupValues[1] + ":" + m.groupValues[2].ifEmpty { "00" })
    }
    when (rest) {
        " pendant une demi heure", " pour une demi heure" -> return QuietAsk(minutes = 30)
        " pendant une heure et demie", " pour une heure et demie" -> return QuietAsk(minutes = 90)
    }
    FOR_TIME.matchEntire(rest)?.let { m ->
        val n = m.groupValues[1].toIntOrNull() ?: WORD_NUMBERS[m.groupValues[1]] ?: return null
        return QuietAsk(minutes = if (m.groupValues[2].startsWith("h")) n * 60 else n)
    }
    return null
}

internal fun quietStopPhrase(n: String): Boolean = STOP.matches(n)
