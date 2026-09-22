package com.jarvis.android.calendar

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import java.util.TimeZone

/*
 * Really creating a calendar event (as opposed to CalendarTool's default: opening Android's own
 * "new event" form, prefilled, for the user to save themselves). Gated the same way GmailTool's
 * real sending is: off unless the user switched "Créer les événements d'agenda sans confirmation"
 * on AND just asked for it clearly — see CalendarTool.
 */

/** One calendar Jarvis could write to, with just enough to choose the right one. */
internal data class CalendarRow(
    val id: Long,
    val displayName: String,
    val accessLevel: Int,
    val accountType: String,
    val isPrimary: Boolean,
    val syncEvents: Boolean,
)

/** `CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR` — the least access level that can create events. */
internal const val CAL_ACCESS_CONTRIBUTOR = 500

/**
 * The best calendar to add to, or null when none can be written to. Requires at least contributor
 * access and that the calendar actually syncs (an unsynced one could accept the event and never show
 * it anywhere the user looks). Among the rest: the account's own primary calendar first, then a real
 * synced account over a purely local one, then alphabetically, for a result that does not depend on
 * the database's own row order.
 */
internal fun pickWritableCalendar(rows: List<CalendarRow>): CalendarRow? =
    rows.filter { it.accessLevel >= CAL_ACCESS_CONTRIBUTOR && it.syncEvents }
        .sortedWith(
            compareByDescending<CalendarRow> { it.isPrimary }
                .thenByDescending { it.accountType != "LOCAL" }
                .thenBy { it.displayName }
                .thenBy { it.id },
        )
        .firstOrNull()

internal fun hasCalendarWritePermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED

/** Every calendar Jarvis can see, without the IS_PRIMARY column (added only in API 30): "primary" is worked out the
 * portable way instead, an account's own calendar being the one whose owner is the account itself. */
internal fun writableCalendars(context: Context): List<CalendarRow> {
    if (!hasCalendarWritePermission(context)) return emptyList()
    val projection = arrayOf(
        CalendarContract.Calendars._ID,
        CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
        CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
        CalendarContract.Calendars.ACCOUNT_TYPE,
        CalendarContract.Calendars.ACCOUNT_NAME,
        CalendarContract.Calendars.OWNER_ACCOUNT,
        CalendarContract.Calendars.SYNC_EVENTS,
    )
    val rows = mutableListOf<CalendarRow>()
    try {
        context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, projection, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val account = c.getString(4).orEmpty()
                val owner = c.getString(5).orEmpty()
                rows += CalendarRow(
                    id = c.getLong(0),
                    displayName = c.getString(1).orEmpty().ifEmpty { "(sans nom)" },
                    accessLevel = c.getInt(2),
                    accountType = c.getString(3).orEmpty(),
                    isPrimary = account.isNotEmpty() && account.equals(owner, ignoreCase = true),
                    syncEvents = c.getInt(6) == 1,
                )
            }
        }
    } catch (_: Exception) {
        // No calendar provider, or a permission race: treated the same as "nothing writable".
    }
    return rows
}

/** Creates the event for real. Returns its new row id, or null on any failure (including no writable calendar). */
internal fun createEvent(context: Context, calendarId: Long, title: String, beginMs: Long, endMs: Long, location: String): Long? {
    val values = ContentValues().apply {
        put(CalendarContract.Events.CALENDAR_ID, calendarId)
        put(CalendarContract.Events.TITLE, title)
        put(CalendarContract.Events.DTSTART, beginMs)
        put(CalendarContract.Events.DTEND, endMs)
        put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
        if (location.isNotEmpty()) put(CalendarContract.Events.EVENT_LOCATION, location)
    }
    return try {
        context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)?.let { ContentUris.parseId(it) }
    } catch (_: Exception) {
        null
    }
}
