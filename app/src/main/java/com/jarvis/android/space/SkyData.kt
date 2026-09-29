package com.jarvis.android.space

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.sqrt

/*
 * What the live sky view shows (SkyView.kt): the satellites and the aircraft around the user, where they are in their sky, and every
 * detail the free sources give. Aircraft: adsb.lol (live positions from volunteers' receivers, no key), then adsbdb for the one chosen
 * (its airline and route from its flight number, its model, owner and a photo from its transponder address).
 */

/** An aircraft as adsb.lol reports it (feet, knots, feet per minute, as aviation says; converted when shown). */
internal data class Aircraft(
    val hex: String,
    val flight: String,
    val registration: String,
    val typeCode: String,
    val latitude: Double,
    val longitude: Double,
    val altitudeFt: Double?,
    val onGround: Boolean,
    val speedKt: Double?,
    val trackDeg: Double?,
    val verticalFpm: Double?,
    val squawk: String,
    val category: String,
    val emergency: String,
) {
    val altitudeM: Double? get() = altitudeFt?.let { it * 0.3048 }
    val speedKmh: Double? get() = speedKt?.let { it * 1.852 }
    val verticalMs: Double? get() = verticalFpm?.let { it * 0.00508 }
    val label: String get() = flight.ifBlank { registration.ifBlank { hex.uppercase() } }
}

/** The aircraft of an adsb.lol answer ({"ac":[…]}), those with a position. */
internal fun parseAdsbLol(json: String): List<Aircraft> {
    val list = try { Json.parseToJsonElement(json).jsonObject["ac"] as? JsonArray } catch (_: Exception) { null } ?: return emptyList()
    return list.mapNotNull { e ->
        val o = e as? JsonObject ?: return@mapNotNull null
        fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        fun d(k: String) = (o[k] as? JsonPrimitive)?.doubleOrNull
        val lat = d("lat") ?: return@mapNotNull null
        val lon = d("lon") ?: return@mapNotNull null
        val altRaw = s("alt_baro")
        Aircraft(
            hex = s("hex").lowercase(), flight = s("flight"), registration = s("r"), typeCode = s("t"), latitude = lat, longitude = lon,
            altitudeFt = altRaw.toDoubleOrNull() ?: d("alt_geom"), onGround = altRaw == "ground",
            speedKt = d("gs"), trackDeg = d("track") ?: d("true_heading"), verticalFpm = d("baro_rate") ?: d("geom_rate"),
            squawk = s("squawk"), category = s("category"), emergency = s("emergency").takeIf { it != "none" }.orEmpty(),
        )
    }
}

/** What an aircraft's category code says ("A3" a large airliner…). */
internal fun categoryWords(code: String): String = when (code.uppercase()) {
    "A1" -> "avion léger"
    "A2" -> "petit avion"
    "A3" -> "avion de ligne"
    "A4" -> "gros porteur (757)"
    "A5" -> "très gros porteur"
    "A6" -> "avion très rapide"
    "A7" -> "hélicoptère"
    "B1" -> "planeur"
    "B2" -> "ballon"
    "B4" -> "ULM"
    "B6" -> "drone"
    "C1", "C2" -> "véhicule au sol"
    else -> ""
}

/** What a transponder code means when it is one of the three distress codes, else "". */
internal fun squawkWords(squawk: String): String = when (squawk) {
    "7500" -> "détournement déclaré"
    "7600" -> "panne radio"
    "7700" -> "urgence déclarée"
    else -> ""
}

/** An airline and its route, from adsbdb's answer for a flight number. */
internal data class Route(val airline: String, val originCity: String, val originAirport: String, val originCode: String, val destinationCity: String, val destinationAirport: String, val destinationCode: String)

internal fun parseRoute(json: String): Route? = try {
    val r = Json.parseToJsonElement(json).jsonObject["response"]?.jsonObject?.get("flightroute")?.jsonObject ?: return null
    fun o(k: String) = r[k] as? JsonObject
    fun s(obj: JsonObject?, k: String) = (obj?.get(k) as? JsonPrimitive)?.contentOrNull.orEmpty()
    Route(
        airline = s(o("airline"), "name"),
        originCity = s(o("origin"), "municipality"), originAirport = s(o("origin"), "name"), originCode = s(o("origin"), "iata_code"),
        destinationCity = s(o("destination"), "municipality"), destinationAirport = s(o("destination"), "name"), destinationCode = s(o("destination"), "iata_code"),
    )
} catch (_: Exception) {
    null
}

/** An aircraft's model, owner and photo, from adsbdb's answer for its transponder address. */
internal data class AircraftInfo(val manufacturer: String, val model: String, val owner: String, val ownerCountry: String, val photo: String)

internal fun parseAircraftInfo(json: String): AircraftInfo? = try {
    val a = Json.parseToJsonElement(json).jsonObject["response"]?.jsonObject?.get("aircraft")?.jsonObject ?: return null
    fun s(k: String) = (a[k] as? JsonPrimitive)?.contentOrNull.orEmpty()
    AircraftInfo(s("manufacturer"), s("type"), s("registered_owner"), s("registered_owner_country_name"), s("url_photo_thumbnail").takeIf { it.startsWith("https://") }.orEmpty())
} catch (_: Exception) {
    null
}

/** Where an aircraft is in the sky of [o]. */
internal fun lookAtAircraft(o: Observer, a: Aircraft): Look = lookAt(o, Observer(a.latitude, a.longitude, (a.altitudeM ?: 0.0) / 1000.0).ecef())

/** A satellite now: where it is in the sky, how high above the Earth, how fast, whether lit. */
internal data class SatNow(val tle: Tle, val look: Look, val altitudeKm: Double, val speedKmh: Double, val lit: Boolean, val latitude: Double, val longitude: Double)

internal fun satNow(sgp: Sgp4, tle: Tle, o: Observer, timeMs: Long): SatNow? {
    val s = sgp.at(timeMs) ?: return null
    val ecef = temeToEcef(s.x, s.y, s.z, timeMs)
    val (lat, lon, alt) = subPoint(ecef)
    return SatNow(tle, lookAt(o, ecef), alt, sqrt(s.vx * s.vx + s.vy * s.vy + s.vz * s.vz) * 3600, sunlit(s, timeMs), lat, lon)
}

/** The path of a satellite in the sky of [o] from [fromMs] to [toMs], every [stepMs]: the points above the horizon (elevation, azimuth). */
internal fun skyTrack(sgp: Sgp4, o: Observer, fromMs: Long, toMs: Long, stepMs: Long = 20_000L): List<Look> {
    val out = ArrayList<Look>()
    var t = fromMs
    while (t <= toMs) {
        sgp.at(t)?.let { s -> lookAt(o, temeToEcef(s.x, s.y, s.z, t)).takeIf { it.elevationDeg > -1 }?.let { out += it } }
        t += stepMs
    }
    return out
}
