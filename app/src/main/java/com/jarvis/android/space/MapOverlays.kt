package com.jarvis.android.space

import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.tan

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
