package com.jarvis.android.messaging

internal const val AUTO_REPLY_QUIET_MS = 30 * 60_000L

internal const val AUTO_REPLY_MAX_PER_HOUR = 10

/** Whether [sender] may get an automatic answer now, given the answers already sent ([history]: sender to time). */
internal fun autoReplyAllowed(history: List<Pair<String, Long>>, sender: String, now: Long): Boolean {
    if (sender.isBlank()) return false
    if (history.any { it.first.equals(sender, ignoreCase = true) && now - it.second < AUTO_REPLY_QUIET_MS }) return false
    return history.count { now - it.second < 60 * 60_000L } < AUTO_REPLY_MAX_PER_HOUR
}
