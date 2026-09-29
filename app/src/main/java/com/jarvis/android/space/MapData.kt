package com.jarvis.android.space

import android.content.Context
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.tan

/*
 * The world map's data and geometry: Natural Earth's land and borders (assets/sky/land.bin, borders.bin: rings of lon/lat in 1/100 °)
 * and cities (cities.tsv), the Web Mercator projection the radar tiles use (x and y from 0 to 1 across the world), the line between
 * day and night, and a satellite's track on the ground.
 */

/** Longitude to Web Mercator x (0 at 180° W, 1 at 180° E). */
internal fun mercX(lon: Double): Double = (lon + 180.0) / 360.0

/** Latitude to Web Mercator y (0 at the top, 85° N; 1 at 85° S). */
internal fun mercY(lat: Double): Double {
    val l = Math.toRadians(lat.coerceIn(-85.05, 85.05))
    return (1 - ln(tan(l) + 1 / cos(l)) / PI) / 2
}

/** Web Mercator y back to latitude. */
internal fun latOf(y: Double): Double = Math.toDegrees(atan(kotlin.math.sinh(PI * (1 - 2 * y))))

/** A city: name, position, population, capital or not. */
internal data class City(val name: String, val lat: Double, val lon: Double, val population: Int, val capital: Boolean)

/** The map's outlines already in Mercator (x, y pairs), and its cities; read once. */
internal class MapData(val land: List<FloatArray>, val borders: List<FloatArray>, val cities: List<City>) {
    companion object {
        @Volatile private var cached: MapData? = null

        fun get(context: Context): MapData = cached ?: synchronized(this) {
            cached ?: MapData(
                rings(context.assets.open("sky/land.bin").use { it.readBytes() }),
                rings(context.assets.open("sky/borders.bin").use { it.readBytes() }),
                parseCities(context.assets.open("sky/cities.tsv").bufferedReader().use { it.readText() }),
            ).also { cached = it }
        }

        /** Rings of 1/100 degree integers (little-endian: count, then for each: n and n × (lon, lat) shorts) as Mercator x, y pairs. */
        fun rings(bytes: ByteArray): List<FloatArray> {
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val count = b.int
            return List(count) {
                val n = b.int
                FloatArray(n * 2).also { a ->
                    for (i in 0 until n) {
                        val lon = b.short / 100.0
                        val lat = b.short / 100.0
                        a[2 * i] = mercX(lon).toFloat()
                        a[2 * i + 1] = mercY(lat).toFloat()
                    }
                }
            }
        }

        fun parseCities(tsv: String): List<City> = tsv.lineSequence().filter { it.isNotBlank() && !it.startsWith("#") }.mapNotNull { l ->
            val f = l.split('\t')
            if (f.size < 5) null else try { City(f[0], f[1].toDouble(), f[2].toDouble(), f[3].toInt(), f[4] == "1") } catch (_: NumberFormatException) { null }
        }.toList()
    }
}

/** The point of the Earth under the Sun at [timeMs]: latitude, longitude (degrees). */
internal fun subSolar(timeMs: Long): Pair<Double, Double> {
    val (x, y, z) = sunPosition(timeMs)
    val (lat, lon, _) = subPoint(temeToEcef(x, y, z, timeMs))
    return lat to lon
}

/**
 * The night side of the Earth at [timeMs] as a polygon of longitude, latitude points (degrees): the terminator every 2° of longitude,
 * closed round the pole in the dark.
 */
internal fun nightPolygon(timeMs: Long): List<Pair<Double, Double>> {
    val (sLat, sLon) = subSolar(timeMs)
    val dec = Math.toRadians(if (kotlin.math.abs(sLat) < 0.1) 0.1 else sLat)
    val line = (-180..180 step 2).map { lon ->
        val h = Math.toRadians(lon - sLon)
        lon.toDouble() to Math.toDegrees(atan(-cos(h) / tan(dec)))
    }
    val darkPole = if (sLat > 0) -85.0 else 85.0
    return line + listOf(180.0 to darkPole, -180.0 to darkPole)
}

/** A satellite's track on the ground from [fromMs] to [toMs], cut where it crosses the date line (lists of latitude, longitude). */
internal fun groundTrack(sgp: Sgp4, fromMs: Long, toMs: Long, stepMs: Long = 30_000L): List<List<Pair<Double, Double>>> {
    val out = ArrayList<MutableList<Pair<Double, Double>>>()
    var cur = ArrayList<Pair<Double, Double>>()
    var t = fromMs
    var lastLon: Double? = null
    while (t <= toMs) {
        val s = sgp.at(t)
        if (s != null) {
            val (lat, lon, _) = subPoint(temeToEcef(s.x, s.y, s.z, t))
            if (lastLon != null && kotlin.math.abs(lon - lastLon) > 180) { out += cur; cur = ArrayList() }
            cur += lat to lon
            lastLon = lon
        }
        t += stepMs
    }
    out += cur
    return out.filter { it.size > 1 }
}

/** The nearest city to a point within [maxKm], with its distance. */
internal fun nearestCity(cities: List<City>, lat: Double, lon: Double, maxKm: Double = 600.0): Pair<City, Double>? =
    cities.map { it to com.jarvis.android.actions.distanceKm(lat, lon, it.lat, it.lon) }.filter { it.second <= maxKm }.minByOrNull { it.second }

/** The points of the great circle from one place to another (latitude, longitude), for a flight's route. */
internal fun greatCircle(lat1: Double, lon1: Double, lat2: Double, lon2: Double, n: Int = 64): List<Pair<Double, Double>> {
    val p1 = Math.toRadians(lat1); val l1 = Math.toRadians(lon1)
    val p2 = Math.toRadians(lat2); val l2 = Math.toRadians(lon2)
    val d = 2 * kotlin.math.asin(kotlin.math.sqrt(sin((p2 - p1) / 2).let { it * it } + cos(p1) * cos(p2) * sin((l2 - l1) / 2).let { it * it }))
    if (d < 1e-9) return listOf(lat1 to lon1)
    return (0..n).map { i ->
        val f = i.toDouble() / n
        val a = sin((1 - f) * d) / sin(d)
        val b = sin(f * d) / sin(d)
        val x = a * cos(p1) * cos(l1) + b * cos(p2) * cos(l2)
        val y = a * cos(p1) * sin(l1) + b * cos(p2) * sin(l2)
        val z = a * sin(p1) + b * sin(p2)
        Math.toDegrees(kotlin.math.atan2(z, kotlin.math.sqrt(x * x + y * y))) to Math.toDegrees(kotlin.math.atan2(y, x))
    }
}
