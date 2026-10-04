package com.jarvis.android.space

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.jarvis.android.JarvisApp
import com.jarvis.android.JarvisContainer
import com.jarvis.android.tool.Tool
import com.jarvis.android.tool.objectSchema
import com.jarvis.android.tool.stringArg
import com.jarvis.android.i18n.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.Request
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.sqrt

/*
 * What happens in the sky in the weeks to come, seen from where the user is: the lunar eclipses (computed here from the Moon and the Sun),
 * the solar eclipses as seen from the place (the discs of the Moon and the Sun, minute by minute), the meteor showers (their peaks, and how much the Moon spoils
 * them), the Moon close to a planet or two planets close together, the full moons (and the "super" ones). Told as a calendar, and a
 * notification the day before.
 */

/** Something to see in the sky: when (its best moment), what, in words, and how much it is worth a look (to sort and to tell). */
internal data class SkyEvent(val timeMs: Long, val kind: String, val title: String, val words: String, val worth: Int)

private fun norm(v: Triple<Double, Double, Double>) = sqrt(v.first * v.first + v.second * v.second + v.third * v.third)

/** The angle between two directions from the Earth's centre, degrees. */
internal fun angleBetween(a: Triple<Double, Double, Double>, b: Triple<Double, Double, Double>): Double =
    Math.toDegrees(acos(((a.first * b.first + a.second * b.second + a.third * b.third) / (norm(a) * norm(b))).coerceIn(-1.0, 1.0)))

/** The Moon's distance from the point opposite the Sun (the centre of the Earth's shadow), degrees. */
private fun shadowGap(t: Long): Double = 180.0 - angleBetween(moonVector(t), sunPosition(t))

/** The time within [lo, hi] where [f] is least (golden section), to the minute. */
private fun minimize(lo0: Long, hi0: Long, f: (Long) -> Double): Long {
    var lo = lo0.toDouble()
    var hi = hi0.toDouble()
    val g = (sqrt(5.0) - 1) / 2
    while (hi - lo > 60_000) {
        val a = hi - g * (hi - lo)
        val b = lo + g * (hi - lo)
        if (f(a.toLong()) < f(b.toLong())) hi = b else lo = a
    }
    return ((lo + hi) / 2).toLong()
}

/** A lunar eclipse: its middle, its kind (total, partial, penumbral), the umbral magnitude (the part of the Moon's width in the shadow). */
internal data class LunarEclipse(val maxMs: Long, val kind: String, val umbralMagnitude: Double, val penumbralMagnitude: Double)

/** The full moons between two times (the moments the Moon is furthest from the Sun in the sky). */
internal fun fullMoons(fromMs: Long, toMs: Long): List<Long> {
    val out = ArrayList<Long>()
    var t = fromMs
    val day = 86_400_000L
    while (t < toMs) {
        val a = shadowGap(t - day); val b = shadowGap(t); val c = shadowGap(t + day)
        if (b <= a && b < c) {
            val m = minimize(t - day, t + day) { shadowGap(it) }
            if (m in fromMs..toMs && (out.isEmpty() || m - out.last() > 20 * day)) out += m
        }
        t += day
    }
    return out
}

/** The lunar eclipses between two times: at each full moon, whether the Moon enters the Earth's penumbra or umbra (Meeus's radii, with the usual 2 % for the atmosphere). */
internal fun lunarEclipses(fromMs: Long, toMs: Long): List<LunarEclipse> = fullMoons(fromMs, toMs).mapNotNull { t ->
    val moon = moonVector(t)
    val sun = sunPosition(t)
    val dm = norm(moon)
    val ds = norm(sun)
    val pm = Math.toDegrees(asin(6378.14 / dm))
    val ps = Math.toDegrees(asin(6378.14 / ds))
    val ss = Math.toDegrees(asin(696_000.0 / ds))
    val sm = Math.toDegrees(asin(1737.4 / dm))
    val umbra = 1.02 * (0.998340 * pm - ss + ps)
    val penumbra = 1.02 * (0.998340 * pm + ss + ps)
    val gap = shadowGap(t)
    val um = (umbra + sm - gap) / (2 * sm)
    val pen = (penumbra + sm - gap) / (2 * sm)
    when {
        um >= 1 -> LunarEclipse(t, "totale", um, pen)
        um > 0 -> LunarEclipse(t, "partielle", um, pen)
        pen > 0 -> LunarEclipse(t, "par la pénombre", um, pen)
        else -> null
    }
}

/** A meteor shower: its name, its peak (month, day), its rate at best (ZHR), where its meteors come from. */
internal data class Shower(val name: String, val month: Int, val day: Int, val zhr: Int, val radiant: String, val bestEvening: Boolean = false)

internal val SHOWERS = listOf(
    Shower("Quadrantides", 1, 3, 110, "le Bouvier"), Shower("Lyrides", 4, 22, 18, "la Lyre"), Shower("Êta Aquarides", 5, 6, 50, "le Verseau"),
    Shower("Delta Aquarides", 7, 30, 25, "le Verseau"), Shower("Perséides", 8, 12, 100, "Persée"), Shower("Draconides", 10, 8, 10, "le Dragon", bestEvening = true),
    Shower("Orionides", 10, 21, 20, "Orion"), Shower("Léonides", 11, 17, 15, "le Lion"), Shower("Géminides", 12, 14, 150, "les Gémeaux", bestEvening = true),
    Shower("Ursides", 12, 22, 10, "la Petite Ourse"),
)

/** "du Lion", "de la Lyre", "des Gémeaux", "d’Orion", "de Persée". */
internal fun fromWords(name: String): String = when {
    name.startsWith("le ") -> "du " + name.removePrefix("le ")
    name.startsWith("les ") -> "des " + name.removePrefix("les ")
    name.startsWith("la ") -> "de " + name
    name.first().lowercaseChar() in "aeiouéèêh" -> "d’$name"
    else -> "de $name"
}

/** The showers peaking between two dates, with the Moon's light that night (at 2 a.m., or 10 p.m. for those best in the evening). */
internal fun showerEvents(from: LocalDate, to: LocalDate, zone: ZoneId): List<SkyEvent> = (from.year..to.year).flatMap { y ->
    SHOWERS.mapNotNull { s ->
        val night = LocalDate.of(y, s.month, s.day)
        if (night.isBefore(from) || night.isAfter(to)) return@mapNotNull null
        val at = (if (s.bestEvening) night.atTime(22, 0) else night.plusDays(1).atTime(2, 0)).atZone(zone).toInstant().toEpochMilli()
        val lit = (moonPhase(at).lit * 100).toInt()
        val moon = when { lit < 25 -> "la Lune ne gêne pas"; lit < 60 -> "la Lune (éclairée à $lit %) gêne un peu"; else -> "la Lune (éclairée à $lit %) cache les plus faibles" }
        val nightWords = "nuit du ${night.dayOfMonth} au ${night.plusDays(1).dayOfMonth} ${night.plusDays(1).format(DateTimeFormatter.ofPattern("MMMM", Locale.FRANCE))}"
        SkyEvent(
            at, "shower", "Étoiles filantes : ${s.name}",
            "${s.name}, maximum la $nightWords : jusqu’à ${s.zhr} par heure dans un ciel parfait, venant ${fromWords(s.radiant)} ; " +
                "$moon ; meilleur ${if (s.bestEvening) "en soirée" else "après minuit, jusqu’à l’aube"}, loin des lumières",
            (if (s.zhr >= 80) 3 else if (s.zhr >= 30) 2 else 1) + if (lit < 40) 1 else 0,
        )
    }
}

/** Two of the Moon and the bright planets close together in the sky: when they are closest (under [maxDeg]), and how far from the Sun. */
internal data class Conjunction(val timeMs: Long, val a: String, val b: String, val separation: Double, val elongation: Double, val evening: Boolean)

private val BRIGHT = listOf(Planet.values().first { it.name == "MERCURY" }, Planet.values().first { it.name == "VENUS" }, Planet.values().first { it.name == "MARS" },
    Planet.values().first { it.name == "JUPITER" }, Planet.values().first { it.name == "SATURN" })

internal fun conjunctions(fromMs: Long, toMs: Long): List<Conjunction> {
    data class Body(val name: String, val v: (Long) -> Triple<Double, Double, Double>, val limit: Double)
    val bodies = listOf(Body("la Lune", ::moonVector, 4.0)) + BRIGHT.map { p -> Body(p.french, { t: Long -> planetVector(p, t) }, 2.0) }
    val out = ArrayList<Conjunction>()
    val step = 6 * 3_600_000L
    for (i in bodies.indices) for (j in i + 1 until bodies.size) {
        val a = bodies[i]
        val b = bodies[j]
        val limit = minOf(a.limit, b.limit).let { if (a.name == "la Lune" || b.name == "la Lune") 4.0 else it }
        fun sep(t: Long) = angleBetween(a.v(t), b.v(t))
        var t = fromMs + step
        while (t < toMs - step) {
            val s0 = sep(t - step); val s1 = sep(t); val s2 = sep(t + step)
            if (s1 <= s0 && s1 < s2 && s1 < limit + 1) {
                val m = minimize(t - step, t + step) { sep(it) }
                val s = sep(m)
                if (s < limit) {
                    val sun = sunPosition(m)
                    val pv = b.v(m)
                    val elong = angleBetween(pv, sun)
                    // east of the Sun (a larger right ascension, within 12 h): seen in the evening
                    val (raP, _) = raDec(pv)
                    val (raS, _) = raDec(sun)
                    val evening = ((raP - raS + 360) % 360) < 180
                    if (elong > 15) out += Conjunction(m, a.name, b.name, s, elong, evening)
                }
            }
            t += step
        }
    }
    return out.sortedBy { it.timeMs }
}

/** A solar eclipse as seen from a place, while the Sun is up: its start, its middle, its end, the part of the Sun hidden at the middle. */
internal data class LocalSolarEclipse(
    val beginMs: Long, val maxMs: Long, val endMs: Long, val obscuration: Double, val kind: String, val sunAltitude: Double, val beginsBeforeSunrise: Boolean, val endsAfterSunset: Boolean,
)

/** The new moons between two times (the Moon nearest the Sun in the sky). */
internal fun newMoons(fromMs: Long, toMs: Long): List<Long> {
    val out = ArrayList<Long>()
    val day = 86_400_000L
    fun el(t: Long) = angleBetween(moonVector(t), sunPosition(t))
    var t = fromMs
    while (t < toMs) {
        val a = el(t - day); val b = el(t); val c = el(t + day)
        if (b <= a && b < c) {
            val m = minimize(t - day, t + day) { el(it) }
            if (m in fromMs..toMs && (out.isEmpty() || m - out.last() > 20 * day)) out += m
        }
        t += day
    }
    return out
}

/** The part of the Sun's disc (radius [sun]) hidden by the Moon's (radius [moon]) at a distance [d] between their centres (all in degrees). */
internal fun hiddenPart(sun: Double, moon: Double, d: Double): Double {
    if (d >= sun + moon) return 0.0
    if (d <= kotlin.math.abs(sun - moon)) return if (moon >= sun) 1.0 else (moon * moon) / (sun * sun)
    val a = moon * moon * acos(((d * d + moon * moon - sun * sun) / (2 * d * moon)).coerceIn(-1.0, 1.0)) +
        sun * sun * acos(((d * d + sun * sun - moon * moon) / (2 * d * sun)).coerceIn(-1.0, 1.0)) -
        0.5 * sqrt(((-d + moon + sun) * (d + moon - sun) * (d - moon + sun) * (d + moon + sun)).coerceAtLeast(0.0))
    return a / (Math.PI * sun * sun)
}

/**
 * The solar eclipses seen from [o] between two times: at each new moon near a node, the Moon's and the Sun's discs as seen from the place
 * (the Moon's parallax is up to a degree), minute by minute over eight hours, while the Sun is above the horizon.
 */
internal fun localSolarEclipses(o: Observer, fromMs: Long, toMs: Long): List<LocalSolarEclipse> = newMoons(fromMs, toMs).mapNotNull { t0 ->
    if (angleBetween(moonVector(t0), sunPosition(t0)) > 1.7) return@mapNotNull null
    var first: Long? = null
    var last: Long? = null
    var best: Triple<Long, Double, Double>? = null // time, distance less the radii (the deeper the more negative), obscuration
    var anyBeforeSunrise = false
    var anyAfterSunset = false
    var kind = "partielle"
    var t = t0 - 4 * 3_600_000L
    while (t <= t0 + 4 * 3_600_000L) {
        val sun = lookAtSky(o, sunPosition(t), t)
        val moon = lookAtSky(o, moonVector(t), t)
        val rs = Math.toDegrees(asin(696_000.0 / sun.rangeKm))
        val rm = Math.toDegrees(asin(1_737.4 / moon.rangeKm))
        val d = skyAngle(sun, moon)
        if (d < rs + rm) {
            if (sun.elevationDeg > -0.8) {
                if (first == null) first = t
                last = t
                val depth = d - (rs + rm)
                if (best == null || depth < best.second) best = Triple(t, depth, hiddenPart(rs, rm, d))
                if (d <= rm - rs) kind = "totale" else if (d <= rs - rm && kind != "totale") kind = "annulaire"
            } else if (first == null) anyBeforeSunrise = true else anyAfterSunset = true
        }
        t += 60_000L
    }
    val b = best ?: return@mapNotNull null
    LocalSolarEclipse(first!!, b.first, last!!, b.third, kind, lookAtSky(o, sunPosition(b.first), b.first).elevationDeg, anyBeforeSunrise, anyAfterSunset)
}

/** The eclipse in words, for the place. */
internal fun solarWords(e: LocalSolarEclipse, zone: ZoneId): String {
    fun hm(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toLocalTime().let { "%02dh%02d".format(it.hour, it.minute) }
    val day = Instant.ofEpochMilli(e.maxMs).atZone(zone).format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.FRANCE))
    return "éclipse de Soleil ${e.kind} ici le $day : " + (if (e.beginsBeforeSunrise) "déjà commencée au lever du Soleil, " else "début ${hm(e.beginMs)}, ") +
        "maximum ${hm(e.maxMs)} (${(e.obscuration * 100).toInt()} % du Soleil caché, Soleil à ${e.sunAltitude.toInt()}° de haut), " +
        (if (e.endsAfterSunset) "le Soleil se couche avant la fin" else "fin ${hm(e.endMs)}") +
        (if (e.sunAltitude < 8) " — très bas sur l’horizon : il faut un horizon dégagé" else "") + " ; JAMAIS sans lunettes d’éclipse homologuées (à quelques minutes près)"
}

internal object SkyEvents {
    private fun get(ctx: JarvisContainer, url: String): String? = try {
        ctx.http.newCall(Request.Builder().url(url).header("User-Agent", "Jarvis-Android").build()).execute().use { if (it.isSuccessful) it.body?.string() else null }
    } catch (_: Exception) {
        null
    }

    /** Everything to see from [o] in the [days] to come, soonest first. */
    suspend fun upcoming(ctx: JarvisContainer, o: Observer, days: Int, zone: ZoneId = ZoneId.systemDefault()): List<SkyEvent> = withContext(Dispatchers.Default) {
        val now = System.currentTimeMillis()
        val until = now + days * 86_400_000L
        val today = LocalDate.now(zone)
        fun day(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.FRANCE))
        fun hm(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toLocalTime().let { "%02dh%02d".format(it.hour, it.minute) }
        val out = ArrayList<SkyEvent>()
        lunarEclipses(now, until).forEach { e ->
            // seen if the Moon is up at the middle, or an hour either side
            val up = listOf(e.maxMs - 3_600_000L, e.maxMs, e.maxMs + 3_600_000L).count { lookAtSky(o, moonVector(it), it).elevationDeg > 0 }
            val mag = if (e.kind == "par la pénombre") "à peine visible : la Lune s’assombrit un peu" else "%d %% de la Lune dans l’ombre".format((minOf(e.umbralMagnitude, 1.0) * 100).toInt())
            out += SkyEvent(
                e.maxMs, "lunar", "Éclipse de Lune ${e.kind}",
                "éclipse de Lune ${e.kind} le ${day(e.maxMs)}, maximum vers ${hm(e.maxMs)} ($mag)" + when (up) { 3 -> ", visible d’ici"; 0 -> ", pas visible d’ici (la Lune est sous l’horizon)"; else -> ", visible en partie d’ici (Lune basse)" },
                if (up == 0) 0 else if (e.kind == "totale") 5 else if (e.kind == "partielle") 4 else 1,
            )
        }
        fullMoons(now, until).forEach { t ->
            val d = norm(moonVector(t))
            val sup = d < 362_000
            out += SkyEvent(t, "fullmoon", if (sup) "Super Lune" else "Pleine Lune", (if (sup) "super Lune (pleine Lune proche, ${(d / 1000).toInt()} 000 km : un peu plus grande et plus lumineuse)" else "pleine Lune") + " le ${day(t)} à ${hm(t)}", if (sup) 2 else 0)
        }
        out += showerEvents(today, today.plusDays(days.toLong()), zone)
        conjunctions(now, until).forEach { c ->
            val when_ = if (c.evening) "le soir, après le coucher du Soleil, à l’ouest" else "le matin, avant le lever du Soleil, à l’est"
            out += SkyEvent(
                c.timeMs, "conjunction", "${c.a.replaceFirstChar { it.uppercase() }} et ${c.b}",
                "${c.a} et ${c.b} tout près l’un de l’autre (%.1f°) le ${day(c.timeMs)}, à voir $when_".format(Locale.FRANCE, c.separation),
                // the Moon passes each planet every month: worth telling when very close; two planets together is rarer
                if (c.a == "la Lune" || c.b == "la Lune") (if (c.separation < 1.0) 2 else 1) else 3,
            )
        }
        localSolarEclipses(o, now, until).forEach { e -> out += SkyEvent(e.maxMs, "solar", "Éclipse de Soleil ${e.kind}", solarWords(e, zone), 5) }
        out.sortedBy { it.timeMs }
    }

    private fun prefs(c: Context) = c.getSharedPreferences("sky_events", Context.MODE_PRIVATE)
    fun alerts(c: Context) = prefs(c).getBoolean("on", false)

    fun setAlerts(c: Context, on: Boolean) {
        prefs(c).edit().putBoolean("on", on).apply()
        val wm = WorkManager.getInstance(c)
        if (on) wm.enqueueUniquePeriodicWork(
            "sky_events", ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<SkyEventsWorker>(6, TimeUnit.HOURS).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build(),
        ) else wm.cancelUniqueWork("sky_events")
    }

    /** The day before (from 5 p.m.) or the day itself: what is worth a look, each told once. */
    suspend fun check(c: Context) {
        if (!alerts(c)) return
        val zone = ZoneId.systemDefault()
        val now = java.time.ZonedDateTime.now(zone)
        val ctx = (c.applicationContext as JarvisApp).container
        val fix = (com.jarvis.android.location.locate(c, 24 * 3_600_000L) as? com.jarvis.android.location.LocationOutcome.Found)?.fix ?: return
        val events = upcoming(ctx, Observer(fix.latitude, fix.longitude), 3, zone).filter { it.worth >= 2 }
        val p = prefs(c)
        val told = p.getStringSet("told", emptySet()).orEmpty()
        val due = events.filter { e ->
            val d = Instant.ofEpochMilli(e.timeMs).atZone(zone).toLocalDate()
            val key = e.kind + e.timeMs / 3_600_000L
            key !in told && (d == now.toLocalDate() || (d == now.toLocalDate().plusDays(1) && now.hour >= 17) || (e.kind == "shower" && d == now.toLocalDate().plusDays(1)))
        }
        if (due.isEmpty()) return
        c.getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("jarvis_sky_events", tr("Événements du ciel"), NotificationManager.IMPORTANCE_DEFAULT))
        due.forEach { e ->
            try {
                NotificationManagerCompat.from(c).notify(
                    (e.kind + e.timeMs).hashCode(),
                    NotificationCompat.Builder(c, "jarvis_sky_events").setSmallIcon(android.R.drawable.star_on).setContentTitle(e.title)
                        .setContentText(e.words.replaceFirstChar { it.uppercase() }).setStyle(NotificationCompat.BigTextStyle().bigText(e.words.replaceFirstChar { it.uppercase() } + ".")).setAutoCancel(true).build(),
                )
            } catch (_: SecurityException) {
            }
        }
        p.edit().putStringSet("told", (told + due.map { it.kind + it.timeMs / 3_600_000L }).toList().takeLast(100).toSet()).apply()
    }
}

class SkyEventsWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result { try { SkyEvents.check(applicationContext) } catch (_: Exception) {}; return Result.success() }
}

/** "Quels sont les prochains événements dans le ciel ?", "quand est la prochaine éclipse ?", "préviens-moi des étoiles filantes". */
object SkyEventsTool : Tool {
    override val name = "sky_events"
    override val description =
        "Le calendrier du ciel vu d’où est l’utilisateur : éclipses de Lune et de Soleil (calculées pour l’endroit : " +
            "début, maximum, fin, part du Soleil cachée), pluies d’étoiles filantes (maximum, nombre par heure, gêne de la Lune), la Lune " +
            "près d’une planète ou deux planètes proches, pleines Lunes et super Lunes. action « list » (days : sur combien de jours, 120 par " +
            "défaut ; kind : eclipse, shower, conjunction, fullmoon pour n’en garder qu’un genre) ; « alert_on » / « alert_off » : une " +
            "notification la veille au soir (et le jour même) de ce qui vaut le coup d’œil."
    override val parameters = objectSchema(required = listOf("action")) {
        string("action", "list, alert_on ou alert_off.")
        string("days", "Pour list : combien de jours à venir (120 par défaut, 800 au plus).")
        string("kind", "Pour list : eclipse, shower, conjunction ou fullmoon (tout par défaut).")
    }

    override suspend fun run(args: JsonObject, ctx: JarvisContainer): String {
        val c = ctx.appContext
        when (args.stringArg("action").trim().lowercase()) {
            "alert_on" -> { SkyEvents.setAlerts(c, true); return "Je vous préviendrai la veille au soir des éclipses, pluies d’étoiles filantes, rapprochements de la Lune et des planètes et super Lunes visibles d’ici." }
            "alert_off" -> { SkyEvents.setAlerts(c, false); return "Je ne préviens plus des événements du ciel." }
        }
        val fix = (com.jarvis.android.location.locate(c) as? com.jarvis.android.location.LocationOutcome.Found)?.fix
            ?: return "Je n’ai pas votre position : autorisez la position pour Jarvis, les événements dépendent du lieu."
        val days = args.stringArg("days").trim().toIntOrNull()?.coerceIn(1, 800) ?: 120
        val kind = args.stringArg("kind").trim().lowercase()
        val all = SkyEvents.upcoming(ctx, Observer(fix.latitude, fix.longitude), days)
        val list = when {
            kind.startsWith("ecl") -> all.filter { it.kind == "lunar" || it.kind == "solar" }
            kind.startsWith("sh") || "filante" in kind -> all.filter { it.kind == "shower" }
            kind.startsWith("con") -> all.filter { it.kind == "conjunction" }
            kind.startsWith("full") || "lune" in kind -> all.filter { it.kind == "fullmoon" }
            else -> all.filter { it.worth >= 1 }
        }
        if (list.isEmpty()) return "Rien de marquant dans le ciel d’ici dans les $days prochains jours" + (if (kind.isNotEmpty()) " pour ce genre d’événement" else "") + "."
        // the most remarkable fifteen, in the order they come
        val shown = if (list.size <= 15) list else list.sortedWith(compareByDescending<SkyEvent> { it.worth }.thenBy { it.timeMs }).take(15).sortedBy { it.timeMs }
        return shown.joinToString("\n", "Dans le ciel d’ici (${days} jours) :\n") { "- " + it.words } + "\nDites les plus remarquables en quelques phrases."
    }
}
