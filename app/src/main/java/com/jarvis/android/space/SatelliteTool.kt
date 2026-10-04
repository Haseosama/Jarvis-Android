package com.jarvis.android.space

import com.jarvis.android.JarvisContainer
import com.jarvis.android.tool.Tool
import com.jarvis.android.tool.objectSchema
import com.jarvis.android.tool.stringArg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.sqrt

/**
 * A pass of a satellite over a place: when it rises, is highest and sets, how high, from where to where, whether one can see it, and
 * if not whether it is because of daylight ([daylight]) or the Earth's shadow.
 */
internal data class Pass(
    val riseMs: Long, val maxMs: Long, val setMs: Long, val maxElevation: Double, val riseAzimuth: Double, val setAzimuth: Double,
    val visible: Boolean, val daylight: Boolean = false,
)

/**
 * The passes of [sat] over [o] from [fromMs] for [hours] hours, highest above [minElevation] degrees; looked at every 20 s. A pass can be
 * seen when, at its highest, the satellite is lit by the Sun while the sky is dark (the Sun 6° under the horizon or more).
 */
internal fun passes(sat: Sgp4, o: Observer, fromMs: Long, hours: Int = 72, minElevation: Double = 10.0): List<Pass> {
    val out = ArrayList<Pass>()
    val step = 20_000L
    var t = fromMs
    val end = fromMs + hours * 3_600_000L
    var rise = -1L
    var riseAz = 0.0
    var best = -90.0
    var bestAt = 0L
    var lastAz = 0.0
    fun look(at: Long): Look? = sat.at(at)?.let { s -> lookAt(o, temeToEcef(s.x, s.y, s.z, at)) }
    while (t <= end) {
        val l = look(t) ?: return out
        if (l.elevationDeg > 0) {
            if (rise < 0) { rise = t; riseAz = l.azimuthDeg; best = -90.0 }
            if (l.elevationDeg > best) { best = l.elevationDeg; bestAt = t }
            lastAz = l.azimuthDeg
        } else if (rise >= 0) {
            if (best >= minElevation) {
                val s = sat.at(bestAt)
                val daylight = sunElevation(o, bestAt) >= -6
                val visible = s != null && sunlit(s, bestAt) && !daylight
                out += Pass(rise, bestAt, t, best, riseAz, lastAz, visible, daylight)
            }
            rise = -1L
        }
        t += step
    }
    return out
}

/** "nord-est", from an azimuth in degrees. */
internal fun compass(azimuth: Double): String =
    listOf("nord", "nord-est", "est", "sud-est", "sud", "sud-ouest", "ouest", "nord-ouest")[(((azimuth % 360) + 360 + 22.5) / 45).toInt() % 8]

/** "du nord-est", "de l’ouest": where it comes from. */
internal fun fromDirection(azimuth: Double): String = compass(azimuth).let { if (it == "est" || it == "ouest") "de l’$it" else "du $it" }

/** "vers le sud", "vers l’est": where it goes or where to look. */
internal fun towardDirection(azimuth: Double): String = compass(azimuth).let { if (it == "est" || it == "ouest") "vers l’$it" else "vers le $it" }

/** The name people know a satellite by. */
internal fun friendlyName(name: String): String = when {
    name.startsWith("ISS (ZARYA)") -> "la Station spatiale internationale (ISS)"
    name.startsWith("CSS (TIANHE)") -> "la station spatiale chinoise Tiangong"
    name == "HST" -> "le télescope spatial Hubble"
    else -> name.trim()
}

/**
 * The satellites around the user: which ones are above them now (how many, the brightest ones, whether they can be seen), when the ISS
 * (or another) passes next, and where it is now. The orbits come from CelesTrak (public, no key), kept 12 hours on the phone; the
 * positions are computed on the phone (SGP4), with the phone's approximate position used for that one question.
 */
object SatelliteTool : Tool {
    override val name = "satellites"
    override val description =
        "Les satellites autour de l’utilisateur. action « above » : ceux au-dessus de lui maintenant (combien, dont les Starlink, les " +
            "plus brillants avec leur hauteur et leur direction, et s’ils sont visibles à l’œil nu) ; « passes » : les prochains passages " +
            "d’un satellite (name, l’ISS par défaut) sur 3 jours, avec l’heure, la hauteur, d’où vers où, et s’il sera visible ; « where » : " +
            "où il est maintenant (au-dessus de quel point, altitude, vitesse, distance) ; passes et where marchent aussi pour les satellites " +
            "lointains (GPS, Galileo, géostationnaires comme Meteosat ou Astra). Pour « quand passe l’ISS ? », « quels satellites " +
            "au-dessus de moi ? », « où est la station spatiale ? ». « alert » : prévenir 5 minutes avant le prochain passage VISIBLE (name, " +
            "voice = true pour le dire à voix haute aussi, repeat = true pour chaque passage visible) ; « alert_off » : ne plus prévenir ; " +
            "« alert_show » : l’alerte réglée."
    override val parameters = objectSchema(required = listOf("action")) {
        string("action", "above, passes, where, alert, alert_off ou alert_show.")
        string("voice", "Pour alert : « true » pour aussi le dire à voix haute.")
        string("repeat", "Pour alert : « true » pour chaque passage visible, pas seulement le prochain.")
        string("name", "Pour passes et where : le satellite (ISS par défaut, Tiangong, Hubble, un GPS, Galileo, Meteosat…).")
    }

    private const val CELESTRAK = "https://celestrak.org/NORAD/elements/gp.php?FORMAT=tle&GROUP="
    private const val FRESH_MS = 12 * 3_600_000L

    override suspend fun run(args: JsonObject, ctx: JarvisContainer): String = withContext(Dispatchers.Default) {
        val action = args.stringArg("action").trim().lowercase()
        val wanted = args.stringArg("name").trim()
        try {
            val bright = orbits(ctx, "stations") + orbits(ctx, "visual")
            when (action) {
                "alert", "alerte", "prevenir" -> {
                    val o = observer(ctx) ?: return@withContext positionMissing(ctx)
                    val tle = find(bright, wanted) ?: return@withContext notFound(wanted)
                    fun yes(k: String) = args.stringArg(k).trim().lowercase() in setOf("true", "oui", "yes", "1")
                    val alert = PassAlert(tle.name, o.latDeg, o.lonDeg, yes("voice"), yes("repeat"))
                    val (p, approximate) = PassAlerts.schedule(ctx.appContext, alert)
                        ?: return@withContext "${friendlyName(tle.name).replaceFirstChar { it.uppercase() }} n’a pas de passage visible chez vous dans les 5 jours" +
                            if (alert.repeat) " : je regarde de nouveau tous les 2 jours et je préviendrai au prochain." else " : rien n’est réglé."
                    "C’est noté : je préviendrai 5 minutes avant ${if (alert.repeat) "chaque passage visible" else "le prochain passage visible"}" +
                        (if (alert.voice) ", à voix haute et" else ",") + " par une notification. Le prochain : " + passWords(friendlyName(tle.name), p) +
                        (if (approximate) " (Android ne permet qu’une alarme approximative : autorisez « Alarmes et rappels » pour Jarvis pour être prévenu à l’heure.)" else "")
                }
                "alert_off", "stop_alert" -> if (PassAlerts.get(ctx.appContext) != null) { PassAlerts.cancel(ctx.appContext); "Plus d’alerte de passage." } else "Aucune alerte de passage n’est réglée."
                "alert_show", "alerts" -> PassAlerts.get(ctx.appContext)?.let { a ->
                    "Alerte réglée pour ${friendlyName(a.name)}, ${if (a.repeat) "à chaque passage visible" else "le prochain passage visible"}" +
                        (if (a.voice) ", à voix haute" else "") + ", prochaine le " + java.time.Instant.ofEpochMilli(a.alarmAt).atZone(zone()).let {
                        com.jarvis.android.photos.dayWords(it.toLocalDate(), false) + " à " + "%02dh%02d".format(it.hour, it.minute)
                    } + "."
                } ?: "Aucune alerte de passage n’est réglée."
                "where", "ou" -> where(ctx, findAnywhere(ctx, bright, wanted) ?: return@withContext notFound(wanted))
                "passes", "passages" -> {
                    val o = observer(ctx) ?: return@withContext positionMissing(ctx)
                    val tle = findAnywhere(ctx, bright, wanted) ?: return@withContext notFound(wanted)
                    val sgp = Sgp4(tle)
                    val list = passes(sgp, o, System.currentTimeMillis()).take(6)
                    if (list.isEmpty()) return@withContext noPass(tle, sgp, o, System.currentTimeMillis())
                    "Prochains passages de ${friendlyName(tle.name)} au-dessus de vous (hauteur 10° ou plus) :\n" +
                        list.joinToString("\n") { p ->
                            "- ${whenWords(p.riseMs)} à ${time(p.riseMs)} : ${fromDirection(p.riseAzimuth)} ${towardDirection(p.setAzimuth)}, " +
                                "au plus haut à ${p.maxElevation.toInt()}° vers ${time(p.maxMs)}, ${minutes(p.setMs - p.riseMs)} — " +
                                when {
                                    p.visible -> "VISIBLE à l’œil nu (éclairée par le Soleil, ciel noir)"
                                    p.daylight -> "pas visible (en plein jour)"
                                    else -> "pas visible (dans l’ombre de la Terre)"
                                }
                        }
                }
                else -> {
                    val o = observer(ctx) ?: return@withContext positionMissing(ctx)
                    above(ctx, o, bright)
                }
            }
        } catch (_: IOException) {
            "Les orbites des satellites ne se téléchargent pas (connexion ?) et aucune copie récente n’est gardée."
        }
    }

    private fun notFound(name: String) = "Je ne connais pas le satellite « $name » : essayez ISS, Tiangong, Hubble, un GPS, Galileo ou Meteosat."

    /** Why [tle] has no pass to tell over [o]: it never rises there, or (a geostationary one, a high orbit) it never sets. */
    internal fun noPass(tle: Tle, sgp: Sgp4, o: Observer, now: Long): String {
        val name = friendlyName(tle.name).replaceFirstChar { it.uppercase() }
        val looks = (0..24).mapNotNull { h -> val at = now + h * 3_600_000L; sgp.at(at)?.let { s -> lookAt(o, temeToEcef(s.x, s.y, s.z, at)) } }
        if (looks.isEmpty()) return "La position de ${friendlyName(tle.name)} ne peut pas être calculée (orbite trop ancienne)."
        val low = looks.minOf { it.elevationDeg }
        val high = looks.maxOf { it.elevationDeg }
        val l = looks.first()
        return when {
            low > 0 && high - low < 2 -> "$name ne se lève ni ne se couche chez vous : il reste presque immobile dans le ciel, à ${l.elevationDeg.toInt()}° " +
                "au-dessus de l’horizon ${towardDirection(l.azimuthDeg)}, à ${"%,d".format(l.rangeKm.toInt())} km (orbite géostationnaire). Trop loin pour être vu à l’œil nu."
            low > 0 -> "$name reste au-dessus de l’horizon chez vous toute la journée : en ce moment à ${l.elevationDeg.toInt()}° ${towardDirection(l.azimuthDeg)}."
            high < 0 -> "$name ne se lève jamais chez vous : il reste sous l’horizon."
            else -> "$name ne passe pas assez haut au-dessus de vous dans les 3 jours."
        }
    }

    /** [find] among the bright ones, then among the navigation (GPS, Galileo…) and geostationary satellites when a name is given. */
    private suspend fun findAnywhere(ctx: JarvisContainer, bright: List<Tle>, name: String): Tle? =
        find(bright, name) ?: if (name.isBlank()) null else try {
            find(orbits(ctx, "gnss") + orbits(ctx, "geo"), name)
        } catch (_: IOException) {
            null
        }

    private fun positionMissing(ctx: JarvisContainer) =
        "Je n’ai pas votre position : autorisez la position pour Jarvis (Paramètres > Position (météo)), ou dites au-dessus de quelle ville regarder."

    private suspend fun observer(ctx: JarvisContainer): Observer? =
        (com.jarvis.android.location.locate(ctx.appContext) as? com.jarvis.android.location.LocationOutcome.Found)?.let { Observer(it.fix.latitude, it.fix.longitude) }

    /** The element set asked for among [tles] (the ISS when nothing is named). */
    internal fun find(tles: List<Tle>, name: String): Tle? {
        val w = name.lowercase()
        val key = when {
            w.isEmpty() || "iss" in w || "internationale" in w -> "ISS (ZARYA)"
            "tiangong" in w || "chinoise" in w || "css" in w -> "CSS (TIANHE)"
            "hubble" in w -> "HST"
            else -> name.uppercase()
        }
        return tles.firstOrNull { it.name.startsWith(key) } ?: tles.firstOrNull { it.name.contains(key, ignoreCase = true) }
    }

    private suspend fun above(ctx: JarvisContainer, o: Observer, bright: List<Tle>): String {
        val now = System.currentTimeMillis()
        val dark = sunElevation(o, now) < -6
        val seen = bright.distinctBy { it.number }.mapNotNull { tle ->
            val s = Sgp4(tle).at(now) ?: return@mapNotNull null
            val l = lookAt(o, temeToEcef(s.x, s.y, s.z, now))
            if (l.elevationDeg > 0) Triple(tle, l, sunlit(s, now)) else null
        }.sortedByDescending { it.second.elevationDeg }
        val starlink = try {
            orbits(ctx, "starlink").count { tle ->
                (Sgp4(tle).at(now)?.let { s -> lookAt(o, temeToEcef(s.x, s.y, s.z, now)).elevationDeg > 0 } ?: false)
            }
        } catch (_: IOException) {
            -1
        }
        val lines = seen.take(8).joinToString("\n") { (tle, l, lit) ->
            "- ${friendlyName(tle.name)} : ${l.elevationDeg.toInt()}° au-dessus de l’horizon ${towardDirection(l.azimuthDeg)}, à ${l.rangeKm.toInt()} km" +
                if (lit && dark && l.elevationDeg > 10) " — visible à l’œil nu maintenant" else ""
        }
        return "Au-dessus de vous en ce moment : ${seen.size} des ${bright.distinctBy { it.number }.size} satellites les plus brillants" +
            (if (starlink >= 0) ", et $starlink satellites Starlink" else "") + ". " +
            (if (dark) "Le ciel est noir : un satellite éclairé par le Soleil se voit comme une étoile qui avance." else "Il fait jour (ou crépuscule) : on ne les voit pas à l’œil nu.") +
            (if (lines.isNotEmpty()) "\nLes plus hauts :\n$lines" else "")
    }

    private fun where(ctx: JarvisContainer, tle: Tle): String {
        val now = System.currentTimeMillis()
        val s = Sgp4(tle).at(now) ?: return "La position de ${friendlyName(tle.name)} ne peut pas être calculée (orbite trop ancienne)."
        val ecef = temeToEcef(s.x, s.y, s.z, now)
        val (lat, lon, alt) = subPoint(ecef)
        val speed = sqrt(s.vx * s.vx + s.vy * s.vy + s.vz * s.vz) * 3600
        return "${friendlyName(tle.name).replaceFirstChar { it.uppercase() }} est en ce moment au-dessus du point ${"%.1f".format(lat)}° de latitude, " +
            "${"%.1f".format(lon)}° de longitude (dites le pays ou l’océan si vous le savez), à ${"%,d".format(alt.toInt())} km d’altitude, à ${"%,d".format(speed.toInt())} km/h, " +
            (if (tle.periodMin in 1_400.0..1_480.0 && tle.inclination < 5 * Math.PI / 180) "en orbite géostationnaire (il reste au-dessus du même point de l’équateur), " else "") +
            (if (sunlit(s, now)) "éclairée par le Soleil." else "dans l’ombre de la Terre.")
    }

    /** A CelesTrak group, kept 12 hours on the phone (CelesTrak asks not to be asked again sooner); an older copy when offline. */
    internal suspend fun orbits(ctx: JarvisContainer, group: String): List<Tle> {
        val file = File(File(ctx.appContext.cacheDir, "space").apply { mkdirs() }, "$group.tle")
        if (file.isFile && System.currentTimeMillis() - file.lastModified() < FRESH_MS) return parseTles(file.readText())
        return try {
            val text = download(ctx.http, CELESTRAK + group)
            if (parseTles(text).isEmpty()) throw IOException("no orbits")
            file.writeText(text)
            parseTles(text)
        } catch (e: IOException) {
            if (file.isFile) parseTles(file.readText()) else throw e
        }
    }

    private suspend fun download(http: OkHttpClient, url: String): String = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(url).header("User-Agent", "Jarvis-Android").build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            r.body?.string().orEmpty()
        }
    }

    private fun zone() = ZoneId.systemDefault()
    private fun time(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone()).toLocalTime().let { "%02dh%02d".format(it.hour, it.minute) }
    private fun minutes(ms: Long) = "${maxOf(1, (ms / 60_000).toInt())} min"
    private fun whenWords(ms: Long): String {
        val day = Instant.ofEpochMilli(ms).atZone(zone()).toLocalDate()
        val today = LocalDate.now(zone())
        return when (day) {
            today -> "aujourd’hui"
            today.plusDays(1) -> "demain"
            else -> "le " + com.jarvis.android.photos.dayWords(day, false)
        }
    }
}
