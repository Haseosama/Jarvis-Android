package com.jarvis.android.calendar

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.jarvis.android.offline.normalize
import java.time.LocalDate
import java.time.MonthDay
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/*
 * Birthdays saved in the phone's contacts (the "Anniversaire" field): "c'est quand l'anniversaire de Paul ?", "quels
 * anniversaires ce mois-ci ?", and today's in the morning briefing and notification. Read with the contacts permission
 * Jarvis already asks for calls; nothing is copied or kept.
 */

internal data class Birthday(val name: String, val monthDay: MonthDay, val year: Int?)

/** The ways contacts apps store a birthday: "1990-05-12", "--05-12" (no year), "19900512", "12/05/1990". Null otherwise. */
internal fun parseBirthday(text: String): Pair<MonthDay, Int?>? {
    val t = text.trim()
    Regex("^(\\d{4})-(\\d{1,2})-(\\d{1,2})").find(t)?.let { m -> return md(m.groupValues[2], m.groupValues[3], m.groupValues[1]) }
    Regex("^--(\\d{1,2})-?(\\d{1,2})$").find(t)?.let { m -> return md(m.groupValues[1], m.groupValues[2], null) }
    Regex("^(\\d{4})(\\d{2})(\\d{2})$").find(t)?.let { m -> return md(m.groupValues[2], m.groupValues[3], m.groupValues[1]) }
    Regex("^(\\d{1,2})[/.](\\d{1,2})(?:[/.](\\d{4}))?$").find(t)?.let { m -> return md(m.groupValues[2], m.groupValues[1], m.groupValues[3].ifEmpty { null }) }
    return null
}

private fun md(month: String, day: String, year: String?): Pair<MonthDay, Int?>? = try {
    val y = year?.toInt()?.takeIf { it in 1900..2100 } // some apps store 1604 for "year unknown"
    MonthDay.of(month.toInt(), day.toInt()) to y
} catch (_: Exception) {
    null
}

/** The next time [b] comes round, [today] included; 29 February falls on the 28th in other years. */
internal fun nextOccurrence(b: Birthday, today: LocalDate): LocalDate {
    fun inYear(y: Int): LocalDate = if (b.monthDay.monthValue == 2 && b.monthDay.dayOfMonth == 29 && !java.time.Year.isLeap(y.toLong())) LocalDate.of(y, 2, 28) else b.monthDay.atYear(y)
    val thisYear = inYear(today.year)
    return if (thisYear.isBefore(today)) inYear(today.year + 1) else thisYear
}

/** "Paul : aujourd'hui (35 ans)", "Marie : demain", "Luc : le 12 octobre, dans 17 jours (40 ans)". */
internal fun describeBirthday(b: Birthday, today: LocalDate): String {
    val next = nextOccurrence(b, today)
    val days = ChronoUnit.DAYS.between(today, next)
    val age = b.year?.let { " (${next.year - it} ans)" }.orEmpty()
    val `when` = when (days) {
        0L -> "aujourd'hui"
        1L -> "demain"
        else -> "le ${next.dayOfMonth} ${next.month.getDisplayName(TextStyle.FULL, Locale.FRENCH)}, dans $days jours"
    }
    return "${b.name} : $`when`$age"
}

/** Birthdays within [days] from [today], soonest first. */
internal fun upcomingBirthdays(all: List<Birthday>, today: LocalDate, days: Int): List<Birthday> =
    all.filter { ChronoUnit.DAYS.between(today, nextOccurrence(it, today)) <= days }.sortedBy { nextOccurrence(it, today) }

/** The contacts a spoken name designates: every word of it in the contact's name, accents and case ignored. */
internal fun birthdaysOf(all: List<Birthday>, query: String): List<Birthday> {
    val words = normalize(query).split(' ').filter { it.isNotEmpty() }
    if (words.isEmpty()) return emptyList()
    return all.filter { b -> val n = normalize(b.name); words.all { it in n } }
}

internal fun hasContactsPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

/** Every birthday in the contacts, or an empty list without the permission or on any error. */
internal fun readBirthdays(context: Context): List<Birthday> {
    if (!hasContactsPermission(context)) return emptyList()
    return try {
        val out = mutableListOf<Birthday>()
        context.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.Data.DISPLAY_NAME, ContactsContract.CommonDataKinds.Event.START_DATE),
            "${ContactsContract.Data.MIMETYPE} = ? AND ${ContactsContract.CommonDataKinds.Event.TYPE} = ?",
            arrayOf(ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE, ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY.toString()),
            null,
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(0)?.trim().orEmpty()
                val date = parseBirthday(c.getString(1).orEmpty()) ?: continue
                if (name.isNotEmpty()) out += Birthday(name.take(80), date.first, date.second)
            }
        }
        out.distinctBy { normalize(it.name) to it.monthDay }
    } catch (_: Exception) {
        emptyList()
    }
}

/** Today's birthdays as lines for the briefing: "Anniversaire de Paul (35 ans)". */
internal fun birthdaysToday(context: Context, today: LocalDate = LocalDate.now()): List<String> =
    upcomingBirthdays(readBirthdays(context), today, 0).map { b ->
        "Anniversaire de ${b.name}" + (b.year?.let { " (${today.year - it} ans)" }.orEmpty())
    }
