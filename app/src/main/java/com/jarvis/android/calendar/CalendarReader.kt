package com.jarvis.android.calendar

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** One calendar event: only what Jarvis needs (title and time). Descriptions, guests and notes are never read. */
internal data class EventRow(val title: String, val beginMs: Long, val endMs: Long, val allDay: Boolean)

private val HOUR = DateTimeFormatter.ofPattern("HH:mm")

private fun cleanTitle(title: String): String = title.filter { !it.isISOControl() }.trim().take(120).ifEmpty { "(sans titre)" }

/** "HH:mm-HH:mm Title", or "Toute la journée Title" for an all-day event. */
internal fun formatEvent(event: EventRow, zone: ZoneId): String {
    val title = cleanTitle(event.title)
    if (event.allDay) return "Toute la journée $title"
    val begin = Instant.ofEpochMilli(event.beginMs).atZone(zone).format(HOUR)
    val end = Instant.ofEpochMilli(event.endMs).atZone(zone).format(HOUR)
    return "$begin-$end $title"
}

/**
 * The local day an event belongs to. All-day events are stored by Android at UTC midnight, so their date is read in UTC:
 * read in a zone west of Greenwich they would fall on the day before.
 */
internal fun eventDay(event: EventRow, zone: ZoneId): LocalDate =
    Instant.ofEpochMilli(event.beginMs).atZone(if (event.allDay) ZoneOffset.UTC else zone).toLocalDate()

/** Events of [day], all-day ones first, then by start time, as display lines. */
internal fun linesForDay(events: List<EventRow>, day: LocalDate, zone: ZoneId): List<String> =
    events.filter { eventDay(it, zone) == day }
        .sortedWith(compareBy({ !it.allDay }, { it.beginMs }))
        .map { formatEvent(it, zone) }

internal fun hasCalendarPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

/** Events that overlap [fromMs, toMs), or an empty list without the permission or on any error. */
internal fun readEvents(context: Context, fromMs: Long, toMs: Long, limit: Int = 60): List<EventRow> {
    if (!hasCalendarPermission(context)) return emptyList()
    return try {
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, fromMs)
            ContentUris.appendId(it, toMs)
        }.build()
        val projection = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
        )
        val rows = mutableListOf<EventRow>()
        context.contentResolver.query(uri, projection, null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
            while (c.moveToNext() && rows.size < limit) {
                rows += EventRow(c.getString(0).orEmpty(), c.getLong(1), c.getLong(2), c.getInt(3) == 1)
            }
        }
        rows
    } catch (_: Exception) {
        emptyList()
    }
}

/** Today's events as display lines, for the briefing and the morning notification. */
internal fun eventsToday(context: Context, zone: ZoneId = ZoneId.systemDefault()): List<String> {
    val today = LocalDate.now(zone)
    val from = today.atStartOfDay(zone).toInstant().toEpochMilli()
    val to = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    // All-day events sit at UTC midnight and may start hours before or after the local day: read a wider window, then filter by day.
    return linesForDay(readEvents(context, from - 14 * 3_600_000L, to + 14 * 3_600_000L), today, zone)
}
