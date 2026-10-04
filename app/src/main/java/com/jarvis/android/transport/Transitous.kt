package com.jarvis.android.transport

import com.jarvis.android.location.distanceKm
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId

/*
 * Next departures without any key, through Transitous (api.transitous.org): a community MOTIS server fed with the open
 * timetables of transport.data.gouv.fr and their real-time feeds — SNCF trains, and the buses, trams and metros of most French
 * networks. Its terms: an open-source, non-commercial app, light on requests, a User-Agent naming the app and a contact, and a
 * visible link to transitous.org/sources. Everything here is pure: reading the answers and saying them.
 */

internal const val TRANSITOUS_BASE = "https://api.transitous.org/api"
internal const val TRANSITOUS_SOURCES = "https://transitous.org/sources/"

private fun JsonObject.str(key: String): String = this[key]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }.orEmpty()
private fun JsonObject.num(key: String): Double? = this[key]?.let { runCatching { it.jsonPrimitive.doubleOrNull }.getOrNull() }
private fun JsonObject.flag(key: String): Boolean = this[key]?.let { runCatching { it.jsonPrimitive.booleanOrNull }.getOrNull() } ?: false
private fun JsonObject.arr(key: String): JsonArray = (this[key] as? JsonArray) ?: JsonArray(emptyList())
private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

/** Which departures are wanted: trains, local transport (bus, tram, metro…), or all. */
internal enum class TransitKind { TRAIN, LOCAL, ANY }

private val RAIL_MODES = setOf("RAIL", "HIGHSPEED_RAIL", "LONG_DISTANCE", "NIGHT_RAIL", "REGIONAL_FAST_RAIL", "REGIONAL_RAIL", "SUBURBAN")

internal fun TransitKind.accepts(mode: String): Boolean = when (this) {
    TransitKind.ANY -> true
    TransitKind.TRAIN -> mode in RAIL_MODES
    TransitKind.LOCAL -> mode !in RAIL_MODES && mode != "AIRPLANE"
}

internal data class TransitStop(val id: String, val name: String, val lat: Double, val lon: Double, val modes: Set<String>)

/** The stops of a geocode or reverse-geocode answer (a list of matches), in the server's order. */
internal fun parseTransitStops(body: String): List<TransitStop> = try {
    (Json.parseToJsonElement(body) as JsonArray).mapNotNull { m0 ->
        val m = m0 as? JsonObject ?: return@mapNotNull null
        if (m.str("type") != "STOP") return@mapNotNull null
        val lat = m.num("lat") ?: return@mapNotNull null
        val lon = m.num("lon") ?: return@mapNotNull null
        val id = m.str("id").ifBlank { return@mapNotNull null }
        TransitStop(id, m.str("name"), lat, lon, m.arr("modes").mapNotNull { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }.toSet())
    }
} catch (_: Exception) {
    emptyList()
}

/**
 * The stops worth asking, nearest first, at most [maxKm] away: those known to serve [kind] before the others (older servers
 * do not say which modes a stop serves; then the distance alone decides).
 */
internal fun stopsToTry(stops: List<TransitStop>, lat: Double, lon: Double, kind: TransitKind, maxKm: Double): List<TransitStop> =
    stops.map { it to distanceKm(lat, lon, it.lat, it.lon) }
        .filter { it.second <= maxKm }
        .distinctBy { it.first.name.lowercase() }
        .sortedWith(compareBy({ s -> if (s.first.modes.isNotEmpty() && s.first.modes.none { kind.accepts(it) }) 1 else 0 }, { it.second }))
        .map { it.first }

internal data class LiveDeparture(
    val mode: String,
    val line: String,
    val headsign: String,
    /** The time it leaves, with the real-time delay when known. */
    val time: Instant,
    val planned: Instant?,
    val realTime: Boolean,
    val cancelled: Boolean,
    val track: String,
    val stop: String,
)

internal data class StopBoard(val stop: String, val departures: List<LiveDeparture>)

private fun instant(s: String): Instant? = try {
    if (s.isBlank()) null else OffsetDateTime.parse(s).toInstant()
} catch (_: Exception) {
    null
}

/** A stoptimes answer: the stop's name and its departures, in time order. */
internal fun parseStopTimes(body: String): StopBoard? = try {
    val root = Json.parseToJsonElement(body).jsonObject
    val list = root.arr("stopTimes").mapNotNull { t0 ->
        val t = t0 as? JsonObject ?: return@mapNotNull null
        val p = t.obj("place") ?: return@mapNotNull null
        val planned = instant(p.str("scheduledDeparture"))
        val time = instant(p.str("departure")) ?: planned ?: return@mapNotNull null
        LiveDeparture(
            mode = t.str("mode"),
            line = t.str("displayName").ifBlank { t.str("routeShortName") }.ifBlank { t.str("tripShortName") }.trim(),
            headsign = t.str("headsign").ifBlank { t.obj("tripTo")?.str("name").orEmpty() }.trim(),
            time = time,
            planned = planned,
            realTime = t.flag("realTime"),
            cancelled = t.flag("cancelled") || t.flag("tripCancelled"),
            track = p.str("track").ifBlank { p.str("scheduledTrack") }.trim(),
            stop = p.str("name"),
        )
    }.sortedBy { it.time }
    StopBoard(root.obj("place")?.str("name").orEmpty().ifBlank { list.firstOrNull()?.stop.orEmpty() }, list)
} catch (_: Exception) {
    null
}

/** "Bus 12", "Tram A", "Métro 1", "TER 857300", "Train K12": the mode in words, unless the line's name already says it. */
internal fun lineWords(mode: String, line: String): String {
    val word = when (mode) {
        "BUS" -> "Bus"
        "COACH" -> "Car"
        "TRAM" -> "Tram"
        "SUBWAY", "METRO" -> "Métro"
        "FERRY" -> "Bateau"
        "FUNICULAR" -> "Funiculaire"
        "AERIAL_LIFT", "AREAL_LIFT", "CABLE_CAR" -> "Téléphérique"
        in RAIL_MODES -> "Train"
        else -> ""
    }
    if (line.isBlank()) return word
    if (word.isEmpty() || line.startsWith(word, ignoreCase = true)) return line
    // Rail lines often carry their own kind: "TER 857300", "TGV INOUI 8704", "RER B", "Transilien L".
    if (word == "Train" && Regex("^\\p{L}{2,}").containsMatchIn(line)) return line
    return "$word $line"
}

/** "19 h 42 (dans 4 min, retard 2 min) Bus 12 vers Gare Sud, voie 3", or "supprimé" when it will not run. */
internal fun describeLiveDeparture(d: LiveDeparture, now: Instant, zone: ZoneId): String {
    val at = LocalDateTime.ofInstant(d.time, zone)
    val notes = buildList {
        if (d.cancelled) {
            add("supprimé")
        } else {
            val inMin = Duration.between(now, d.time).toMinutes()
            if (inMin in 0..59) add(if (inMin == 0L) "maintenant" else "dans $inMin min")
            d.planned?.let { Duration.between(it, d.time).toMinutes() }?.takeIf { it > 0 }?.let { add("retard $it min") }
        }
    }
    val what = lineWords(d.mode, d.line)
    return hm(at) + (if (notes.isNotEmpty()) " (${notes.joinToString(", ")})" else "") +
        " ${if (what.isNotBlank()) "$what " else ""}vers ${d.headsign.ifBlank { "?" }}" + (if (d.track.isNotBlank()) ", voie ${d.track}" else "")
}

/** The answer for one stop: its next [count] departures of [kind], said, or null when it has none. */
internal fun describeBoard(board: StopBoard, kind: TransitKind, now: Instant, zone: ZoneId, count: Int = 6): String? {
    val list = board.departures.filter { kind.accepts(it.mode) && !it.time.isBefore(now.minusSeconds(60)) }.take(count)
    if (list.isEmpty()) return null
    val planned = if (list.none { it.realTime }) " (horaires prévus : pas de temps réel sur ce réseau)" else ""
    return "Prochains départs de ${board.stop}$planned :\n" + list.joinToString("\n") { describeLiveDeparture(it, now, zone) }
}
