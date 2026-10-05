package com.jarvis.android.trash

import com.jarvis.android.text.normalize
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.text.Normalizer
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.IsoFields
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/*
 * Which bin goes out tomorrow. No national open data gives the collection days (each commune or intercommunality publishes its own
 * calendar, as a PDF, a page, an app, sometimes an .ics), so the calendar comes from three places: the days said by voice ("le bac
 * jaune le mardi des semaines paires", "le verre le premier lundi du mois"), an .ics link when the commune gives one, and the events
 * of the phone's calendar that speak of a collection. Pure logic here, no Android, so it is unit-tested.
 */

/** How often a rule's days come back. */
internal enum class Cadence(val key: String) {
    WEEKLY("weekly"), EVEN_WEEKS("even"), ODD_WEEKS("odd"), BIWEEKLY("biweekly"), MONTHLY("monthly");

    companion object { fun of(key: String?) = entries.firstOrNull { it.key == key } ?: WEEKLY }
}

/**
 * A bin collected on [days]: every week, on even or odd ISO weeks (how most French calendars say it), every other week counted
 * from the week of [anchor], or on the [nth] such day of the month (1 to 4, -1 for the last).
 */
internal data class TrashRule(val bin: String, val days: Set<DayOfWeek>, val cadence: Cadence = Cadence.WEEKLY, val anchor: LocalDate? = null, val nth: Int = 0)

/** A bin on one day: a one-off collection, an .ics or calendar event, or (in [TrashSchedule.skips]) a day without it. */
internal data class TrashDay(val bin: String, val date: LocalDate)

/** What was said: the rules, the one-off days, and the days without collection (bin "" = none at all that day). */
internal data class TrashSchedule(val rules: List<TrashRule> = emptyList(), val dates: List<TrashDay> = emptyList(), val skips: List<TrashDay> = emptyList()) {
    val isEmpty get() = rules.isEmpty() && dates.isEmpty()
}

private fun monday(d: LocalDate) = d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

internal fun TrashRule.collects(date: LocalDate): Boolean {
    if (date.dayOfWeek !in days) return false
    return when (cadence) {
        Cadence.WEEKLY -> true
        Cadence.EVEN_WEEKS -> date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR) % 2 == 0
        Cadence.ODD_WEEKS -> date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR) % 2 == 1
        Cadence.BIWEEKLY -> anchor != null && Math.floorMod(ChronoUnit.WEEKS.between(monday(anchor), monday(date)), 2L) == 0L
        Cadence.MONTHLY -> when {
            nth > 0 -> (date.dayOfMonth - 1) / 7 + 1 == nth
            nth < 0 -> date.plusWeeks(1).month != date.month
            else -> true
        }
    }
}

private val BIN_FILLER = Regex("^(?:(?:le|la|les|du|de|des|d|un|une|mon|ma|mes|bac|bacs|poubelle|poubelles|conteneur|conteneurs|collecte|ramassage|sortie)\\s+)+")

/** The words that tell a bin from another: "le bac jaune" and "poubelle jaune" are both "jaune". */
internal fun binKey(bin: String): String = normalize(bin).replace(BIN_FILLER, "").trim()

internal fun sameBin(a: String, b: String): Boolean {
    val ka = binKey(a)
    val kb = binKey(b)
    if (ka.isEmpty() || kb.isEmpty()) return ka == kb
    return ka == kb || " $ka ".contains(" $kb ") || " $kb ".contains(" $ka ")
}

private val COLOURS = setOf("jaune", "vert", "verte", "gris", "grise", "marron", "bleu", "bleue", "noir", "noire", "brun", "brune", "orange", "violet", "rouge", "blanc")

/** A bin as it will be said: the user's words, "bac jaune" for a bare colour. */
internal fun binLabel(raw: String): String {
    val t = raw.trim().trimEnd('.', '!', ',').replace(Regex("\\s+"), " ")
    return if (normalize(t) in COLOURS) "bac ${t.lowercase(Locale.FRANCE)}" else t.replaceFirstChar { it.lowercase(Locale.FRANCE) }
}

/** The bins collected on [date], from the schedule and [others] (.ics and calendar), each once, without the skipped ones. */
internal fun binsOn(schedule: TrashSchedule, date: LocalDate, others: List<TrashDay> = emptyList()): List<String> {
    val skips = schedule.skips.filter { it.date == date }
    if (skips.any { it.bin.isBlank() }) return emptyList()
    val bins = schedule.rules.filter { it.collects(date) }.map { it.bin } + (schedule.dates + others).filter { it.date == date }.map { it.bin }
    val out = ArrayList<String>()
    for (b in bins) if (skips.none { sameBin(it.bin, b) } && out.none { sameBin(it, b) }) out += b
    return out
}

/** The days with a collection from [from] for [days] days, soonest first. */
internal fun collections(schedule: TrashSchedule, from: LocalDate, days: Int, others: List<TrashDay> = emptyList()): List<Pair<LocalDate, List<String>>> =
    (0 until days).map { from.plusDays(it.toLong()) }.mapNotNull { d -> binsOn(schedule, d, others).takeIf { it.isNotEmpty() }?.let { d to it } }

/** [schedule] with [rule]: it replaces the rules said before for the same bin (a correction, or all its days said again). */
internal fun withRule(schedule: TrashSchedule, rule: TrashRule): TrashSchedule =
    schedule.copy(rules = schedule.rules.filterNot { sameBin(it.bin, rule.bin) } + rule)

/** "", "tout", "toutes" or "all": every bin. */
internal fun isAllBins(bin: String): Boolean = binKey(bin).let { it.isEmpty() || it == "tout" || it == "toutes" || it == "tous" || it == "all" }

/** [schedule] without anything about [bin] (rules, one-off days, skips); "tout" or "" clears all. */
internal fun withoutBin(schedule: TrashSchedule, bin: String): TrashSchedule {
    if (isAllBins(bin)) return TrashSchedule()
    return TrashSchedule(schedule.rules.filterNot { sameBin(it.bin, bin) }, schedule.dates.filterNot { sameBin(it.bin, bin) }, schedule.skips.filterNot { it.bin.isNotBlank() && sameBin(it.bin, bin) })
}

/** One-off days and skips that are past are dropped. */
internal fun pruned(schedule: TrashSchedule, today: LocalDate): TrashSchedule =
    schedule.copy(dates = schedule.dates.filter { !it.date.isBefore(today) }, skips = schedule.skips.filter { !it.date.isBefore(today) })

// ---- what is said

private val FR = Locale.FRANCE
private val DAY_NAMES = listOf("lundi", "mardi", "mercredi", "jeudi", "vendredi", "samedi", "dimanche")
private val EN_DAY_NAMES = listOf("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")
private val MONTHS = listOf("janvier", "fevrier", "mars", "avril", "mai", "juin", "juillet", "aout", "septembre", "octobre", "novembre", "decembre")

/** "lundi et jeudi", "du lundi au vendredi", "Tuesday" → the days. */
internal fun parseDays(text: String): Set<DayOfWeek> {
    val t = normalize(text)
    fun day(w: String): DayOfWeek? = (DAY_NAMES.indexOf(w).takeIf { it >= 0 } ?: EN_DAY_NAMES.indexOf(w).takeIf { it >= 0 })?.let { DayOfWeek.of(it + 1) }
    val names = (DAY_NAMES + EN_DAY_NAMES).joinToString("|")
    Regex("\\bdu ($names)s? au ($names)s?\\b").find(t)?.let { m ->
        val a = day(m.groupValues[1])!!.value
        val b = day(m.groupValues[2])!!.value
        return (0..Math.floorMod(b - a, 7)).map { DayOfWeek.of((a - 1 + it) % 7 + 1) }.toSet()
    }
    return Regex("\\b($names)s?\\b").findAll(t).mapNotNull { day(it.groupValues[1]) }.toSet()
}

/** "chaque semaine", "semaines paires", "une semaine sur deux", "le premier lundi du mois", "dernier vendredi du mois" → how often, and which of the month. */
internal fun parseCadence(text: String): Pair<Cadence, Int> {
    val t = normalize(text)
    return when {
        Regex("\\bimpaire?s?\\b|\\bodd\\b").containsMatchIn(t) -> Cadence.ODD_WEEKS to 0
        Regex("\\bpaire?s?\\b|\\beven\\b").containsMatchIn(t) -> Cadence.EVEN_WEEKS to 0
        Regex("\\b(?:une|1) semaine sur (?:deux|2)\\b|\\b(?:quinze|15) jours\\b|\\bquinzaine\\b|\\b(?:deux|2) semaines\\b|\\bevery other\\b|\\bfortnight|\\bbiweekly\\b|\\bevery (?:two|2) weeks\\b").containsMatchIn(t) ->
            Cadence.BIWEEKLY to 0
        Regex("\\bmois\\b|\\bmonth|\\bmensuel").containsMatchIn(t) -> Cadence.MONTHLY to when {
            Regex("\\bdernier|\\blast\\b").containsMatchIn(t) -> -1
            Regex("\\bquatrieme\\b|\\b4e\\b|\\b4eme\\b|\\bfourth\\b|\\b4th\\b").containsMatchIn(t) -> 4
            Regex("\\btroisieme\\b|\\b3e\\b|\\b3eme\\b|\\bthird\\b|\\b3rd\\b").containsMatchIn(t) -> 3
            Regex("\\bdeuxieme\\b|\\bsecond\\b|\\b2e\\b|\\b2eme\\b|\\b2nd\\b").containsMatchIn(t) -> 2
            Regex("\\bpremier\\b|\\b1er\\b|\\bfirst\\b|\\b1st\\b").containsMatchIn(t) -> 1
            else -> 0
        }
        else -> Cadence.WEEKLY to 0
    }
}

private fun fold(text: String) = Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").replace('’', '\'').trim()

private fun dateOf(year: Int?, month: Int, day: Int, today: LocalDate): LocalDate? = try {
    if (year != null) LocalDate.of(if (year < 100) 2000 + year else year, month, day)
    else LocalDate.of(today.year, month, day).let { if (it.isBefore(today.minusDays(7))) it.plusYears(1) else it }
} catch (_: Exception) {
    null
}

/** "2026-11-14", "14/11", "14/11/2026", "le 14 novembre", "demain", "jeudi" (the next one, today included) → a day. */
internal fun parseDate(text: String, today: LocalDate): LocalDate? {
    val t = fold(text)
    Regex("\\b(\\d{4})-(\\d{1,2})-(\\d{1,2})\\b").find(t)?.let { m -> return dateOf(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt(), today) }
    Regex("\\b(\\d{1,2})[/.](\\d{1,2})(?:[/.](\\d{2,4}))?\\b").find(t)?.let { m ->
        return dateOf(m.groupValues[3].toIntOrNull(), m.groupValues[2].toInt(), m.groupValues[1].toInt(), today)
    }
    Regex("\\b(\\d{1,2})(?:er)? (${MONTHS.joinToString("|")})(?: (\\d{4}))?\\b").find(t)?.let { m ->
        return dateOf(m.groupValues[3].toIntOrNull(), MONTHS.indexOf(m.groupValues[2]) + 1, m.groupValues[1].toInt(), today)
    }
    if (Regex("apres[- ]demain").containsMatchIn(t)) return today.plusDays(2)
    if (Regex("\\bdemain\\b|\\btomorrow\\b").containsMatchIn(t)) return today.plusDays(1)
    if (Regex("aujourd'hui|\\btoday\\b").containsMatchIn(t)) return today
    return parseDays(t).singleOrNull()?.let { today.with(TemporalAdjusters.nextOrSame(it)) }
}

/** "19h30", "20 h", "20:15", "8 heures du soir" → a time of day. */
internal fun parseTime(text: String): LocalTime? {
    val t = fold(text)
    val m = Regex("\\b(\\d{1,2}) ?(?:h|heures?|:)? ?(\\d{2})?\\b").find(t) ?: return null
    var h = m.groupValues[1].toInt()
    val min = m.groupValues[2].toIntOrNull() ?: 0
    if (h in 1..11 && Regex("\\bsoir\\b|\\bpm\\b").containsMatchIn(t)) h += 12
    return if (h in 0..23 && min in 0..59) LocalTime.of(h, min) else null
}

internal fun timeWords(t: LocalTime): String = if (t.minute == 0) "${t.hour} h" else "${t.hour} h ${"%02d".format(t.minute)}"

/** "demain", "aujourd’hui", "jeudi 9 octobre". */
internal fun dayWords(date: LocalDate, today: LocalDate): String = when (date) {
    today -> "aujourd’hui"
    today.plusDays(1) -> "demain " + date.format(DateTimeFormatter.ofPattern("EEEE", FR))
    else -> date.format(DateTimeFormatter.ofPattern("EEEE d MMMM", FR))
}

/** "le bac jaune", "le bac jaune et le verre". */
internal fun binsWords(bins: List<String>): String = when (bins.size) {
    0 -> ""
    1 -> bins[0]
    else -> bins.dropLast(1).joinToString(", ") + " et " + bins.last()
}

/** What the evening reminder says about tomorrow's [bins]. */
internal fun eveningWords(bins: List<String>, tomorrow: LocalDate): String {
    val day = tomorrow.format(DateTimeFormatter.ofPattern("EEEE", FR))
    val out = if (bins.size > 1) "les bacs" else "le bac"
    return "Demain $day, collecte : ${binsWords(bins)}. Pensez à sortir $out ce soir."
}

private fun ordinal(n: Int) = when (n) { 1 -> "premier"; 2 -> "deuxième"; 3 -> "troisième"; 4 -> "quatrième"; -1 -> "dernier"; else -> "" }

private fun daysList(days: Set<DayOfWeek>): String {
    val names = days.sorted().map { DAY_NAMES[it.value - 1] }
    return if (names.size == 1) names[0] else names.dropLast(1).joinToString(", ") + " et " + names.last()
}

/** "bac jaune : le mardi des semaines paires". */
internal fun ruleWords(r: TrashRule): String {
    val days = daysList(r.days)
    val many = r.days.size > 1
    val text = when (r.cadence) {
        Cadence.WEEKLY -> if (many) "chaque $days" else "le $days, chaque semaine"
        Cadence.EVEN_WEEKS -> "le $days des semaines paires"
        Cadence.ODD_WEEKS -> "le $days des semaines impaires"
        Cadence.BIWEEKLY -> "le $days, une semaine sur deux" + (r.anchor?.let { " (dont la semaine du ${monday(it).format(DateTimeFormatter.ofPattern("d MMMM", FR))})" } ?: "")
        Cadence.MONTHLY -> if (r.nth != 0) "le ${ordinal(r.nth)} $days du mois" else "le $days, chaque mois"
    }
    return "${r.bin} : $text"
}

/** The schedule in a few sentences, for "quel est mon calendrier des poubelles". */
internal fun scheduleWords(s: TrashSchedule, today: LocalDate): String {
    if (s.isEmpty && s.skips.isEmpty()) return ""
    val parts = ArrayList<String>()
    parts += s.rules.map { ruleWords(it) }
    s.dates.sortedBy { it.date }.forEach { parts += "${it.bin} : ${dayWords(it.date, today)}" }
    s.skips.sortedBy { it.date }.forEach { parts += "pas de collecte ${if (it.bin.isBlank()) "" else "de ${it.bin} "}${dayWords(it.date, today)}" }
    return parts.joinToString(" ; ")
}

/** "Demain mardi : bac jaune. Vendredi 9 octobre : ordures ménagères." */
internal fun upcomingWords(list: List<Pair<LocalDate, List<String>>>, today: LocalDate): String =
    list.joinToString(" ") { (d, bins) -> dayWords(d, today).replaceFirstChar { it.uppercase() } + " : " + binsWords(bins) + "." }

/** The next time the evening reminder rings: today at [at] if still ahead, else tomorrow. */
internal fun nextRing(now: LocalDateTime, at: LocalTime): LocalDateTime {
    val today = now.toLocalDate().atTime(at)
    return if (now.isBefore(today)) today else today.plusDays(1)
}

// ---- the phone's calendar and .ics files

private val TRASH_WORDS = Regex(
    "\\bcollecte|\\bpoubelle|\\bordures\\b|\\bdechets?\\b|\\bencombrants?\\b|\\bbiodechets?\\b|\\btri selectif\\b|\\bemballages\\b|\\bramassage\\b|" +
        "\\bbacs? (?:jaune|vert|verte|gris|grise|marron|bleu|bleue|noir|brun)\\b|\\bsortir (?:le|les) bacs?\\b|\\bbin collection\\b|\\brecycling\\b|\\bgarbage\\b|\\btrash\\b",
)

/** Whether a calendar event speaks of a collection ("Collecte bac jaune", "Ordures ménagères", "Encombrants"). */
internal fun isTrashTitle(title: String): Boolean = TRASH_WORDS.containsMatchIn(normalize(title))

/** The bin a collection event names: "Collecte des ordures ménagères" → "ordures ménagères". */
internal fun binFromTitle(title: String): String {
    val t = title.trim().replace(Regex("\\s+"), " ")
    val stripped = t.replace(Regex("(?i)^(?:collecte|ramassage|sortie|sortir)\\s+(?:(?:des|du|de la|de l'|de|le|la|les)\\s+)?"), "").trim()
    return binLabel(stripped.ifEmpty { t })
}

/**
 * The days of an .ics file from [from] to [to]: each event's summary as the bin, its start day, and its repeats when it has an
 * RRULE (DAILY, WEEKLY with BYDAY, MONTHLY with BYDAY like 1MO or -1FR or BYMONTHDAY, YEARLY; INTERVAL, UNTIL, COUNT, EXDATE).
 * A time in UTC is read in [zone].
 */
internal fun parseIcs(text: String, from: LocalDate, to: LocalDate, zone: ZoneId): List<TrashDay> {
    val lines = text.replace("\r\n", "\n").replace("\r", "\n").replace(Regex("\n[ \t]"), "").split("\n")
    val out = ArrayList<TrashDay>()
    var props: MutableList<Pair<String, String>>? = null
    for (line in lines) {
        when {
            line.equals("BEGIN:VEVENT", true) -> props = ArrayList()
            line.equals("END:VEVENT", true) -> { props?.let { out += icsEvent(it, from, to, zone) }; props = null }
            props != null -> {
                val colon = line.indexOf(':').takeIf { it > 0 } ?: continue
                props += line.substring(0, colon).substringBefore(';').uppercase(Locale.ROOT) to line.substring(colon + 1)
            }
        }
    }
    return out.sortedBy { it.date }
}

private fun icsDate(value: String, zone: ZoneId): LocalDate? {
    val v = value.trim()
    val m = Regex("^(\\d{4})(\\d{2})(\\d{2})(?:T(\\d{2})(\\d{2})(\\d{2})(Z)?)?").find(v) ?: return null
    return try {
        val (y, mo, d) = Triple(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
        if (m.groupValues[7] == "Z") LocalDateTime.of(y, mo, d, m.groupValues[4].toInt(), m.groupValues[5].toInt(), m.groupValues[6].toInt())
            .atZone(java.time.ZoneOffset.UTC).withZoneSameInstant(zone).toLocalDate()
        else LocalDate.of(y, mo, d)
    } catch (_: Exception) {
        null
    }
}

private val ICS_DAYS = listOf("MO", "TU", "WE", "TH", "FR", "SA", "SU")

private fun icsEvent(props: List<Pair<String, String>>, from: LocalDate, to: LocalDate, zone: ZoneId): List<TrashDay> {
    val summary = props.firstOrNull { it.first == "SUMMARY" }?.second?.replace("\\,", ",")?.replace("\\;", ";")?.replace(Regex("\\\\[nN]"), " ")?.trim().orEmpty()
    if (summary.isEmpty()) return emptyList()
    val start = props.firstOrNull { it.first == "DTSTART" }?.let { icsDate(it.second, zone) } ?: return emptyList()
    val bin = binFromTitle(summary)
    val excluded = props.filter { it.first == "EXDATE" }.flatMap { it.second.split(',') }.mapNotNull { icsDate(it, zone) }.toSet()
    val rrule = props.firstOrNull { it.first == "RRULE" }?.second
        ?: return if (start in from..to && start !in excluded) listOf(TrashDay(bin, start)) else emptyList()
    val r = rrule.split(';').mapNotNull { p -> p.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0].uppercase(Locale.ROOT) to it[1].uppercase(Locale.ROOT) } }.toMap()
    val freq = r["FREQ"] ?: return emptyList()
    val interval = r["INTERVAL"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
    val until = r["UNTIL"]?.let { icsDate(it, zone) }
    var left = r["COUNT"]?.toIntOrNull()
    val byDay = r["BYDAY"]?.split(',')?.mapNotNull { Regex("^([+-]?\\d)?(MO|TU|WE|TH|FR|SA|SU)$").find(it.trim()) }
        ?.map { (it.groupValues[1].toIntOrNull() ?: 0) to DayOfWeek.of(ICS_DAYS.indexOf(it.groupValues[2]) + 1) }.orEmpty()
    val byMonthDay = r["BYMONTHDAY"]?.split(',')?.mapNotNull { it.trim().toIntOrNull() }.orEmpty()
    fun matches(d: LocalDate): Boolean = when (freq) {
        "DAILY" -> ChronoUnit.DAYS.between(start, d) % interval == 0L
        "WEEKLY" -> (if (byDay.isEmpty()) d.dayOfWeek == start.dayOfWeek else byDay.any { it.second == d.dayOfWeek }) &&
            ChronoUnit.WEEKS.between(monday(start), monday(d)) % interval == 0L
        "MONTHLY" -> ChronoUnit.MONTHS.between(start.withDayOfMonth(1), d.withDayOfMonth(1)) % interval == 0L && when {
            byDay.isNotEmpty() -> byDay.any { (n, day) ->
                d.dayOfWeek == day && when {
                    n > 0 -> (d.dayOfMonth - 1) / 7 + 1 == n
                    n < 0 -> (d.lengthOfMonth() - d.dayOfMonth) / 7 + 1 == -n
                    else -> true
                }
            }
            byMonthDay.isNotEmpty() -> byMonthDay.any { it == d.dayOfMonth || (it < 0 && d.lengthOfMonth() + it + 1 == d.dayOfMonth) }
            else -> d.dayOfMonth == start.dayOfMonth
        }
        "YEARLY" -> d.month == start.month && d.dayOfMonth == start.dayOfMonth && (d.year - start.year) % interval == 0
        else -> false
    }
    val out = ArrayList<TrashDay>()
    val end = listOfNotNull(to, until).min()
    // a COUNT is counted from the start; a start years back without one is walked from a little before [from]
    var d = if (left == null && start.isBefore(from.minusDays(7))) from.minusDays(7) else start
    var steps = 0
    while (!d.isAfter(end) && steps++ < 4_000) {
        if (matches(d)) {
            if (left != null) { if (left <= 0) break; left-- }
            if (d !in excluded && !d.isBefore(from)) out += TrashDay(bin, d)
        }
        d = d.plusDays(1)
    }
    return out
}

// ---- kept on the phone

internal fun trashToJson(s: TrashSchedule): String = buildJsonObject {
    put("rules", buildJsonArray {
        s.rules.forEach { r ->
            add(buildJsonObject {
                put("bin", r.bin)
                put("days", r.days.sorted().joinToString(",") { it.value.toString() })
                put("cadence", r.cadence.key)
                r.anchor?.let { put("anchor", it.toString()) }
                if (r.nth != 0) put("nth", r.nth)
            })
        }
    })
    put("dates", buildJsonArray { s.dates.forEach { add(buildJsonObject { put("bin", it.bin); put("date", it.date.toString()) }) } })
    put("skips", buildJsonArray { s.skips.forEach { add(buildJsonObject { put("bin", it.bin); put("date", it.date.toString()) }) } })
}.toString()

/** What [trashToJson] wrote; an unreadable part is left out rather than failing. */
internal fun trashFromJson(json: String?): TrashSchedule {
    if (json.isNullOrBlank()) return TrashSchedule()
    return try {
        val o = Json.parseToJsonElement(json) as JsonObject
        fun s(e: JsonObject, k: String) = (e[k] as? JsonPrimitive)?.contentOrNull
        fun days(arr: String) = (o[arr] as? JsonArray).orEmpty().mapNotNull { el ->
            val e = el as? JsonObject ?: return@mapNotNull null
            val date = s(e, "date")?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return@mapNotNull null
            TrashDay(s(e, "bin").orEmpty(), date)
        }
        val rules = (o["rules"] as? JsonArray).orEmpty().mapNotNull { el ->
            val e = el as? JsonObject ?: return@mapNotNull null
            val days = s(e, "days").orEmpty().split(',').mapNotNull { it.trim().toIntOrNull()?.takeIf { n -> n in 1..7 }?.let { n -> DayOfWeek.of(n) } }.toSet()
            if (days.isEmpty()) return@mapNotNull null
            TrashRule(s(e, "bin") ?: return@mapNotNull null, days, Cadence.of(s(e, "cadence")), s(e, "anchor")?.let { runCatching { LocalDate.parse(it) }.getOrNull() }, (e["nth"] as? JsonPrimitive)?.intOrNull ?: 0)
        }
        TrashSchedule(rules, days("dates"), days("skips"))
    } catch (_: Exception) {
        TrashSchedule()
    }
}
