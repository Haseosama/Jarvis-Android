package com.jarvis.android.wakeup

import com.jarvis.android.JarvisContainer
import com.jarvis.android.google.MailSummary
import com.jarvis.android.space.Launch
import com.jarvis.android.space.Trip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/*
 * The briefing's newer parts, each one sentence or nothing: rain coming within two hours, the important mails not read, the ISS
 * crossing the sky tonight, the user's flights today, the rocket launches today, an aurora possible tonight, today's pollens and bad air.
 * Each can be switched off.
 */

/** The parts of the briefing, by the key the user switches off ("sans les mails"). */
internal val BRIEFING_SECTIONS = linkedMapOf(
    "meteo" to "la météo", "vigilance" to "les vigilances météo et crues", "pollen" to "le risque pollen et l’air pollué", "pluie" to "la pluie qui arrive", "agenda" to "l’agenda", "rappels" to "les rappels", "depenses" to "les prélèvements et budgets",
    "uv" to "les UV élevés", "electricite" to "les jours Tempo et EcoWatt", "coupures" to "les coupures prévues", "mails" to "les mails importants", "vols" to "vos vols", "iss" to "l’ISS ce soir", "fusees" to "les lancements de fusées", "aurores" to "les aurores", "sommeil" to "la nuit de sommeil",
)

/** "mails" from "Mails", "e-mails", "courriels": the key of a part named in words, or null. */
internal fun sectionKey(words: String): String? {
    val w = com.jarvis.android.text.normalize(words)
    return when {
        w.isEmpty() -> null
        "mail" in w || "courriel" in w -> "mails"
        "pollen" in w || "allerg" in w || "pollution" in w || "qualite de l" in w || w == "air" || w.endsWith(" air") -> "pollen"
        "vigilance" in w || "alerte" in w || "crue" in w -> "vigilance"
        "coupure" in w || "travaux" in w -> "coupures"
        "tempo" in w || "ecowatt" in w || "electri" in w -> "electricite"
        "pluie" in w || "radar" in w -> "pluie"
        "meteo" in w || "temps" in w -> "meteo"
        "agenda" in w || "rendez" in w || "evenement" in w -> "agenda"
        "rappel" in w -> "rappels"
        "prelevement" in w || "budget" in w || "depense" in w -> "depenses"
        "vol" in w || "avion" in w -> "vols"
        "iss" in w || "station" in w || "satellite" in w -> "iss"
        "fusee" in w || "lancement" in w -> "fusees"
        "aurore" in w -> "aurores"
        "sommeil" in w || "nuit" in w -> "sommeil"
        "uv" == w || w.startsWith("uv ") || "creme" in w || "soleil" in w -> "uv"
        else -> null
    }
}

/** "Marie Dupont" from "Marie Dupont <marie@x.fr>", the address when there is no name. */
internal fun senderName(from: String): String = from.substringBefore('<').trim().trim('"').ifBlank { from.substringAfter('<').substringBefore('>').trim() }

/** "2 mails importants non lus : Marie Dupont (« Réunion demain »), la banque (« Votre relevé »)". */
internal fun mailsLine(mails: List<MailSummary>): String? {
    if (mails.isEmpty()) return null
    val shown = mails.take(3).joinToString(", ") { "${senderName(it.from)} (« ${it.subject.take(50)} »)" }
    return "${mails.size} mail${if (mails.size > 1) "s" else ""} important${if (mails.size > 1) "s" else ""} non lu${if (mails.size > 1) "s" else ""} : $shown" +
        if (mails.size > 3) ", et ${mails.size - 3} autre${if (mails.size > 4) "s" else ""}" else ""
}

/** "vol AF1234 aujourd’hui vers New York (JFK), départ à 10h35, porte K42". */
internal fun flightsLine(trips: List<Trip>, today: LocalDate): String? {
    val t = trips.filter { it.date == today.toString() }
    if (t.isEmpty()) return null
    return t.joinToString(" ; ") { "vol ${it.flight} aujourd’hui vers ${it.destCity.ifBlank { it.dest }} (${it.dest})" + (it.time?.let { h -> ", départ à ${h.replace(':', 'h')}" } ?: "") + (it.gate?.let { g -> ", porte $g" } ?: "") }
}

/** "lancement aujourd’hui : Crew-13 (SpaceX) à 17h10". */
internal fun launchesLine(list: List<Launch>, today: LocalDate, zone: ZoneId): String? {
    val t = list.filter { Instant.ofEpochMilli(it.netMs).atZone(zone).toLocalDate() == today && it.precision in setOf("SEC", "MIN") && it.status in setOf("Go", "TBC") }
    if (t.isEmpty()) return null
    return (if (t.size > 1) "lancements aujourd’hui : " else "lancement aujourd’hui : ") + t.take(3).joinToString(", ") {
        val h = Instant.ofEpochMilli(it.netMs).atZone(zone).toLocalTime()
        "${it.name.substringAfter(" | ")} (${it.provider}) à %02dh%02d".format(h.hour, h.minute)
    }
}

internal object BriefingExtras {
    /** Rain within two hours where the user is: the sentence, and whether it is coming. */
    suspend fun rain(ctx: JarvisContainer, lat: Double, lon: Double): Pair<String, Boolean>? = withTimeoutOrNull(8_000) {
        withContext(Dispatchers.IO) {
            try {
                val body = ctx.http.newCall(Request.Builder().url(com.jarvis.android.weather.rainForecastUrl(lat, lon)).build()).execute().use { if (it.isSuccessful) it.body?.string() else null } ?: return@withContext null
                val slots = com.jarvis.android.weather.parseRainSlots(body)
                val now = com.jarvis.android.weather.forecastNow(body)
                val soon = slots.any { !it.start.isBefore(now.minusMinutes(15)) && it.start.isBefore(now.plusHours(2)) && it.mm >= com.jarvis.android.weather.RAIN_THRESHOLD_MM }
                if (soon) com.jarvis.android.weather.describeRain(slots, now) to true else null
            } catch (_: Exception) {
                null
            }
        }
    }

    /** The official warnings in force where the user is (yellow or more), and the rivers in flood watch nearby. */
    suspend fun vigilance(ctx: JarvisContainer, lat: Double, lon: Double): String? = withTimeoutOrNull(12_000) {
        try {
            val v = com.jarvis.android.weather.Vigilance
            val dept = v.department(ctx, lat, lon) ?: return@withTimeoutOrNull null
            val warn = com.jarvis.android.weather.warningsFor(v.weather(ctx), dept.second, System.currentTimeMillis())
            val floods = com.jarvis.android.weather.floodsNear(v.floods(ctx), lat, lon)
            if (warn.isEmpty() && floods.isEmpty()) null else v.words(dept.second, warn, floods.ifEmpty { null }, ZoneId.systemDefault()).removeSuffix(".")
        } catch (_: Exception) {
            null
        }
    }

    /** Today's pollens from moderate (those the user named for the air watch, else all) and the air when bad. */
    suspend fun pollen(ctx: JarvisContainer, lat: Double, lon: Double): String? = withTimeoutOrNull(8_000) {
        try {
            com.jarvis.android.air.AirData.forecast(ctx, lat, lon)?.let { com.jarvis.android.air.airBriefingWords(it, LocalDate.now(), com.jarvis.android.air.AirWatch.pollens(ctx.appContext)) }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun mails(ctx: JarvisContainer): String? = withTimeoutOrNull(10_000) {
        try { mailsLine(com.jarvis.android.google.GoogleApi(ctx.appContext, ctx.http).mailList("is:unread is:important newer_than:1d -category:promotions", false, 8)) } catch (_: Exception) { null }
    }

    /** The ISS seen tonight (a visible pass before 6 a.m.). */
    suspend fun iss(ctx: JarvisContainer, lat: Double, lon: Double): String? = withTimeoutOrNull(10_000) {
        withContext(Dispatchers.Default) {
            try {
                val tle = com.jarvis.android.space.SatelliteTool.orbits(ctx, "stations").firstOrNull { it.name.startsWith("ISS (ZARYA)") } ?: return@withContext null
                val zone = ZoneId.systemDefault()
                val end = LocalDate.now(zone).plusDays(1).atTime(6, 0).atZone(zone).toInstant().toEpochMilli()
                val p = com.jarvis.android.space.passes(com.jarvis.android.space.Sgp4(tle), com.jarvis.android.space.Observer(lat, lon), System.currentTimeMillis(), 24)
                    .firstOrNull { it.visible && it.riseMs < end } ?: return@withContext null
                "ce soir, " + com.jarvis.android.space.passWords("l’ISS", p).replaceFirstChar { it.lowercase() }
            } catch (_: Exception) {
                null
            }
        }
    }

    suspend fun launches(ctx: JarvisContainer): String? = withTimeoutOrNull(8_000) {
        try { launchesLine(com.jarvis.android.space.Launches.upcoming(ctx), LocalDate.now(), ZoneId.systemDefault()) } catch (_: Exception) { null }
    }

    /** An aurora possible tonight from here (NOAA's forecast Kp against the Kp needed). */
    suspend fun aurora(ctx: JarvisContainer, lat: Double, lon: Double): String? = withTimeoutOrNull(8_000) {
        try {
            val zone = ZoneId.systemDefault()
            val tonight = com.jarvis.android.space.nightlyKp(com.jarvis.android.space.SpaceWeather.kpForecast(ctx), System.currentTimeMillis(), zone)[LocalDate.now(zone)] ?: return@withTimeoutOrNull null
            val max = tonight.maxOf { it.kp }
            val needed = com.jarvis.android.space.kpNeeded(com.jarvis.android.space.geomagneticLatitude(lat, lon))
            if (max >= needed) "aurore boréale possible cette nuit (Kp prévu %.0f, il en faut environ %.0f ici) : regardez vers le nord, loin des lumières".format(java.util.Locale.FRANCE, max, kotlin.math.ceil(needed)) else null
        } catch (_: Exception) {
            null
        }
    }
}
