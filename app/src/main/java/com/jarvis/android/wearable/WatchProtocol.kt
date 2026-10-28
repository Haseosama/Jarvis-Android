package com.jarvis.android.wearable

import com.jarvis.android.car.carSpoken
import com.jarvis.android.i18n.tr

/*
 * What the Huawei watch app (watch/ in the repository) and the phone say to each other through Wear Engine. A message is a short text,
 * at most 1 KB:
 *   watch → phone   "HELLO"           the watch app opened: the phone answers "OK"
 *                   "P:<id>"          a button of the watch ([WATCH_PRESETS]), answered by Jarvis
 *                   "Q:<question>"    a question in words, answered by Jarvis
 *   phone → watch   "OK"              the phone hears the watch
 *                   "W"               Jarvis is thinking
 *                   "A:<i>/<n>:<text>" part i of n of the answer (n parts make the whole answer)
 * The watch cannot listen (a third-party app on the GT 4 has no microphone), so it asks by buttons.
 */

/** The longest answer shown on the watch: past it the rest is on the phone's chat. */
internal const val WATCH_REPLY_MAX_CHARS = 600

/** The most UTF-8 bytes of text in one "A:" message, leaving room for its header under Wear Engine's 1 KB. */
internal const val WATCH_PART_MAX_BYTES = 900

/** A button of the watch app: its [id] (the watch sends "P:<id>") and the question Jarvis gets. */
internal data class WatchPreset(val id: String, val question: String)

/** The watch app's buttons. Their labels are in the watch app (watch/entry/src/main/js/default/pages/index/index.js), same ids. */
internal fun watchPresets(): List<WatchPreset> = listOf(
    WatchPreset("day", tr("Qu’est-ce que j’ai aujourd’hui ? Mon agenda, mes rappels et la météo, en quelques phrases.")),
    WatchPreset("weather", tr("Quel temps fait-il aujourd’hui et demain ici ?")),
    WatchPreset("reminder", tr("Quel est mon prochain rappel ?")),
    WatchPreset("messages", tr("Lis-moi mes derniers messages non lus, en bref.")),
    WatchPreset("car", tr("Où est garée ma voiture ?")),
    WatchPreset("fuel", tr("Où est le carburant le moins cher près de moi ?")),
)

/** What the watch asked, read from its message. */
internal sealed interface WatchRequest {
    data object Hello : WatchRequest
    data class Ask(val question: String) : WatchRequest
}

/** [message] from the watch as a request, or null when it is not one (an unknown button, an empty question, garbage). */
internal fun parseWatchMessage(message: String): WatchRequest? {
    val m = message.trim()
    return when {
        m == "HELLO" -> WatchRequest.Hello
        m.startsWith("P:") -> watchPresets().firstOrNull { it.id == m.substring(2).trim() }?.let { WatchRequest.Ask(it.question) }
        m.startsWith("Q:") -> m.substring(2).trim().takeIf { it.isNotEmpty() }?.let { WatchRequest.Ask(it.take(500)) }
        else -> null
    }
}

/** [question] as Jarvis gets it from the watch: with a word that the answer goes on a small round screen. */
internal fun watchQuestion(question: String): String =
    question + " " + tr("(Réponds en deux ou trois phrases courtes, sans liste : la réponse s’affiche sur ma montre.)")

/** [answer] cut into the "A:<i>/<n>:<text>" messages sent to the watch: plain text, no Markdown, each part under [maxBytes] bytes. */
internal fun watchAnswerMessages(answer: String, maxBytes: Int = WATCH_PART_MAX_BYTES, maxChars: Int = WATCH_REPLY_MAX_CHARS): List<String> {
    val text = carSpoken(answer, maxChars).ifEmpty { tr("Je n’ai pas de réponse.") }
    val parts = splitUtf8(text, maxBytes)
    return parts.mapIndexed { i, part -> "A:${i + 1}/${parts.size}:$part" }
}

/** [text] in pieces of at most [maxBytes] UTF-8 bytes, cut at a space when there is one and never inside a character. */
internal fun splitUtf8(text: String, maxBytes: Int): List<String> {
    require(maxBytes >= 4)
    val parts = mutableListOf<String>()
    var start = 0
    while (start < text.length) {
        var end = start
        var bytes = 0
        while (end < text.length) {
            val cp = text.codePointAt(end)
            val size = when {
                cp < 0x80 -> 1
                cp < 0x800 -> 2
                cp < 0x10000 -> 3
                else -> 4
            }
            if (bytes + size > maxBytes) break
            bytes += size
            end += Character.charCount(cp)
        }
        if (end < text.length) {
            val space = text.lastIndexOf(' ', end - 1)
            if (space > start + (end - start) / 2) end = space + 1
        }
        parts += text.substring(start, end)
        start = end
    }
    return parts
}
