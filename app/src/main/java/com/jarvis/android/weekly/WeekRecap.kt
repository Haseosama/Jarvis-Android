package com.jarvis.android.weekly

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * The parts of Sunday's summary drawn from what Jarvis itself did in the week: the reminders that rang and the ones to come, the
 * drives (driving mode on, then off) and the alerts it gave. Each part only when there is something to say.
 */

/** A reminder as the summary sees it; [shown] false when it could not be shown (notifications blocked, or it failed). */
internal data class RecapReminder(val at: LocalDateTime, val text: String, val shown: Boolean = true)

private val DAY = DateTimeFormatter.ofPattern("EEEE", Locale.FRANCE)
private fun day(t: LocalDateTime) = t.format(DAY)
private fun clock(t: LocalDateTime) = if (t.minute == 0) "${t.hour} h" else "${t.hour} h ${"%02d".format(t.minute)}"
private fun short(text: String) = text.replace(Regex("\\s+"), " ").trim().let { if (it.length > 60) it.take(60).substringBeforeLast(' ') + "…" else it }

/** "1 h 05", "40 min". */
internal fun durationWords(minutes: Long): String =
    if (minutes < 60) "$minutes min" else "${minutes / 60} h" + if (minutes % 60 > 0) " ${"%02d".format(minutes % 60)}" else ""

/** The reminders of the week gone by: named when one or two, counted otherwise; those not shown said apart. */
internal fun pastRemindersWords(list: List<RecapReminder>): String? {
    if (list.isEmpty()) return null
    val shown = list.filter { it.shown }.sortedBy { it.at }
    val missed = list.filterNot { it.shown }.sortedBy { it.at }
    val head = when {
        shown.isEmpty() -> "rappels : aucun n’a pu s’afficher"
        shown.size <= 2 -> "rappels : " + shown.joinToString(", ") { "${short(it.text)} ${day(it.at)}" }
        else -> "rappels : ${shown.size} sonnés"
    }
    return if (missed.isEmpty()) head
    else head + ", ${missed.size} non affiché${if (missed.size > 1) "s" else ""} faute de notifications (" + missed.take(2).joinToString(", ") { short(it.text) } +
        (if (missed.size > 2) "…" else "") + ")"
}

/** The reminders set for the week to come, day and hour, five at most. */
internal fun nextRemindersWords(list: List<RecapReminder>): String? {
    if (list.isEmpty()) return null
    val sorted = list.sortedBy { it.at }
    return "rappels prévus : " + sorted.take(5).joinToString(" ; ") { "${day(it.at)} ${clock(it.at)} ${short(it.text)}" } +
        if (sorted.size > 5) " (+${sorted.size - 5})" else ""
}

/** The drives of the week: each one's start and length in minutes. */
internal fun drivesWords(drives: List<Pair<LocalDateTime, Long>>): String? {
    if (drives.isEmpty()) return null
    if (drives.size == 1) return "en voiture : un trajet, ${day(drives[0].first)} (${durationWords(drives[0].second)})"
    val longest = drives.maxBy { it.second }
    return "en voiture : ${drives.size} trajets, ${durationWords(drives.sumOf { it.second })} au volant, le plus long ${day(longest.first)} (${durationWords(longest.second)})"
}

/** The alerts Jarvis gave, as (subject, what it said): one said in full, more counted by subject, the most frequent first. */
internal fun alertsWords(alerts: List<Pair<String, String>>): String? {
    if (alerts.isEmpty()) return null
    if (alerts.size == 1) {
        val (kind, text) = alerts[0]
        return "une alerte : $kind" + if (text.isNotBlank()) " (${text.trim().let { if (it.length > 120) it.take(120).substringBeforeLast(' ') + "…" else it }.removeSuffix(".")})" else ""
    }
    val order = alerts.map { it.first }.distinct()
    val counts = alerts.groupingBy { it.first }.eachCount().entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { order.indexOf(it.key) })
    return "${alerts.size} alertes reçues : " + counts.joinToString(", ") { if (it.value > 1) "${it.key} (${it.value})" else it.key }
}
