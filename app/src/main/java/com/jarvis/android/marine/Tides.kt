package com.jarvis.android.marine

import com.jarvis.android.location.distanceKm
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * Tides and marine weather, without any key. The sea level comes from Open-Meteo's Marine API
 * (`sea_level_height_msl`, an ocean model that includes the tide, about 8 km wide cells): the high and low
 * waters are its turning points, refined between the hourly values. French tide coefficients are defined at
 * Brest for the whole coast, so they are worked out from the same series at Brest: 100 is a mean equinoctial
 * spring tide (6.10 m of range). The model's cell off Brest does not have exactly the harbour's range, so its
 * ranges are first scaled by the last lunar month's mean, which is coefficient 70 on average over the years.
 * No Android imports here, so it can be unit-tested on the JVM.
 */

internal data class Port(val name: String, val lat: Double, val lon: Double)

/** Main French tide ports, Channel to Basque coast, then the Mediterranean (tiny tide there) and Corsica. */
internal val PORTS = listOf(
    Port("Dunkerque", 51.05, 2.36), Port("Calais", 50.97, 1.86), Port("Boulogne-sur-Mer", 50.73, 1.58),
    Port("Le Touquet", 50.53, 1.58), Port("Saint-Valery-sur-Somme", 50.19, 1.63), Port("Le Tréport", 50.06, 1.37),
    Port("Dieppe", 49.93, 1.08), Port("Fécamp", 49.76, 0.37), Port("Le Havre", 49.48, 0.11),
    Port("Honfleur", 49.42, 0.23), Port("Deauville-Trouville", 49.37, 0.07), Port("Ouistreham", 49.29, -0.25),
    Port("Arromanches", 49.34, -0.62), Port("Port-en-Bessin", 49.35, -0.75), Port("Saint-Vaast-la-Hougue", 49.59, -1.26),
    Port("Barfleur", 49.67, -1.26), Port("Cherbourg", 49.65, -1.62), Port("Goury", 49.71, -1.94),
    Port("Carteret", 49.37, -1.79), Port("Granville", 48.83, -1.60), Port("Le Mont-Saint-Michel", 48.64, -1.51),
    Port("Cancale", 48.67, -1.85), Port("Saint-Malo", 48.64, -2.03), Port("Dinard", 48.63, -2.06),
    Port("Saint-Cast", 48.64, -2.25), Port("Erquy", 48.63, -2.47), Port("Saint-Brieuc (Le Légué)", 48.53, -2.73),
    Port("Binic", 48.60, -2.82), Port("Paimpol", 48.78, -3.04), Port("Bréhat", 48.85, -3.00),
    Port("Perros-Guirec", 48.82, -3.44), Port("Trébeurden", 48.77, -3.58), Port("Roscoff", 48.72, -3.97),
    Port("Morlaix", 48.68, -3.89), Port("L'Aber Wrac'h", 48.60, -4.56), Port("Le Conquet", 48.36, -4.78),
    Port("Ouessant", 48.46, -5.09), Port("Brest", 48.38, -4.49), Port("Camaret-sur-Mer", 48.28, -4.60),
    Port("Morgat", 48.22, -4.50), Port("Douarnenez", 48.10, -4.33), Port("Audierne", 48.02, -4.54),
    Port("Le Guilvinec", 47.79, -4.28), Port("Loctudy", 47.83, -4.17), Port("Bénodet", 47.87, -4.11),
    Port("Concarneau", 47.87, -3.92), Port("Lorient", 47.73, -3.36), Port("Port-Louis", 47.71, -3.36),
    Port("Étel", 47.65, -3.21), Port("Quiberon", 47.48, -3.12), Port("Belle-Île (Le Palais)", 47.35, -3.15),
    Port("La Trinité-sur-Mer", 47.58, -3.03), Port("Port-Navalo", 47.55, -2.92), Port("Vannes", 47.65, -2.76),
    Port("Le Croisic", 47.30, -2.51), Port("La Baule (Le Pouliguen)", 47.27, -2.43), Port("Saint-Nazaire", 47.27, -2.20),
    Port("Pornic", 47.11, -2.11), Port("Noirmoutier", 47.00, -2.25), Port("L'Île-d'Yeu (Port-Joinville)", 46.73, -2.35),
    Port("Saint-Gilles-Croix-de-Vie", 46.69, -1.94), Port("Les Sables-d'Olonne", 46.49, -1.79),
    Port("La Tranche-sur-Mer", 46.34, -1.43), Port("La Rochelle", 46.15, -1.16), Port("Saint-Martin-de-Ré", 46.21, -1.37),
    Port("Île d'Aix", 46.01, -1.17), Port("Rochefort", 45.94, -0.96), Port("La Cotinière (Oléron)", 45.91, -1.33),
    Port("Royan", 45.62, -1.03), Port("Pointe de Grave", 45.57, -1.06), Port("Arcachon", 44.66, -1.17),
    Port("Cap Ferret", 44.63, -1.25), Port("Capbreton", 43.65, -1.45), Port("Bayonne (Boucau)", 43.53, -1.51),
    Port("Biarritz", 43.48, -1.56), Port("Saint-Jean-de-Luz", 43.39, -1.67), Port("Hendaye", 43.37, -1.78),
    Port("Port-Vendres", 42.52, 3.11), Port("Sète", 43.40, 3.70), Port("Port-Camargue", 43.52, 4.13),
    Port("Marseille", 43.30, 5.36), Port("Toulon", 43.12, 5.93), Port("Saint-Tropez", 43.27, 6.64),
    Port("Cannes", 43.55, 7.02), Port("Nice", 43.70, 7.29), Port("Menton", 43.78, 7.51),
    Port("Ajaccio", 41.92, 8.74), Port("Bastia", 42.70, 9.45), Port("Bonifacio", 41.39, 9.16),
)

internal val BREST = PORTS.first { it.name == "Brest" }

/** A port within [maxKm] of the point, the closest one; null when the coast is further. */
internal fun nearestPort(lat: Double, lon: Double, maxKm: Double = 50.0): Pair<Port, Double>? =
    PORTS.map { it to distanceKm(lat, lon, it.lat, it.lon) }.minByOrNull { it.second }?.takeIf { it.second <= maxKm }

/** The sea level at the asked point then at Brest, in one call; each answer gives the model cell it used. */
internal fun tidesUrl(lat: Double, lon: Double): String =
    "https://marine-api.open-meteo.com/v1/marine".toHttpUrl().newBuilder()
        .addQueryParameter("latitude", "%.4f,%.4f".format(Locale.US, lat, BREST.lat))
        .addQueryParameter("longitude", "%.4f,%.4f".format(Locale.US, lon, BREST.lon))
        .addQueryParameter("hourly", "sea_level_height_msl")
        .addQueryParameter("past_days", "31").addQueryParameter("forecast_days", "3")
        .addQueryParameter("timeformat", "unixtime").addQueryParameter("cell_selection", "sea")
        .build().toString()

internal fun marineWeatherUrl(lat: Double, lon: Double): String =
    "https://marine-api.open-meteo.com/v1/marine".toHttpUrl().newBuilder()
        .addQueryParameter("latitude", "%.4f".format(Locale.US, lat)).addQueryParameter("longitude", "%.4f".format(Locale.US, lon))
        .addQueryParameter("current", "wave_height,wave_direction,wave_period,sea_surface_temperature")
        .addQueryParameter("hourly", "wave_height").addQueryParameter("forecast_days", "2")
        .addQueryParameter("timeformat", "unixtime").addQueryParameter("cell_selection", "sea")
        .build().toString()

internal fun seaWindUrl(lat: Double, lon: Double): String =
    "https://api.open-meteo.com/v1/forecast".toHttpUrl().newBuilder()
        .addQueryParameter("latitude", "%.4f".format(Locale.US, lat)).addQueryParameter("longitude", "%.4f".format(Locale.US, lon))
        .addQueryParameter("current", "wind_speed_10m,wind_direction_10m,wind_gusts_10m")
        .addQueryParameter("hourly", "wind_gusts_10m").addQueryParameter("forecast_days", "2")
        .addQueryParameter("wind_speed_unit", "kn").addQueryParameter("timeformat", "unixtime")
        .build().toString()

/** Where the model's sea cell for the first asked point is. */
internal fun modelCell(body: String): Pair<Double, Double> {
    val root = Json.parseToJsonElement(body)
    val first = ((root as? JsonArray)?.firstOrNull() ?: root) as? JsonObject ?: error("Invalid marine data")
    return (first["latitude"].num() ?: error("Missing latitude")) to (first["longitude"].num() ?: error("Missing longitude"))
}

internal data class SeaLevel(val lat: Double, val lon: Double, val times: List<Long>, val heights: List<Double?>)

private fun JsonElement?.num(): Double? = (this as? JsonPrimitive)?.doubleOrNull

/** One location's hourly series; the API answers an array when several points were asked. */
internal fun parseSeaLevels(body: String): List<SeaLevel> {
    val root = Json.parseToJsonElement(body)
    val items = (root as? JsonArray)?.toList() ?: listOf(root)
    return items.map { item ->
        val o = item as? JsonObject ?: error("Invalid marine data")
        require(o["error"]?.toString() != "true")
        val hourly = o["hourly"] as? JsonObject ?: error("Missing hourly sea level")
        val times = (hourly["time"] as? JsonArray ?: error("Missing times")).map { (it as JsonPrimitive).longOrNull ?: error("Bad time") }
        val heights = (hourly["sea_level_height_msl"] as? JsonArray ?: error("Missing sea level")).map { it.num() }
        SeaLevel(o["latitude"].num() ?: 0.0, o["longitude"].num() ?: 0.0, times, heights)
    }
}

internal data class TideExtreme(val epochSec: Long, val height: Double, val high: Boolean)

/**
 * High and low waters: the hourly turning points, each refined with the parabola through it and its two neighbours
 * (good to a few minutes for a smooth tide curve). A flat top of two equal values counts once.
 */
internal fun findExtremes(times: List<Long>, heights: List<Double?>): List<TideExtreme> {
    val out = mutableListOf<TideExtreme>()
    for (i in 1 until times.size - 1) {
        val a = heights[i - 1] ?: continue
        val b = heights[i] ?: continue
        val c = heights[i + 1] ?: continue
        val high = b > a && b >= c
        val low = b < a && b <= c
        if (!high && !low) continue
        val denom = a - 2 * b + c
        val offset = if (denom == 0.0) 0.0 else (0.5 * (a - c) / denom).coerceIn(-0.5, 0.5)
        val step = times[i + 1] - times[i]
        val t = times[i] + (offset * step).roundToLong()
        val h = b - 0.25 * (a - c) * offset
        // a tide alternates; a small wobble (surge, model noise) inside one half-tide is dropped
        val last = out.lastOrNull()
        if (last != null && last.high == high) {
            if ((high && h > last.height) || (!high && h < last.height)) out[out.lastIndex] = TideExtreme(t, h, high)
            continue
        }
        if (last != null && abs(h - last.height) < 0.05 && t - last.epochSec < 3 * 3600) { out.removeAt(out.lastIndex); continue }
        out += TideExtreme(t, h, high)
    }
    return out
}

private fun Double.roundToLong(): Long = Math.round(this)

/** The tide's range at each high water: its height above the mean of the low waters on either side. */
internal fun highWaterRanges(extremes: List<TideExtreme>): List<Pair<TideExtreme, Double>> =
    extremes.withIndex().filter { it.value.high }.mapNotNull { (i, hw) ->
        val lows = listOfNotNull(extremes.getOrNull(i - 1), extremes.getOrNull(i + 1)).filter { !it.high }
        if (lows.isEmpty()) null else hw to (hw.height - lows.map { it.height }.average())
    }

/** Mean equinoctial spring range at Brest, the coefficient's 100. */
private const val BREST_UNIT_RANGE = 6.10

/** Long-term mean coefficient: the scale a whole lunar month of the model's ranges is brought to. */
private const val MEAN_COEFFICIENT = 70.0

private const val LUNAR_MONTH_SEC = 2_551_443L

/**
 * Brest's coefficient at each of its high waters. With a lunar month of past ranges ([nowSec] back), the model's
 * ranges are scaled so that their mean is coefficient 70; without, they are taken as they are.
 */
internal fun brestCoefficients(brest: List<TideExtreme>, nowSec: Long): List<Pair<Long, Int>> {
    val ranges = highWaterRanges(brest)
    val month = ranges.filter { it.first.epochSec in (nowSec - LUNAR_MONTH_SEC)..nowSec }.map { it.second }
    val unit = if (month.size >= 50) month.average() * 100 / MEAN_COEFFICIENT else BREST_UNIT_RANGE
    return ranges.map { (hw, r) -> hw.epochSec to (100 * r / unit).roundToInt().coerceIn(20, 120) }
}

internal fun coefficientWords(c: Int): String = when {
    c >= 100 -> "très grande marée"
    c >= 90 -> "grande marée"
    c >= 70 -> "vives-eaux"
    c >= 45 -> "marée moyenne"
    else -> "mortes-eaux"
}

private fun oneDecimal(v: Double) = String.format(Locale.FRANCE, "%.1f", v)

private fun hm(epochSec: Long, zone: ZoneId): String {
    val t = Instant.ofEpochSecond(epochSec).atZone(zone)
    return "%d h %02d".format(t.hour, t.minute)
}

private fun dayWord(day: LocalDate, today: LocalDate): String = when (day) {
    today -> "aujourd'hui"
    today.plusDays(1) -> "demain"
    else -> "après-demain"
}

/**
 * The spoken answer: today's and tomorrow's high and low waters at [label], the range of each high water, and
 * each day's coefficients.
 */
internal fun formatTides(body: String, label: String, nowSec: Long, zone: ZoneId): String {
    val levels = parseSeaLevels(body)
    require(levels.size >= 2)
    val port = levels[0]
    val portExtremes = findExtremes(port.times, port.heights)
    val brestExtremes = findExtremes(levels[1].times, levels[1].heights)
    val today = Instant.ofEpochSecond(nowSec).atZone(zone).toLocalDate()
    val days = listOf(today, today.plusDays(1))
    fun dayOf(sec: Long) = Instant.ofEpochSecond(sec).atZone(zone).toLocalDate()
    val ranges = highWaterRanges(portExtremes).associate { it.first.epochSec to it.second }
    val upcoming = portExtremes.filter { dayOf(it.epochSec) in days }
    if (upcoming.isEmpty()) return "Marées indisponibles pour $label : le modèle n'a pas de niveau de la mer ici."
    val coefficients = brestCoefficients(brestExtremes, nowSec).filter { dayOf(it.first) in days }
    val maxRange = upcoming.filter { it.high }.mapNotNull { ranges[it.epochSec] }.maxOrNull() ?: 0.0
    return buildString {
        append("Marées à $label")
        if (maxRange < 0.5) append(" (marée très faible ici, quelques dizaines de centimètres au plus)")
        append(". ")
        for (day in days) {
            val list = upcoming.filter { dayOf(it.epochSec) == day }
            if (list.isEmpty()) continue
            append(dayWord(day, today).replaceFirstChar { it.uppercase() }).append(" : ")
            append(list.joinToString(", ") { e ->
                val past = if (day == today && e.epochSec < nowSec) " (passée)" else ""
                if (e.high) "pleine mer ${hm(e.epochSec, zone)}$past" + (ranges[e.epochSec]?.takeIf { maxRange >= 0.5 }
                    ?.let { " (marnage ${oneDecimal(it)} m)" } ?: "")
                else "basse mer ${hm(e.epochSec, zone)}$past"
            })
            val cs = coefficients.filter { dayOf(it.first) == day }.map { it.second }
            if (cs.isNotEmpty()) append(" ; coefficient ").append(cs.joinToString(" puis ")).append(" (").append(coefficientWords(cs.max())).append(")")
            append(". ")
        }
        append("Heures et coefficients calculés d'un modèle océanique (Open-Meteo), à une vingtaine de minutes près : pour naviguer " +
            "ou pêcher à pied, vérifiez l'annuaire officiel du SHOM.")
    }.trim()
}

/** Beaufort force from knots. */
internal fun beaufort(knots: Double): Int = listOf(1, 4, 7, 11, 17, 22, 28, 34, 41, 48, 56, 64).count { knots >= it }

private val SEA_STATES = listOf(0.1 to "calme", 0.5 to "ridée", 1.25 to "belle", 2.5 to "peu agitée", 4.0 to "agitée", 6.0 to "forte", 9.0 to "très forte", 14.0 to "grosse")

/** The Douglas sea state words for a significant wave height (m). */
internal fun seaState(waveM: Double): String = SEA_STATES.firstOrNull { waveM < it.first }?.second ?: "énorme"

/** Where wind or waves come from, ready to follow "vent" or "vagues": "du nord", "d'ouest"... */
internal fun windFrom(deg: Double): String {
    val names = listOf("du nord", "du nord-est", "d'est", "du sud-est", "du sud", "du sud-ouest", "d'ouest", "du nord-ouest")
    return names[(((deg % 360) + 360 + 22.5) / 45).toInt() % 8]
}

/** Now, and the worst of the next 12 hours, from the marine and the wind answers. */
internal fun formatMarineWeather(marineBody: String, windBody: String, label: String, nowSec: Long): String {
    val marine = Json.parseToJsonElement(marineBody) as? JsonObject ?: error("Invalid marine data")
    val wind = Json.parseToJsonElement(windBody) as? JsonObject ?: error("Invalid wind data")
    val mc = marine["current"] as? JsonObject ?: error("Missing current sea")
    val wc = wind["current"] as? JsonObject ?: error("Missing current wind")
    fun next12(o: JsonObject, key: String): Double? {
        val h = o["hourly"] as? JsonObject ?: return null
        val t = (h["time"] as? JsonArray)?.map { (it as JsonPrimitive).longOrNull ?: 0L } ?: return null
        val v = (h[key] as? JsonArray)?.map { it.num() } ?: return null
        return t.indices.filter { t[it] in nowSec..(nowSec + 12 * 3600) }.mapNotNull { v.getOrNull(it) }.maxOrNull()
    }
    val wave = mc["wave_height"].num()
    val parts = mutableListOf<String>()
    wc["wind_speed_10m"].num()?.let { kn ->
        val dir = wc["wind_direction_10m"].num()?.let { " ${windFrom(it)}" } ?: ""
        val gusts = wc["wind_gusts_10m"].num()?.let { ", rafales ${it.roundToInt()} nœuds" } ?: ""
        parts += "vent$dir ${kn.roundToInt()} nœuds (force ${beaufort(kn)})$gusts"
    }
    if (wave != null) {
        val dir = mc["wave_direction"].num()?.let { " ${windFrom(it)}" } ?: ""
        val period = mc["wave_period"].num()?.let { ", période ${it.roundToInt()} s" } ?: ""
        parts += "mer ${seaState(wave)}, vagues ${oneDecimal(wave)} m$dir$period"
    }
    mc["sea_surface_temperature"].num()?.let { parts += "eau à ${it.roundToInt()} °C" }
    if (parts.isEmpty()) return "Météo marine indisponible pour $label : pas de données de mer ici."
    return buildString {
        append("Météo marine à $label : ").append(parts.joinToString(", ")).append(".")
        val later = listOfNotNull(
            next12(marine, "wave_height")?.takeIf { wave == null || it >= wave + 0.3 }?.let { "vagues jusqu'à ${oneDecimal(it)} m" },
            next12(wind, "wind_gusts_10m")?.let { g -> "rafales jusqu'à ${g.roundToInt()} nœuds (force ${beaufort(g)})".takeIf { beaufort(g) >= 6 } },
        )
        if (later.isNotEmpty()) append(" Dans les 12 heures : ").append(later.joinToString(", ")).append(".")
        append(" Modèle Open-Meteo, pas un bulletin officiel : pour sortir en mer, le bulletin côtier de Météo-France fait foi.")
    }
}
