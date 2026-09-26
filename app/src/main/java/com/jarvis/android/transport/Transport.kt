package com.jarvis.android.transport

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/*
 * Public transport through the Navitia API — the one behind SNCF's own open data (api.sncf.com, trains) and navitia.io
 * (local buses, trams, metros): the same questions and the same answers, only the address and the key differ.
 * Everything here is pure: reading the answers and saying them.
 */

private val NAVITIA_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")

internal fun navitiaTime(s: String?): LocalDateTime? = try {
    s?.let { LocalDateTime.parse(it, NAVITIA_TIME) }
} catch (_: Exception) {
    null
}

internal fun toNavitia(t: LocalDateTime): String = t.format(NAVITIA_TIME)

/** "7 h 05" */
internal fun hm(t: LocalDateTime): String = if (t.minute == 0) "${t.hour} h" else "${t.hour} h ${t.minute.toString().padStart(2, '0')}"

/** "1 h 32", "45 min" */
internal fun durationWords(d: Duration): String {
    val m = d.toMinutes()
    return if (m < 60) "$m min" else "${m / 60} h" + if (m % 60 == 0L) "" else " ${(m % 60).toString().padStart(2, '0')}"
}

private fun JsonObject.str(key: String): String = this[key]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }.orEmpty()
private fun JsonObject.arr(key: String): JsonArray = (this[key] as? JsonArray) ?: JsonArray(emptyList())
private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

internal data class Stop(val id: String, val name: String)

/** The first stop area of a places or places_nearby answer. */
internal fun parseStopArea(body: String): Stop? = try {
    val root = Json.parseToJsonElement(body).jsonObject
    val places = root.arr("places").ifEmpty { root.arr("places_nearby") }
    places.map { it.jsonObject }.firstOrNull { it.str("embedded_type") == "stop_area" }?.let { Stop(it.str("id"), it.str("name")) }
} catch (_: Exception) {
    null
}

internal data class Leg(val mode: String, val code: String, val direction: String)

internal data class Journey(
    val departure: LocalDateTime,
    val arrival: LocalDateTime,
    val transfers: Int,
    val legs: List<Leg>,
    /** Minutes late at departure (0 when on time or unknown). */
    val delayMinutes: Long,
    val disrupted: Boolean,
)

/** The journeys of a journeys answer, with the trains taken and the delay at departure. */
internal fun parseJourneys(body: String): List<Journey> = try {
    val root = Json.parseToJsonElement(body).jsonObject
    root.arr("journeys").mapNotNull { j0 ->
        val j = j0.jsonObject
        val dep = navitiaTime(j.str("departure_date_time")) ?: return@mapNotNull null
        val arr = navitiaTime(j.str("arrival_date_time")) ?: return@mapNotNull null
        val sections = j.arr("sections").map { it.jsonObject }.filter { it.str("type") == "public_transport" }
        val legs = sections.map { s ->
            val d = s.obj("display_informations")
            Leg(d?.str("commercial_mode").orEmpty(), d?.str("headsign")?.ifBlank { d.str("code") }.orEmpty(), d?.str("direction").orEmpty())
        }
        val first = sections.firstOrNull()
        val planned = navitiaTime(first?.str("base_departure_date_time"))
        val real = navitiaTime(first?.str("departure_date_time"))
        val delay = if (planned != null && real != null) Duration.between(planned, real).toMinutes().coerceAtLeast(0) else 0
        // "status" is the worst effect of the disruptions on this journey, when there are any.
        Journey(dep, arr, j["nb_transfers"]?.jsonPrimitive?.intOrNull ?: (legs.size - 1).coerceAtLeast(0), legs, delay,
            j.str("status") in DISRUPTED)
    }
} catch (_: Exception) {
    emptyList()
}

private val DISRUPTED = setOf("SIGNIFICANT_DELAYS", "REDUCED_SERVICE", "NO_SERVICE", "MODIFIED_SERVICE", "DETOUR")

internal data class Departure(val time: LocalDateTime, val planned: LocalDateTime?, val mode: String, val code: String, val direction: String)

internal fun parseDepartures(body: String): List<Departure> = try {
    Json.parseToJsonElement(body).jsonObject.arr("departures").mapNotNull { d0 ->
        val d = d0.jsonObject
        val sdt = d.obj("stop_date_time") ?: return@mapNotNull null
        val time = navitiaTime(sdt.str("departure_date_time")) ?: return@mapNotNull null
        val info = d.obj("display_informations")
        Departure(time, navitiaTime(sdt.str("base_departure_date_time")), info?.str("commercial_mode").orEmpty(),
            info?.str("headsign")?.ifBlank { info.str("code") }.orEmpty(), info?.str("direction").orEmpty().substringBefore(" ("))
    }
} catch (_: Exception) {
    emptyList()
}

/** "7 h 05 → 8 h 35 (1 h 30, direct, TGV INOUI 8704)", with the delay when there is one. */
internal fun describeJourney(j: Journey): String {
    val how = if (j.transfers == 0) "direct" else "${j.transfers} correspondance${if (j.transfers > 1) "s" else ""}"
    val trains = j.legs.filter { it.mode.isNotBlank() || it.code.isNotBlank() }.joinToString(" puis ") { "${it.mode} ${it.code}".trim() }
    val late = when {
        j.delayMinutes > 0 -> ", retard de ${j.delayMinutes} min"
        j.disrupted -> ", perturbé"
        else -> ""
    }
    return "${hm(j.departure)} → ${hm(j.arrival)} (${durationWords(Duration.between(j.departure, j.arrival))}, $how${if (trains.isNotBlank()) ", $trains" else ""}$late)"
}

internal fun describeDeparture(d: Departure): String {
    val late = d.planned?.let { Duration.between(it, d.time).toMinutes() }?.takeIf { it > 0 }?.let { " (retard $it min)" }.orEmpty()
    val what = "${d.mode} ${d.code}".trim()
    return "${hm(d.time)}$late ${if (what.isNotBlank()) "$what " else ""}vers ${d.direction.ifBlank { "?" }}"
}
