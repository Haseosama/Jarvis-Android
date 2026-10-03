package com.jarvis.android.nearby

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Month
import java.time.temporal.ChronoUnit

/*
 * OpenStreetMap's opening_hours, the part people actually write: "24/7", weekdays and their ranges ("Mo-Fr", "Mo,We,Fr"),
 * French public holidays ("PH"), months ("Jun-Sep"), times with a break or past midnight ("08:30-12:30,14:00-19:00",
 * "22:00-02:00"), "off" / "closed", rules after ";" replacing the days they name and rules after "," adding to them.
 * Anything else (sunrise, "08:00+", "Mo[1]", week numbers, dates) is not guessed at: the hours are then "unknown".
 */

/** Whether a place is open at a moment, and when that changes (null: not within a week, as with "24/7"). */
internal data class OpenState(val open: Boolean, val nextChange: LocalDateTime?)

private val DAYS = mapOf(
    "mo" to DayOfWeek.MONDAY, "tu" to DayOfWeek.TUESDAY, "we" to DayOfWeek.WEDNESDAY, "th" to DayOfWeek.THURSDAY,
    "fr" to DayOfWeek.FRIDAY, "sa" to DayOfWeek.SATURDAY, "su" to DayOfWeek.SUNDAY,
)
private val MONTHS = Month.entries.associateBy { it.name.take(3).lowercase() }
private val TIME_RANGE = Regex("""(\d{1,2}):(\d{2})\s*-\s*(\d{1,2}):(\d{2})""")

/** One rule: the months and days it is for (null: all, holidays included), whether it names public holidays, its times (minutes; empty: closed). */
private data class Rule(
    val months: Set<Month>?,
    val days: Set<DayOfWeek>?,
    val holidays: Boolean,
    val intervals: List<IntRange>,
    val additional: Boolean,
)

private class Unreadable : Exception()

/** The rules of an opening_hours value, or null when it says something this reader does not know. */
private fun parseRules(hours: String): List<Rule>? = try {
    val text = hours.replace(Regex("\"[^\"]*\""), " ").replace("||", ";").trim()
    if (text.isEmpty()) null
    else text.split(';').map { it.trim() }.filter { it.isNotEmpty() }.flatMap { part ->
        splitAdditional(part).mapIndexedNotNull { i, piece -> parseRule(piece, additional = i > 0) }
    }.takeIf { it.isNotEmpty() }
} catch (_: Unreadable) {
    null
}

/** "Mo-Fr 08:00-18:00, Sa 09:00-12:00" → its two rules; a comma inside the days ("Mo,We") or the times stays. */
private fun splitAdditional(part: String): List<String> {
    val out = ArrayList<String>()
    val current = StringBuilder()
    part.split(',').forEach { piece ->
        val p = piece.trim()
        val startsWithDays = Regex("""^(mo|tu|we|th|fr|sa|su|ph|jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)\b""", RegexOption.IGNORE_CASE).containsMatchIn(p)
        val currentHasTimes = TIME_RANGE.containsMatchIn(current) || Regex("""\b(off|closed)\b""").containsMatchIn(current.toString().lowercase())
        if (current.isNotEmpty() && startsWithDays && currentHasTimes) { out += current.toString(); current.clear() }
        if (current.isNotEmpty()) current.append(',')
        current.append(p)
    }
    if (current.isNotEmpty()) out += current.toString()
    return out
}

private fun parseRule(raw: String, additional: Boolean): Rule? {
    var rest = raw.trim().lowercase()
    if (rest.isEmpty()) return null
    if (rest == "24/7") return Rule(null, null, holidays = true, intervals = listOf(0..1440), additional = additional)
    if (Regex("""\b(sunrise|sunset|dawn|dusk|week|easter)\b|\[|\+|\d{4}""").containsMatchIn(rest)) throw Unreadable()
    if (Regex("""\bsh\b""").containsMatchIn(rest)) return null // school holidays: not known here, the rule is left out

    // the months, then the days
    var months: Set<Month>? = null
    Regex("""^((?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)(?:\s*-\s*(?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec))?(?:\s*,\s*(?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)(?:\s*-\s*(?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec))?)*)(?:\s*:)?\s+""")
        .find(rest)?.let { m ->
            months = m.groupValues[1].split(',').flatMap { range -> expand(range, MONTHS) { a, b -> cycle(a, b, Month.entries) } }.toSet()
            rest = rest.substring(m.range.last + 1)
        }
    var days: Set<DayOfWeek>? = null
    var holidays = false
    Regex("""^((?:mo|tu|we|th|fr|sa|su|ph)(?:\s*-\s*(?:mo|tu|we|th|fr|sa|su))?(?:\s*,\s*(?:mo|tu|we|th|fr|sa|su|ph)(?:\s*-\s*(?:mo|tu|we|th|fr|sa|su))?)*)(?:\s*:)?(?:\s+|$)""")
        .find(rest)?.let { m ->
            val parts = m.groupValues[1].split(',').map { it.trim() }
            holidays = "ph" in parts
            days = parts.filter { it != "ph" }.flatMap { range -> expand(range, DAYS) { a, b -> cycle(a, b, DayOfWeek.entries) } }.toSet()
            rest = rest.substring(m.range.last + 1).trim()
        }
    rest = rest.trim()

    val intervals = when {
        rest.isEmpty() || rest == "open" -> listOf(0..1440) // days without times: the whole day
        rest == "off" || rest == "closed" -> emptyList()
        else -> {
            val ranges = rest.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            ranges.map { r ->
                val m = TIME_RANGE.matchEntire(r) ?: throw Unreadable()
                val (h1, m1, h2, m2) = m.destructured.toList().map { it.toInt() }
                if (h1 > 24 || h2 > 48 || m1 > 59 || m2 > 59) throw Unreadable()
                val start = h1 * 60 + m1
                var end = h2 * 60 + m2
                if (end <= start) end += 1440 // past midnight
                start..end
            }
        }
    }
    return Rule(months, days, holidays, intervals, additional)
}

private fun <T> expand(range: String, names: Map<String, T>, between: (T, T) -> List<T>): List<T> {
    val ends = range.split('-').map { it.trim() }
    val a = names[ends[0]] ?: throw Unreadable()
    if (ends.size == 1) return listOf(a)
    val b = names[ends[1]] ?: throw Unreadable()
    return between(a, b)
}

/** From [a] to [b] included, going round ("Sa-Mo", "Nov-Feb"). */
private fun <T> cycle(a: T, b: T, all: List<T>): List<T> {
    val out = ArrayList<T>()
    var i = all.indexOf(a)
    while (true) { out += all[i]; if (all[i] == b) break; i = (i + 1) % all.size }
    return out
}

/** The times (minutes from that day's midnight, past 1440 when it runs over midnight) a place is open on [date]. */
private fun openOn(rules: List<Rule>, date: LocalDate): List<IntRange> {
    val holiday = isFrenchPublicHoliday(date)
    var out: List<IntRange> = emptyList()
    for (rule in rules) {
        if (rule.months != null && date.month !in rule.months) continue
        val applies = rule.days == null || date.dayOfWeek in rule.days || (rule.holidays && holiday)
        if (!applies) continue
        out = if (rule.additional && rule.intervals.isNotEmpty()) out + rule.intervals else rule.intervals
    }
    return out
}

/** Open or closed at [at] by [hours], and when that changes; null when the hours cannot be read. */
internal fun openState(hours: String?, at: LocalDateTime): OpenState? {
    val rules = parseRules(hours ?: return null) ?: return null
    // every open stretch from the day before to a week after, as moments, joined when one ends where the next starts
    val day0 = at.toLocalDate().minusDays(1)
    val spans = ArrayList<Pair<LocalDateTime, LocalDateTime>>()
    for (d in 0..8) {
        val date = day0.plusDays(d.toLong())
        openOn(rules, date).sortedBy { it.first }.forEach { r ->
            spans += date.atStartOfDay().plusMinutes(r.first.toLong()) to date.atStartOfDay().plusMinutes(r.last.toLong())
        }
    }
    spans.sortBy { it.first }
    val merged = ArrayList<Pair<LocalDateTime, LocalDateTime>>()
    spans.forEach { s ->
        val last = merged.lastOrNull()
        if (last != null && !s.first.isAfter(last.second)) merged[merged.size - 1] = last.first to maxOf(last.second, s.second) else merged += s
    }
    val horizon = day0.plusDays(9).atStartOfDay()
    merged.firstOrNull { !at.isBefore(it.first) && at.isBefore(it.second) }?.let { span ->
        return OpenState(true, span.second.takeIf { it.isBefore(horizon) })
    }
    return OpenState(false, merged.firstOrNull { it.first.isAfter(at) }?.first)
}

/** "19 h", "19 h 30". */
internal fun hourWords(t: LocalDateTime): String = if (t.minute == 0) "${t.hour} h" else "${t.hour} h ${"%02d".format(t.minute)}"

private val DAY_NAMES = listOf("lundi", "mardi", "mercredi", "jeudi", "vendredi", "samedi", "dimanche")

/** "à 19 h", "demain à 8 h", "lundi à 9 h". */
internal fun whenWords(t: LocalDateTime, now: LocalDateTime): String {
    val days = ChronoUnit.DAYS.between(now.toLocalDate(), t.toLocalDate())
    return when (days) {
        0L -> "à ${hourWords(t)}"
        1L -> if (t.hour == 0 && t.minute == 0) "à minuit" else "demain à ${hourWords(t)}"
        else -> "${DAY_NAMES[t.dayOfWeek.value - 1]} à ${hourWords(t)}"
    }
}

/** French public holidays (mainland): the fixed ones and those that follow Easter. */
internal fun isFrenchPublicHoliday(date: LocalDate): Boolean {
    val fixed = setOf(1 to 1, 5 to 1, 5 to 8, 7 to 14, 8 to 15, 11 to 1, 11 to 11, 12 to 25)
    if ((date.monthValue to date.dayOfMonth) in fixed) return true
    val easter = easterSunday(date.year)
    return date == easter.plusDays(1) || date == easter.plusDays(39) || date == easter.plusDays(50)
}

/** Easter Sunday (Gregorian calendar, the anonymous algorithm). */
internal fun easterSunday(year: Int): LocalDate {
    val a = year % 19
    val b = year / 100
    val c = year % 100
    val d = b / 4
    val e = b % 4
    val f = (b + 8) / 25
    val g = (b - f + 1) / 3
    val h = (19 * a + b - d - g + 15) % 30
    val i = c / 4
    val k = c % 4
    val l = (32 + 2 * e + 2 * i - h - k) % 7
    val m = (a + 11 * h + 22 * l) / 451
    val month = (h + l - 7 * m + 114) / 31
    val day = (h + l - 7 * m + 114) % 31 + 1
    return LocalDate.of(year, month, day)
}
