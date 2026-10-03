package com.jarvis.android.driving

import com.jarvis.android.JarvisContainer
import com.jarvis.android.actions.Tool
import com.jarvis.android.actions.distanceKm
import com.jarvis.android.actions.objectSchema
import com.jarvis.android.actions.stringArg
import com.jarvis.android.space.MapData
import com.jarvis.android.space.SkyModes
import com.jarvis.android.space.nearestCity
import com.jarvis.android.video.VideoPanel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale

/*
 * The weather along a drive: "quel temps sur la route de Lyon ?". The route by OSRM (OpenStreetMap), a point every 40 km or so with the
 * time the car passes it, Open-Meteo's hourly forecast for each point at that hour (all points in one question), and what matters to a
 * driver: storms, snow, freezing rain, black ice, fog, heavy rain, strong gusts. Told in a few words (in the car, aloud) and drawn on the
 * world map.
 */

/** A point of the route and when the car is there. */
internal data class RoutePoint(val lat: Double, val lon: Double, val km: Double, val etaMs: Long)

/** The weather at a point at its hour, and what is dangerous in it. */
internal data class PointWeather(
    val point: RoutePoint, val place: String?, val code: Int, val rainMm: Double, val snowCm: Double, val tempC: Double?, val gustKmh: Double?, val visibilityM: Double?,
) {
    val hazards: List<String> get() = roadHazards(code, rainMm, snowCm, tempC, gustKmh, visibilityM)
}

/** The route: its line, its length and time, the points looked at, the weather there. */
internal data class RouteReport(val from: String, val to: String, val line: List<Pair<Double, Double>>, val km: Double, val durationS: Double, val points: List<PointWeather>)

/** What a driver should be told: the WMO weather code and the figures of the hour. */
internal fun roadHazards(code: Int, rainMm: Double, snowCm: Double, tempC: Double?, gustKmh: Double?, visibilityM: Double?): List<String> {
    val out = ArrayList<String>()
    when (code) {
        in 95..99 -> out += if (code >= 96) "orage avec grêle" else "orage"
        56, 57, 66, 67 -> out += "pluie verglaçante"
        in 71..77, 85, 86 -> out += "neige"
        45, 48 -> out += "brouillard" + if (code == 48) " givrant" else ""
    }
    if (snowCm >= 0.2 && "neige" !in out) out += "neige"
    if (rainMm >= 4 && out.none { it.startsWith("orage") }) out += "forte pluie"
    else if (out.isEmpty()) when {
        rainMm >= 1 -> out += if (code in 80..82) "averses" else "pluie"
        rainMm >= 0.2 || code in 51..67 || code in 80..82 -> out += if (code in 80..82) "averses faibles" else "pluie faible"
    }
    if (tempC != null && tempC <= 1 && (rainMm > 0 || snowCm > 0 || code in 45..48) && out.none { "verglaçante" in it }) out += "risque de verglas"
    if (gustKmh != null && gustKmh >= 70) out += "rafales à ${gustKmh.toInt()} km/h"
    if (visibilityM != null && visibilityM < 1_000 && out.none { it.startsWith("brouillard") }) out += "visibilité réduite (${(visibilityM / 100).toInt() * 100} m)"
    return out
}

/** Points along a line (lon, lat pairs as GeoJSON gives them) every [stepKm], with the time the car is there (at even speed). */
internal fun samplePoints(line: List<Pair<Double, Double>>, totalS: Double, startMs: Long, stepKm: Double = 40.0): List<RoutePoint> {
    if (line.isEmpty()) return emptyList()
    val cum = DoubleArray(line.size)
    for (i in 1 until line.size) cum[i] = cum[i - 1] + distanceKm(line[i - 1].second, line[i - 1].first, line[i].second, line[i].first)
    val total = cum.last().coerceAtLeast(0.001)
    val step = maxOf(stepKm, total / 14) // no more than 15 points
    val out = ArrayList<RoutePoint>()
    var next = 0.0
    for (i in line.indices) {
        if (cum[i] >= next || i == line.lastIndex) {
            out += RoutePoint(line[i].second, line[i].first, cum[i], startMs + (totalS * cum[i] / total * 1000).toLong())
            next = cum[i] + step
        }
    }
    return out
}

/** Open-Meteo's answer for several places (a list, or one object for one place): each place's hours. */
internal fun parseRouteForecast(json: String, points: List<RoutePoint>, place: (RoutePoint) -> String?): List<PointWeather> {
    val root = Json.parseToJsonElement(json)
    val each: List<JsonElement> = (root as? JsonArray) ?: listOf(root)
    return each.zip(points).mapNotNull { (e, p) ->
        val h = ((e as? JsonObject)?.get("hourly") as? JsonObject) ?: return@mapNotNull null
        fun arr(k: String) = (h[k] as? JsonArray).orEmpty()
        val times = arr("time").map { LocalDateTime.parse((it as JsonPrimitive).content).toInstant(ZoneOffset.UTC).toEpochMilli() }
        val i = times.indexOfLast { it <= p.etaMs }.takeIf { it >= 0 } ?: return@mapNotNull null
        fun d(k: String) = (arr(k).getOrNull(i) as? JsonPrimitive)?.doubleOrNull
        PointWeather(p, place(p), (arr("weather_code").getOrNull(i) as? JsonPrimitive)?.intOrNull ?: 0, d("precipitation") ?: 0.0, d("snowfall") ?: 0.0, d("temperature_2m"), d("wind_gusts_10m"), d("visibility"))
    }
}

/** The report in a few words, for the ear: "Sur 466 km (4 h 58) : pluie vers Auxerre vers 15 h ; orage près de Mâcon vers 17 h. Sinon sec." */
internal fun routeWords(r: RouteReport, zone: ZoneId = ZoneId.systemDefault()): String {
    val h = (r.durationS / 3600).toInt()
    val m = ((r.durationS % 3600) / 60).toInt()
    val head = "Trajet ${r.from} → ${r.to} : ${r.km.toInt()} km, ${if (h > 0) "$h h ${"%02d".format(m)}" else "$m min"} sans les bouchons"
    val bad = r.points.filter { it.hazards.isNotEmpty() }
    if (r.points.isEmpty()) return "$head. Météo du trajet indisponible."
    val temps = r.points.mapNotNull { it.tempC }
    val tail = if (temps.isNotEmpty()) " Températures de ${temps.min().toInt()} à ${temps.max().toInt()} °C." else ""
    if (bad.isEmpty()) return "$head. Pas de pluie ni de danger annoncé sur le trajet.$tail"
    // neighbouring points with the same trouble are said once
    val parts = ArrayList<String>()
    var last: List<String>? = null
    for (p in bad) {
        if (p.hazards == last) continue
        last = p.hazards
        val at = Instant.ofEpochMilli(p.point.etaMs).atZone(zone).toLocalTime()
        parts += p.hazards.joinToString(" et ") + (p.place?.let { " vers $it" } ?: " au km ${p.point.km.toInt()}") + " vers ${at.hour} h${if (at.minute >= 30) " 30" else ""}"
    }
    val dry = r.points.size - bad.size
    return "$head. " + parts.take(5).joinToString(" ; ").replaceFirstChar { it.uppercase() } + "." + (if (dry > 0) " Ailleurs, rien de gênant." else "") + tail
}

internal object RouteWeather {
    /** The last report, for the map. */
    @Volatile var last: RouteReport? = null

    private fun get(ctx: JarvisContainer, url: String): String? = try {
        ctx.http.newCall(Request.Builder().url(url).header("User-Agent", "Jarvis-Android").build()).execute().use { if (it.isSuccessful) it.body?.string() else null }
    } catch (_: Exception) {
        null
    }

    /** A place's name to its position (Open-Meteo's geocoding, in French). */
    suspend fun geocode(ctx: JarvisContainer, name: String): Triple<Double, Double, String>? = withContext(Dispatchers.IO) {
        val url = "https://geocoding-api.open-meteo.com/v1/search".toHttpUrl().newBuilder().addQueryParameter("name", name).addQueryParameter("count", "1").addQueryParameter("language", "fr").build()
        get(ctx, url.toString())?.let {
            val r = ((Json.parseToJsonElement(it) as? JsonObject)?.get("results") as? JsonArray)?.firstOrNull() as? JsonObject ?: return@let null
            val lat = (r["latitude"] as? JsonPrimitive)?.doubleOrNull ?: return@let null
            val lon = (r["longitude"] as? JsonPrimitive)?.doubleOrNull ?: return@let null
            Triple(lat, lon, (r["name"] as? JsonPrimitive)?.contentOrNull ?: name)
        }
    }

    /** The road from one place to another (OSRM): its line (longitude, latitude pairs, as GeoJSON), its length (km) and time (s). */
    suspend fun route(ctx: JarvisContainer, fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): Triple<List<Pair<Double, Double>>, Double, Double>? = withContext(Dispatchers.IO) {
        val route = get(ctx, "https://router.project-osrm.org/route/v1/driving/%.5f,%.5f;%.5f,%.5f?overview=full&geometries=geojson".format(Locale.US, fromLon, fromLat, toLon, toLat)) ?: return@withContext null
        val r = ((Json.parseToJsonElement(route) as? JsonObject)?.get("routes") as? JsonArray)?.firstOrNull() as? JsonObject ?: return@withContext null
        val coords = (((r["geometry"] as? JsonObject)?.get("coordinates")) as? JsonArray).orEmpty().mapNotNull { c ->
            val a = c as? JsonArray ?: return@mapNotNull null
            ((a.getOrNull(0) as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null) to ((a.getOrNull(1) as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null)
        }
        Triple(coords, ((r["distance"] as? JsonPrimitive)?.doubleOrNull ?: 0.0) / 1000, (r["duration"] as? JsonPrimitive)?.doubleOrNull ?: 0.0)
    }

    suspend fun report(ctx: JarvisContainer, fromLat: Double, fromLon: Double, fromName: String, toLat: Double, toLon: Double, toName: String, startMs: Long): RouteReport? = withContext(Dispatchers.IO) {
        val (coords, km, seconds) = route(ctx, fromLat, fromLon, toLat, toLon) ?: return@withContext null
        val points = samplePoints(coords, seconds, startMs)
        val cities = try { MapData.get(ctx.appContext).cities } catch (_: Exception) { emptyList() }
        val weather = if (points.isEmpty()) emptyList() else {
            val url = "https://api.open-meteo.com/v1/forecast?latitude=${points.joinToString(",") { "%.3f".format(Locale.US, it.lat) }}&longitude=${points.joinToString(",") { "%.3f".format(Locale.US, it.lon) }}" +
                "&hourly=precipitation,weather_code,temperature_2m,wind_gusts_10m,visibility,snowfall&forecast_days=3&timezone=UTC"
            get(ctx, url)?.let { body -> try { parseRouteForecast(body, points) { p -> nearestCity(cities, p.lat, p.lon, 40.0)?.first?.name } } catch (_: Exception) { null } }.orEmpty()
        }
        // the line thinned for the map (a few hundred points are enough)
        val every = maxOf(1, coords.size / 400)
        RouteReport(fromName, toName, coords.filterIndexed { i, _ -> i % every == 0 || i == coords.lastIndex }.map { it.second to it.first }, km, seconds, weather).also { last = it }
    }
}

/** The start time asked for: "18:30", "18h", "dans 2 h", "dans 45 min", or now. */
internal fun departureMs(words: String, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): Long {
    val w = words.trim().lowercase()
    Regex("dans\\s+(\\d+)\\s*(h|heure|heures|min|minutes)").find(w)?.let { m ->
        val n = m.groupValues[1].toLong()
        return now + if (m.groupValues[2].startsWith("h")) n * 3_600_000L else n * 60_000L
    }
    Regex("(\\d{1,2})\\s*[h:]\\s*(\\d{2})?").find(w)?.let { m ->
        val t = LocalTime.of(m.groupValues[1].toInt().coerceIn(0, 23), m.groupValues[2].toIntOrNull()?.coerceIn(0, 59) ?: 0)
        var at = Instant.ofEpochMilli(now).atZone(zone).with(t)
        if (at.toInstant().toEpochMilli() < now - 30 * 60_000L) at = at.plusDays(1)
        return at.toInstant().toEpochMilli()
    }
    return now
}

/** "Quel temps sur la route de Lyon ?", "la météo pour aller à Bordeaux ce soir". */
object RouteWeatherTool : Tool {
    override val name = "route_weather"
    override val description =
        "La météo le long d’un trajet en voiture, à l’heure où l’on passe à chaque endroit : pluie, orage, neige, verglas, brouillard, " +
            "rafales, tous les 40 km environ (itinéraire OpenStreetMap, prévisions Open-Meteo). Depuis la position de l’utilisateur (ou " +
            "from) jusqu’à to ; depart : l’heure de départ (« 18h30 », « dans 2 h », maintenant par défaut). Réponse courte à dire à voix " +
            "haute (utile en voiture et dans Android Auto) ; le trajet s’affiche aussi sur la carte."
    override val parameters = objectSchema(required = listOf("to")) {
        string("to", "La destination (ville ou adresse).")
        string("from", "Le départ, si ce n’est pas la position actuelle.")
        string("depart", "L’heure de départ : « 18h30 », « dans 2 h » ; maintenant par défaut.")
    }

    override suspend fun run(args: JsonObject, ctx: JarvisContainer): String {
        val to = RouteWeather.geocode(ctx, args.stringArg("to").trim()) ?: return "Je ne trouve pas « ${args.stringArg("to")} »."
        val fromWords = args.stringArg("from").trim()
        val from = if (fromWords.isNotEmpty()) RouteWeather.geocode(ctx, fromWords) ?: return "Je ne trouve pas « $fromWords »."
        else (com.jarvis.android.weather.locate(ctx.appContext) as? com.jarvis.android.weather.LocationOutcome.Found)?.let { Triple(it.fix.latitude, it.fix.longitude, it.place ?: "votre position") }
            ?: return "Je n’ai pas votre position : dites d’où vous partez."
        val start = departureMs(args.stringArg("depart"))
        val r = RouteWeather.report(ctx, from.first, from.second, from.third, to.first, to.second, to.third, start)
            ?: return "Je n’ai pas pu calculer l’itinéraire (service d’itinéraire indisponible)."
        ctx.videoPanel.show(VideoPanel.Video(title = "Météo du trajet", sky = SkyModes.ROUTE))
        return routeWords(r) + " Dites-le tel quel, en phrases courtes."
    }
}
