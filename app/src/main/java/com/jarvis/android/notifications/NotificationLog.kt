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
