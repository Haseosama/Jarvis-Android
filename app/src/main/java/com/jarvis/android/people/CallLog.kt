package com.jarvis.android.people

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import androidx.core.content.ContextCompat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

internal enum class CallKind { MISSED, INCOMING, OUTGOING, REJECTED, OTHER }

internal data class CallEntry(val name: String?, val number: String, val kind: CallKind, val at: Long, val durationSec: Long)

internal fun callKindOf(type: Int): CallKind = when (type) {
    CallLog.Calls.MISSED_TYPE -> CallKind.MISSED
    CallLog.Calls.INCOMING_TYPE -> CallKind.INCOMING
    CallLog.Calls.OUTGOING_TYPE -> CallKind.OUTGOING
    CallLog.Calls.REJECTED_TYPE -> CallKind.REJECTED
    else -> CallKind.OTHER
}

/** Who called, without the number: the contact name, or the last two digits of an unknown number. */
internal fun callerLabel(e: CallEntry): String {
    e.name?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    val digits = e.number.filter { it.isDigit() }
    return if (digits.length >= 2) "numéro inconnu finissant par ${digits.takeLast(2)}" else "numéro masqué"
}

/** "aujourd'hui à 9 h 03", "hier à 18 h 12", "le 21/09 à 14 h" */
internal fun whenLabel(at: Long, today: LocalDate, zone: ZoneId): String {
    val t = Instant.ofEpochMilli(at).atZone(zone)
    val hm = if (t.minute == 0) "${t.hour} h" else "${t.hour} h ${t.minute.toString().padStart(2, '0')}"
    return when (t.toLocalDate()) {
        today -> "aujourd'hui à $hm"
        today.minusDays(1) -> "hier à $hm"
        else -> "le ${t.dayOfMonth}/${t.monthValue.toString().padStart(2, '0')} à $hm"
    }
}

/** One caller per line, with how many times and when last: repeated calls from the same person are grouped, newest first. */
internal data class CallerGroup(val label: String, val number: String, val count: Int, val lastAt: Long)

internal fun groupCallers(entries: List<CallEntry>): List<CallerGroup> =
    entries.groupBy { it.number.filter { c -> c.isDigit() }.takeLast(9).ifEmpty { callerLabel(it) } }
        .map { (_, list) -> val last = list.maxBy { it.at }; CallerGroup(callerLabel(last), last.number, list.size, last.at) }
        .sortedByDescending { it.lastAt }

internal fun describeGroups(groups: List<CallerGroup>, today: LocalDate, zone: ZoneId): String =
    groups.mapIndexed { i, g ->
        "${i + 1}) ${g.label}" + (if (g.count > 1) " (${g.count} fois)" else "") + ", ${whenLabel(g.lastAt, today, zone)}"
    }.joinToString("\n")

internal fun hasCallLogPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED

internal fun readCallLog(context: Context, sinceMs: Long, limit: Int = 300): List<CallEntry> {
    if (!hasCallLogPermission(context)) return emptyList()
    return try {
        val out = mutableListOf<CallEntry>()
        context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(CallLog.Calls.CACHED_NAME, CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION),
            "${CallLog.Calls.DATE} >= ?", arrayOf(sinceMs.toString()), "${CallLog.Calls.DATE} DESC",
        )?.use { c ->
            while (c.moveToNext() && out.size < limit) {
                out += CallEntry(c.getString(0), c.getString(1).orEmpty(), callKindOf(c.getInt(2)), c.getLong(3), c.getLong(4))
            }
        }
        out
    } catch (_: Exception) {
        emptyList()
    }
}
