package com.jarvis.android.notifications

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** One notification as Jarvis keeps it, in memory only: who sent it, when, and what it says. */
internal data class SeenNotification(val key: String, val app: String, val packageName: String, val postedAt: Long, val title: String, val text: String)

private val SENSITIVE_WORDS = Regex("(?i)\\b(code|otp|vérif|verif|password|mot de passe|passcode|pin|3-?d secure|3ds|authenticat|one-?time)")
private val LONG_DIGITS = Regex("\\d[\\d ]{3,10}\\d")

/**
 * A message that carries a one-time code or a password is replaced by a placeholder: such content must never reach
 * a model, even when the user asks for their notifications.
 */
internal fun redactSensitive(title: String, text: String): Pair<String, String> {
    val all = "$title $text"
    return if (SENSITIVE_WORDS.containsMatchIn(all) && LONG_DIGITS.containsMatchIn(all)) title to "[message contenant un code, masqué]" else title to text
}

internal fun clean(value: CharSequence?, max: Int): String =
    (value?.toString().orEmpty()).filter { !it.isISOControl() || it == '\n' }.replace('\n', ' ').trim().take(max)

/** The kept notifications, newest last; the size is capped and a notification that is posted again replaces its old entry. */
internal class NotificationLog(private val capacity: Int = 60) {
    private val items = ArrayDeque<SeenNotification>()

    @Synchronized
    fun add(item: SeenNotification) {
        items.removeAll { it.key == item.key }
        items.addLast(item)
        while (items.size > capacity) items.removeFirst()
    }

    @Synchronized
    fun remove(key: String) {
        items.removeAll { it.key == key }
    }

    @Synchronized
    fun clear() = items.clear()

    /** Newest first, optionally only the apps whose name contains [app]. */
    @Synchronized
    fun recent(limit: Int, app: String? = null): List<SeenNotification> {
        val filter = app?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        return items.reversed().filter { filter == null || it.app.lowercase().contains(filter) }.take(limit)
    }
}

internal fun formatNotifications(list: List<SeenNotification>, zone: ZoneId): String {
    if (list.isEmpty()) return ""
    val hour = DateTimeFormatter.ofPattern("HH:mm")
    return list.joinToString("\n") { n ->
        val time = Instant.ofEpochMilli(n.postedAt).atZone(zone).format(hour)
        val body = listOf(n.title, n.text).filter { it.isNotBlank() }.joinToString(" : ")
        "$time ${n.app} — $body"
    }
}

/**
 * What arrived, kept in memory even after the user swipes it away (the log above forgets it then), for "qu'est-ce que
 * j'ai raté ?". The same text posted again under the same key counts once. Never written to disk.
 */
internal class NotificationHistory(private val capacity: Int = 300) {
    private val items = ArrayDeque<SeenNotification>()

    @Synchronized
    fun add(item: SeenNotification) {
        if (items.any { it.key == item.key && it.text == item.text && it.title == item.title }) return
        items.addLast(item)
        while (items.size > capacity) items.removeFirst()
    }

    @Synchronized
    fun since(fromMs: Long): List<SeenNotification> = items.filter { it.postedAt >= fromMs }

    @Synchronized
    fun clear() = items.clear()
}

/** A person or a subject in one app, with how many notifications and the latest one. */
internal data class DigestLine(val app: String, val who: String, val count: Int, val last: SeenNotification)

/**
 * The notifications since a moment, grouped the way one tells them: by app, then by sender (the title), most active
 * first. "WhatsApp : Paul (3, dernier à 14:05 : « j'arrive »), Marie (1…)".
 */
internal fun digestLines(items: List<SeenNotification>): List<DigestLine> =
    items.groupBy { it.app to it.title.substringBefore(" (").trim() }
        .map { (k, list) -> DigestLine(k.first, k.second, list.size, list.maxBy { it.postedAt }) }
        .sortedWith(compareByDescending<DigestLine> { it.count }.thenByDescending { it.last.postedAt })

internal fun formatDigest(items: List<SeenNotification>, sinceMs: Long, zone: ZoneId, maxLines: Int = 12): String {
    if (items.isEmpty()) return ""
    val hour = DateTimeFormatter.ofPattern("HH:mm")
    val lines = digestLines(items)
    val apps = lines.map { it.app }.distinct()
    val head = "Depuis ${Instant.ofEpochMilli(sinceMs).atZone(zone).format(hour)} : ${items.size} notification${if (items.size > 1) "s" else ""} " +
        "de ${apps.size} application${if (apps.size > 1) "s" else ""}."
    val body = apps.joinToString("\n") { app ->
        val mine = lines.filter { it.app == app }
        "$app : " + mine.take(maxLines).joinToString(" ; ") { l ->
            val who = l.who.ifBlank { "sans titre" }
            val snippet = l.last.text.take(80).let { if (l.last.text.length > 80) "$it…" else it }
            "$who (${l.count}, dernier à ${Instant.ofEpochMilli(l.last.postedAt).atZone(zone).format(hour)}" +
                (if (snippet.isBlank()) ")" else " : « $snippet »)")
        }
    }
    return "$head\n$body"
}
