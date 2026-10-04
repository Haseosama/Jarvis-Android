package com.jarvis.android.outages

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
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/*
 * The reading of a planned cut of electricity, water or gas in a notice ("Enedis : coupure pour travaux le 14/10 de 9h à 12h",
 * "Veolia : l'eau sera coupée mardi 7 octobre entre 8 h 30 et 17 h"), kept free of Android so it can be tested on its own: what is cut,
 * which day, from and until when; the noted cuts as text; when to remind; and the words said.
 */

internal enum class OutageKind(val key: String, val words: String, val advice: String) {
    ELECTRICITY("electricite", "d’électricité", "chargez le téléphone et les batteries, gardez le congélateur fermé et débranchez les appareils fragiles"),
    WATER("eau", "d’eau", "remplissez quelques bouteilles et une casserole, et prévoyez de quoi tirer la chasse"),
    GAS("gaz", "de gaz", "prévoyez de cuisiner autrement ; l’eau chaude et le chauffage au gaz s’arrêteront aussi"),
    ;

    companion object {
        /** "électricité", "courant", "eau", "gaz"… in words, or null. */
        fun of(words: String): OutageKind? {
            val w = fold(words)
            return when {
                w.isEmpty() -> null
                "electri" in w || "courant" in w || "enedis" in w -> ELECTRICITY
                "gaz" in w || "grdf" in w -> GAS
                Regex("\\beaux?\\b").containsMatchIn(w) -> WATER
                else -> null
            }
        }
    }
}

/** A planned cut: what, which day, from and until when (null when the notice does not say), the reminder set for it, where it was read. */
internal data class Outage(
    val kind: OutageKind,
    val date: LocalDate,
    val start: LocalTime? = null,
    val end: LocalTime? = null,
    val source: String = "",
    val reminderId: Int? = null,
) {
    /** One cut per kind, day and start: the same notice received twice (an SMS and a mail) is noted once. */
    val key: String get() = "${kind.key}|$date|${start ?: ""}"
}

/** Lower case, no accents, one kind of apostrophe, single spaces; the punctuation of dates and hours stays. */
internal fun fold(text: String): String =
    Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
        .replace('’', '\'').replace('–', '-').replace(Regex("\\s+"), " ").trim()

private val CUT = Regex("\\b(?:coupures?|coupee?s?|interruptions?|interrompue?s?|privee?s? d|arret de (?:la )?(?:distribution|fourniture))\\b")
private val ELECTRICITY_WORDS = Regex("\\belectri\\w*|\\benedis\\b|\\bcoupures? d[eu] courant\\b|\\bcourant (?:sera|est|va etre) (?:coupe|interrompu)")
private val WATER_WORDS = Regex("\\beaux?\\b|\\bveolia\\b|\\bsaur\\b")
private val GAS_WORDS = Regex("\\bgaz\\b|\\bgrdf\\b")

/** Who sends real notices of planned cuts, or words only such a notice uses: what makes a message worth noting by itself. */
private val NOTICE = Regex("\\b(?:enedis|grdf|veolia|suez|saur|regie|syndicat des eaux|service des eaux|mairie|travaux|intervention)\\b")

private val MONTHS = listOf("janvier", "fevrier", "mars", "avril", "mai", "juin", "juillet", "aout", "septembre", "octobre", "novembre", "decembre")
private val DAYS = listOf("lundi", "mardi", "mercredi", "jeudi", "vendredi", "samedi", "dimanche")
private val NUMERIC_DATE = Regex("(?<![\\d/.])(\\d{1,2})[/.](\\d{1,2})(?:[/.](\\d{4}|\\d{2}))?(?![\\d/.]*\\d)")
private val WORD_DATE = Regex("\\b(\\d{1,2})(?:er)? (${MONTHS.joinToString("|")})(?: (\\d{4}))?\\b")
private val RELATIVE_DATE = Regex("\\b(apres-demain|apres demain|demain|aujourd'hui|ce jour)\\b")
private val WEEKDAY = Regex("\\b(${DAYS.joinToString("|")})\\b")

private const val HOUR = "\\b(\\d{1,2}) ?(?:h(?:eures?)?|:) ?(\\d{2})?"
private val RANGE = Regex("(?:\\b(?:de|entre) )?$HOUR(?: ?- ?| (?:a|et|jusqu'a) )$HOUR")
private val FROM = Regex("\\b(?:a partir de|des|debut a|vers) $HOUR")

/** The kind of cut a notice is about: the one named nearest to its word of cutting (an electricity notice may mention the water heater). */
internal fun outageKindIn(text: String): OutageKind? {
    val t = fold(text)
    val cut = CUT.find(t)?.range?.first ?: return null
    return listOf(OutageKind.ELECTRICITY to ELECTRICITY_WORDS, OutageKind.WATER to WATER_WORDS, OutageKind.GAS to GAS_WORDS)
        .mapNotNull { (kind, words) -> words.findAll(t).minOfOrNull { kotlin.math.abs(it.range.first - cut) }?.let { kind to it } }
        .minByOrNull { it.second }?.first
}

/** A day and month without a year: this year's, or next year's when it is more than a week gone (a notice in December for January). */
private fun withYear(day: Int, month: Int, year: Int?, today: LocalDate): LocalDate? = try {
    when {
        year != null -> LocalDate.of(if (year < 100) 2000 + year else year, month, day)
        else -> LocalDate.of(today.year, month, day).let { if (it.isBefore(today.minusDays(7))) it.plusYears(1) else it }
    }
} catch (_: Exception) {
    null
}

/** The day a notice gives: a date ("14/10", "14 octobre 2026"), "demain", or else a weekday ("jeudi", the next one, today included). */
internal fun outageDateIn(text: String, today: LocalDate): LocalDate? {
    val t = fold(text)
    val found = ArrayList<Pair<Int, LocalDate>>()
    NUMERIC_DATE.findAll(t).forEach { m ->
        val (d, mo, y) = m.destructured
        withYear(d.toInt(), mo.toInt(), y.toIntOrNull(), today)?.let { found += m.range.first to it }
    }
    WORD_DATE.findAll(t).forEach { m ->
        val (d, mo, y) = m.destructured
        withYear(d.toInt(), MONTHS.indexOf(mo) + 1, y.toIntOrNull(), today)?.let { found += m.range.first to it }
    }
    RELATIVE_DATE.findAll(t).forEach { m ->
        found += m.range.first to when (m.groupValues[1]) { "demain" -> today.plusDays(1); "aujourd'hui", "ce jour" -> today; else -> today.plusDays(2) }
    }
    found.minByOrNull { it.first }?.let { return it.second }
    val day = WEEKDAY.find(t)?.groupValues?.get(1) ?: return null
    return today.with(TemporalAdjusters.nextOrSame(DayOfWeek.of(DAYS.indexOf(day) + 1)))
}

private fun time(h: String, m: String): LocalTime? {
    val hour = h.toIntOrNull() ?: return null
    val minute = m.toIntOrNull() ?: 0
    return if (hour in 0..23 && minute in 0..59) LocalTime.of(hour, minute) else null
}

/** From and until when ("de 9h à 12h", "entre 08h30 et 12h30", "9:00 - 17:00"), or only from when ("à partir de 8 h"). */
internal fun outageHoursIn(text: String): Pair<LocalTime?, LocalTime?> {
    val t = fold(text)
    RANGE.findAll(t).forEach { m ->
        val g = m.groupValues
        val from = time(g[1], g[2])
        val to = time(g[3], g[4])
        if (from != null && to != null) return from to to
    }
    FROM.findAll(t).forEach { m -> time(m.groupValues[1], m.groupValues[2])?.let { return it to null } }
    return null to null
}

/**
 * A planned cut in a message, or null: it must speak of a cut of electricity, water or gas on a day to come (within two months), and,
 * unless [explicit] (the user handing the notice over), come from those who send such notices or speak of works, so that a news
 * headline about a blackout elsewhere is not noted.
 */
internal fun parseOutage(text: String, today: LocalDate, explicit: Boolean = false, source: String = ""): Outage? {
    val kind = outageKindIn(text) ?: return null
    if (!explicit && !NOTICE.containsMatchIn(fold(text))) return null
    val date = outageDateIn(text, today) ?: return null
    if (date.isBefore(today) || date.isAfter(today.plusDays(62))) return null
    val (start, end) = outageHoursIn(text)
    return Outage(kind, date, start, end, source.take(60))
}

/** The noted cuts with [o] added: one per kind, day and start, the newer one kept; the past ones dropped; at most 30, soonest first. */
internal fun addOutage(all: List<Outage>, o: Outage, today: LocalDate): List<Outage> =
    (all.filterNot { it.key == o.key } + o).let { upcomingOutages(it, today) }.take(30)

/** The cuts of today and later, soonest first. */
internal fun upcomingOutages(all: List<Outage>, today: LocalDate): List<Outage> =
    all.filter { !it.date.isBefore(today) }.sortedWith(compareBy<Outage> { it.date }.thenBy { it.start ?: LocalTime.MIN })

internal fun outagesToJson(all: List<Outage>): String = buildJsonArray {
    all.forEach { o ->
        add(buildJsonObject {
            put("kind", o.kind.key)
            put("date", o.date.toString())
            o.start?.let { put("start", it.toString()) }
            o.end?.let { put("end", it.toString()) }
            put("source", o.source)
            o.reminderId?.let { put("reminder", it) }
        })
    }
}.toString()

internal fun outagesFromJson(json: String?): List<Outage> = try {
    (Json.parseToJsonElement(json ?: "[]") as JsonArray).mapNotNull { e ->
        val o = e as? JsonObject ?: return@mapNotNull null
        fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
        val kind = OutageKind.values().firstOrNull { it.key == s("kind") } ?: return@mapNotNull null
        try {
            Outage(
                kind, LocalDate.parse(s("date")),
                s("start")?.let { LocalTime.parse(it) }, s("end")?.let { LocalTime.parse(it) },
                s("source").orEmpty(), (o["reminder"] as? JsonPrimitive)?.intOrNull,
            )
        } catch (_: Exception) {
            null
        }
    }
} catch (_: Exception) {
    emptyList()
}

/**
 * When to remind of a cut: the evening before at 20 h; when that is gone, an hour before it starts (or at 7 h the same day when the
 * notice gives no hour); null when even that is gone.
 */
internal fun outageReminderAt(o: Outage, now: LocalDateTime): LocalDateTime? {
    val eve = o.date.minusDays(1).atTime(20, 0)
    if (eve.isAfter(now)) return eve
    val sameDay = o.start?.let { o.date.atTime(it).minusHours(1) } ?: o.date.atTime(7, 0)
    return sameDay.takeIf { it.isAfter(now.plusMinutes(5)) }
}

/** "8 h", "8 h 30". */
internal fun hourWords(t: LocalTime): String = if (t.minute == 0) "${t.hour} h" else "${t.hour} h ${"%02d".format(t.minute)}"

/** "aujourd’hui", "demain", "jeudi 9 octobre". */
internal fun dayWords(date: LocalDate, today: LocalDate): String = when (date) {
    today -> "aujourd’hui"
    today.plusDays(1) -> "demain"
    else -> date.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.FRANCE))
}

private fun hoursPart(o: Outage): String = when {
    o.start != null && o.end != null -> " de ${hourWords(o.start)} à ${hourWords(o.end)}"
    o.start != null -> " à partir de ${hourWords(o.start)}"
    else -> ""
}

/** "coupure d’eau demain de 8 h à 12 h". */
internal fun outageLine(o: Outage, today: LocalDate): String = "coupure ${o.kind.words} ${dayWords(o.date, today)}${hoursPart(o)}"

/** What the reminder says, the day being [day] as seen when it rings. */
internal fun reminderText(o: Outage, at: LocalDate): String =
    outageLine(o, at).replaceFirstChar { it.uppercase() } + " : " + o.kind.advice + "."

/** The cuts noted, in words. */
internal fun outagesWords(all: List<Outage>, today: LocalDate): String {
    val next = upcomingOutages(all, today)
    if (next.isEmpty()) return "Aucune coupure prévue notée."
    val head = if (next.size == 1) "Une coupure prévue : " else "${next.size} coupures prévues : "
    return head + next.take(5).joinToString(" ; ") { outageLine(it, today) + if (it.source.isNotEmpty()) " (vue dans ${it.source})" else "" } + "."
}

/** One sentence for the morning briefing: the cuts of today and tomorrow, or null. */
internal fun outagesBriefingLine(all: List<Outage>, today: LocalDate): String? {
    val soon = upcomingOutages(all, today).filter { !it.date.isAfter(today.plusDays(1)) }
    if (soon.isEmpty()) return null
    return soon.joinToString(" ; ") { outageLine(it, today) }.replaceFirstChar { it.uppercase() }
}
