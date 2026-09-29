package com.jarvis.android.space

import android.content.Context
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Something of the night sky: the Moon, a planet, the Sun or a star, where it is, how bright it looks, and its distance (km). */
internal data class NightObject(val id: String, val kind: Kind, val name: String, val look: Look, val magnitude: Double, val distanceKm: Double, val star: Star? = null) {
    enum class Kind { SUN, MOON, PLANET, STAR }
}

/** The stars of the catalogue, read once (assets/sky/stars.tsv). */
internal object SkyAssets {
    @Volatile private var cached: List<Star>? = null

    fun stars(context: Context): List<Star> = cached ?: synchronized(this) {
        cached ?: parseStars(context.assets.open("sky/stars.tsv").bufferedReader().use { it.readText() }).also { cached = it }
    }
}

private fun length(v: Triple<Double, Double, Double>) = sqrt(v.first * v.first + v.second * v.second + v.third * v.third)

/** The Sun, the Moon and the planets seen from [o] at [timeMs]. */
internal fun solarSystem(o: Observer, timeMs: Long): List<NightObject> {
    val sun = sunPosition(timeMs)
    val moon = moonVector(timeMs)
    return listOf(
        NightObject("sun", NightObject.Kind.SUN, "le Soleil", lookAtSky(o, sun, timeMs), -26.7, length(sun)),
        NightObject("moon", NightObject.Kind.MOON, "la Lune", lookAtSky(o, moon, timeMs), -10.0, length(moon)),
    ) + Planet.values().map { p ->
        val v = planetVector(p, timeMs)
        NightObject("pl:${p.name}", NightObject.Kind.PLANET, p.french, lookAtSky(o, v, timeMs), p.magnitude, length(v))
    }
}

/** The catalogue's stars seen from [o] at [timeMs], those above the horizon, down to magnitude [faintest]. */
internal fun starsAbove(stars: List<Star>, o: Observer, timeMs: Long, faintest: Double = 5.0): List<NightObject> = stars.mapNotNull { s ->
    if (s.mag > faintest) return@mapNotNull null
    val l = lookAtSky(o, starVector(s, timeMs), timeMs)
    if (l.elevationDeg <= 0) null else NightObject("star:${s.hr}", NightObject.Kind.STAR, s.name ?: s.bayer, l, s.mag, 0.0, s)
}

/** The angle between two points of the sky, degrees. */
internal fun skyAngle(a: Look, b: Look): Double {
    val e1 = Math.toRadians(a.elevationDeg)
    val e2 = Math.toRadians(b.elevationDeg)
    val c = sin(e1) * sin(e2) + cos(e1) * cos(e2) * cos(Math.toRadians(a.azimuthDeg - b.azimuthDeg))
    return Math.toDegrees(acos(c.coerceIn(-1.0, 1.0)))
}

/** Where the user points in words: "sud", "nord-est", "bas", "haut", "au-dessus", "30°"… as a point of the sky (azimuth null: any). */
internal fun pointOfSky(words: String): Pair<Double?, Double> {
    val w = words.lowercase()
    val az = when {
        "nord-est" in w || "nord est" in w -> 45.0
        "nord-ouest" in w || "nord ouest" in w -> 315.0
        "sud-est" in w || "sud est" in w -> 135.0
        "sud-ouest" in w || "sud ouest" in w -> 225.0
        "nord" in w -> 0.0
        "sud" in w -> 180.0
        "ouest" in w -> 270.0   // before "est", which it contains
        "est" in w -> 90.0
        else -> null
    }
    val degrees = Regex("(\\d{1,2})\\s*°").find(w)?.groupValues?.get(1)?.toDouble()
    val el = degrees ?: when {
        "zénith" in w || "zenith" in w || "au-dessus" in w || "tout en haut" in w -> 85.0
        "très haut" in w -> 70.0
        "très bas" in w || "horizon" in w -> 8.0
        "bas" in w -> 18.0
        "haut" in w -> 55.0
        else -> 30.0
    }
    return az to el
}

/** The rising and the setting of [vector] over [o] in the next [hours] hours (null when it does not happen), every 5 minutes. */
internal fun riseSet(o: Observer, fromMs: Long, hours: Int = 24, vector: (Long) -> Triple<Double, Double, Double>): Pair<Long?, Long?> {
    var rise: Long? = null
    var set: Long? = null
    var t = fromMs
    var before = lookAtSky(o, vector(t), t).elevationDeg
    while (t < fromMs + hours * 3_600_000L && (rise == null || set == null)) {
        t += 300_000L
        val now = lookAtSky(o, vector(t), t).elevationDeg
        if (before <= -0.5 && now > -0.5 && rise == null) rise = t
        if (before > -0.5 && now <= -0.5 && set == null) set = t
        before = now
    }
    return rise to set
}
