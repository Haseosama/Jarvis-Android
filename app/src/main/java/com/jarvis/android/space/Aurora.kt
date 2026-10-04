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
import com.jarvis.android.location.distanceKm
import com.jarvis.android.tool.objectSchema
import com.jarvis.android.tool.stringArg
import com.jarvis.android.i18n.tr
import com.jarvis.android.video.VideoPanel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import okhttp3.Request
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import com.jarvis.android.video.SkyModes

/*
 * Northern (and southern) lights: NOAA's Space Weather Prediction Center. The OVATION model's map of the chance of an aurora over the
 * next hour or so (every degree of the Earth), the planetary Kp index now (every minute) and forecast for three days; with where the
 * user is (geomagnetic latitude), whether it is dark and how cloudy it is (Open-Meteo), whether it can be seen from there; and a watch
 * that says so at night.
 */

/** The chance of an aurora overhead, in %, every degree: [lon 0..359][lat -90..90]; when it is for. */
internal class AuroraGrid(val chance: ByteArray, val forecastMs: Long) {
    fun at(lat: Int, lon: Int): Int = chance[Math.floorMod(lon, 360) * 181 + (lat.coerceIn(-90, 90) + 90)].toInt()
}

/** OVATION's answer: {"Forecast Time": …, "coordinates": [[lon, lat, %], …]}, read without building a tree of 65,000 arrays. */
internal fun parseOvation(text: String): AuroraGrid? {
    val start = text.indexOf("\"coordinates\"").takeIf { it >= 0 } ?: return null
    val forecast = Regex("\"Forecast Time\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1)?.let { try { Instant.parse(it).toEpochMilli() } catch (_: Exception) { null } } ?: 0L
    val grid = ByteArray(360 * 181)
    // numbers read one after the other, three by three (a regex's find() from an index costs the whole text each time on Android)
    val nums = IntArray(3)
    var k = 0
    var n = 0
    var i = start + "\"coordinates\"".length
    val end = text.length
    while (i < end) {
        val ch = text[i]
        if (ch == '-' || ch in '0'..'9') {
            var neg = false
            if (ch == '-') { neg = true; i++ }
            var v = 0
            while (i < end && text[i] in '0'..'9') { v = v * 10 + (text[i] - '0'); i++ }
            if (i < end && text[i] == '.') { i++; while (i < end && text[i] in '0'..'9') i++ }
            nums[k++] = if (neg) -v else v
            if (k == 3) {
                k = 0
                val lat = nums[1]
                if (lat in -90..90) { grid[Math.floorMod(nums[0], 360) * 181 + lat + 90] = nums[2].coerceIn(0, 100).toByte(); n++ }
            }
        } else {
            if (ch == ']' && i + 1 < end && text[i + 1] == ']') break
            i++
        }
    }
    return if (n > 1000) AuroraGrid(grid, forecast) else null
}

/** The geomagnetic latitude of a place (a tilted dipole, its north pole at 80.8° N, 72.7° W as in 2025). */
internal fun geomagneticLatitude(lat: Double, lon: Double): Double {
    val p = Math.toRadians(80.8)
    val pl = Math.toRadians(-72.7)
    val f = Math.toRadians(lat)
    val l = Math.toRadians(lon)
    return Math.toDegrees(asin(sin(f) * sin(p) + cos(f) * cos(p) * cos(l - pl)))
}

/** The Kp from which an aurora can be seen low on the horizon from a geomagnetic latitude (the oval's edge moves about 2° a Kp step). */
internal fun kpNeeded(geomagLat: Double): Double = ((62.0 - kotlin.math.abs(geomagLat)) / 2).coerceIn(0.0, 9.0)

/** The best chance of an aurora that can be seen from a place: overhead, or towards the pole within ~1,000 km, low on the horizon. */
internal data class AuroraView(val percent: Int, val distanceKm: Double, val towardPole: Boolean)

internal fun auroraFrom(grid: AuroraGrid, lat: Double, lon: Double): AuroraView {
    var best = AuroraView(grid.at(Math.round(lat).toInt(), Math.round(lon).toInt()), 0.0, false)
    val poleward = if (lat >= 0) 1 else -1
    for (dLat in 0..10) for (dLon in -12..12) {
        val la = Math.round(lat).toInt() + poleward * dLat
        val lo = Math.round(lon).toInt() + dLon
        if (la !in -90..90) continue
        val v = grid.at(la, lo)
        if (v == 0) continue
        val d = distanceKm(lat, lon, la.toDouble(), lo.toDouble())
        if (d > 1_000) continue
        // the further, the lower on the horizon: counted less
        val seen = (v * when { d < 300 -> 1.0; else -> 1.0 - (d - 300) / 1_000 }).toInt()
        if (seen > best.percent) best = AuroraView(seen, d, d >= 300)
    }
    return best
}

/** Kp by 3 hours: observed, estimated or predicted. */
internal data class KpSlot(val timeMs: Long, val kp: Double, val predicted: Boolean, val scale: String?)

internal fun parseKpForecast(json: String): List<KpSlot> = try {
    (Json.parseToJsonElement(json) as JsonArray).mapNotNull { e ->
        val o = e as? JsonObject ?: return@mapNotNull null
        val t = (o["time_tag"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
        val kp = (o["kp"] as? JsonPrimitive)?.doubleOrNull ?: (o["Kp"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
        KpSlot(LocalDateTime.parse(t).toInstant(ZoneOffset.UTC).toEpochMilli(), kp, (o["observed"] as? JsonPrimitive)?.contentOrNull == "predicted", (o["noaa_scale"] as? JsonPrimitive)?.contentOrNull)
    }
} catch (_: Exception) {
    emptyList()
}

/**
 * The forecast night by night (the evening's date): only the 3-hour slots that are dark (starting 18h to 3h local), and only the
 * nights not over yet.
 */
internal fun nightlyKp(slots: List<KpSlot>, nowMs: Long, zone: ZoneId): Map<java.time.LocalDate, List<KpSlot>> = slots
    .filter { s ->
        val h = Instant.ofEpochMilli(s.timeMs).atZone(zone).hour
        (h >= 18 || h <= 3) && s.timeMs + 3 * 3_600_000L > nowMs
    }
    .groupBy { Instant.ofEpochMilli(it.timeMs - 12 * 3_600_000L).atZone(zone).toLocalDate() }
    .toSortedMap()

/** "nuit du 29 au 30 septembre", "nuit du 30 septembre au 1er octobre". */
internal fun nightWords(evening: java.time.LocalDate): String {
    val next = evening.plusDays(1)
    fun d(x: java.time.LocalDate) = if (x.dayOfMonth == 1) "1er" else x.dayOfMonth.toString()
    fun m(x: java.time.LocalDate) = x.format(java.time.format.DateTimeFormatter.ofPattern("MMMM", Locale.FRANCE))
    return if (evening.month == next.month) "nuit du ${d(evening)} au ${d(next)} ${m(next)}" else "nuit du ${d(evening)} ${m(evening)} au ${d(next)} ${m(next)}"
}

/** The latest one-minute estimate of Kp. */
internal fun parseKpNow(json: String): Double? = try {
    ((Json.parseToJsonElement(json) as JsonArray).lastOrNull() as? JsonObject)?.let { (it["estimated_kp"] as? JsonPrimitive)?.doubleOrNull ?: (it["kp_index"] as? JsonPrimitive)?.doubleOrNull }
} catch (_: Exception) {
    null
}

/** NOAA's geomagnetic storm scale from Kp: G1 at 5 … G5 at 9. */
internal fun stormScale(kp: Double): String = when {
    kp >= 9 -> "G5 (extrême)"
    kp >= 8 -> "G4 (sévère)"
    kp >= 7 -> "G3 (forte)"
    kp >= 6 -> "G2 (modérée)"
    kp >= 5 -> "G1 (mineure)"
    else -> "pas de tempête"
}

internal object SpaceWeather {
    private const val SWPC = "https://services.swpc.noaa.gov"
    @Volatile private var grid: Pair<Long, AuroraGrid>? = null

    private fun get(ctx: JarvisContainer, url: String): String? = try {
        ctx.http.newCall(Request.Builder().url(url).header("User-Agent", "Jarvis-Android").build()).execute().use { if (it.isSuccessful) it.body?.string() else null }
    } catch (_: Exception) {
        null
    }

    suspend fun kpNow(ctx: JarvisContainer): Double? = withContext(Dispatchers.IO) { get(ctx, "$SWPC/json/planetary_k_index_1m.json")?.let { parseKpNow(it) } }
    suspend fun kpForecast(ctx: JarvisContainer): List<KpSlot> = withContext(Dispatchers.IO) { get(ctx, "$SWPC/products/noaa-planetary-k-index-forecast.json")?.let { parseKpForecast(it) }.orEmpty() }

    /** The OVATION map, kept 10 minutes (it is renewed about every 5). */
    suspend fun aurora(ctx: JarvisContainer): AuroraGrid? = withContext(Dispatchers.IO) {
        grid?.takeIf { System.currentTimeMillis() - it.first < 10 * 60_000L }?.second
            ?: get(ctx, "$SWPC/json/ovation_aurora_latest.json")?.let { parseOvation(it) }?.also { grid = System.currentTimeMillis() to it }
    }

    /** The cloud cover now where the user is, in %, from Open-Meteo. */
    suspend fun clouds(ctx: JarvisContainer, lat: Double, lon: Double): Int? = withContext(Dispatchers.IO) {
        get(ctx, "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f&current=cloud_cover".format(Locale.US, lat, lon))?.let {
            (((Json.parseToJsonElement(it) as? JsonObject)?.get("current") as? JsonObject)?.get("cloud_cover") as? JsonPrimitive)?.doubleOrNull?.toInt()
        }
    }
}

/** What can be seen from a place now, in words. */
internal fun auroraWords(view: AuroraView?, kp: Double?, needed: Double, dark: Boolean, clouds: Int?): String {
    val parts = ArrayList<String>()
    kp?.let { parts += "Kp actuel %.1f (%s ; il faut environ %.0f ici pour en voir au ras de l’horizon)".format(Locale.FRANCE, it, stormScale(it), kotlin.math.ceil(needed)) }
    parts += when {
        view == null -> "carte des aurores indisponible"
        view.percent >= 10 && view.towardPole -> "aurore possible bas sur l’horizon vers le pôle (${view.percent} %, à environ ${view.distanceKm.toInt()} km)"
        view.percent >= 10 -> "aurore possible au-dessus de vous (${view.percent} %)"
        else -> "pas d’aurore visible d’ici en ce moment (${view.percent} %)"
    }
    if (!dark) parts += "il ne fait pas assez nuit"
    clouds?.let { parts += if (it >= 80) "ciel couvert ($it %)" else if (it >= 40) "nuageux ($it %)" else "ciel plutôt dégagé ($it % de nuages)" }
    return parts.joinToString(" ; ").replaceFirstChar { it.uppercase() } + "."
}

internal object AuroraWatch {
    private const val WORK = "aurora_watch"
    private fun prefs(c: Context) = c.getSharedPreferences("aurora_watch", Context.MODE_PRIVATE)
    fun enabled(c: Context) = prefs(c).getBoolean("on", false)
    fun threshold(c: Context) = prefs(c).getInt("threshold", 10)

    fun set(c: Context, on: Boolean, threshold: Int = 10) {
        prefs(c).edit().putBoolean("on", on).putInt("threshold", threshold).apply()
        val wm = WorkManager.getInstance(c)
        if (on) wm.enqueueUniquePeriodicWork(
            WORK, ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<AuroraWorker>(30, TimeUnit.MINUTES).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build(),
        ) else wm.cancelUniqueWork(WORK)
    }

    /** One look: at night, a cheap Kp first, the map only when it is high enough to matter; told once a night. */
    suspend fun check(c: Context) {
        if (!enabled(c)) return
        val ctx = (c.applicationContext as JarvisApp).container
        val fix = (com.jarvis.android.location.locate(c, 6 * 3_600_000L) as? com.jarvis.android.location.LocationOutcome.Found)?.fix ?: return
        val o = Observer(fix.latitude, fix.longitude)
        val now = System.currentTimeMillis()
        if (sunElevation(o, now) > -10) return
        val needed = kpNeeded(geomagneticLatitude(o.latDeg, o.lonDeg))
        val kp = SpaceWeather.kpNow(ctx) ?: return
        if (kp < needed - 2) return
        val view = auroraFrom(SpaceWeather.aurora(ctx) ?: return, o.latDeg, o.lonDeg)
        if (view.percent < threshold(c)) return
        val night = Instant.ofEpochMilli(now - 12 * 3_600_000L).atZone(ZoneId.systemDefault()).toLocalDate().toString()
        val p = prefs(c)
        if (p.getString("told", null) == night && view.percent < p.getInt("toldPercent", 0) + 15) return
        p.edit().putString("told", night).putInt("toldPercent", view.percent).apply()
        val clouds = SpaceWeather.clouds(ctx, o.latDeg, o.lonDeg)
        val text = auroraWords(view, kp, needed, true, clouds) + " Regardez vers le ${if (o.latDeg >= 0) "nord" else "sud"}, loin des lumières."
        c.getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("jarvis_aurora", tr("Aurores boréales"), NotificationManager.IMPORTANCE_HIGH))
        try {
            NotificationManagerCompat.from(c).notify(
                7_980,
                NotificationCompat.Builder(c, "jarvis_aurora").setSmallIcon(android.R.drawable.star_on).setContentTitle(tr("Aurore possible ce soir"))
                    .setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text)).setAutoCancel(true).build(),
            )
        } catch (_: SecurityException) {
        }
    }
}

class AuroraWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result { try { AuroraWatch.check(applicationContext) } catch (_: Exception) {}; return Result.success() }
}

/** "Est-ce qu'on peut voir des aurores ce soir ?", "préviens-moi s'il y a des aurores boréales". */
object AuroraTool : Tool {
    override val name = "aurora"
    override val description =
        "Aurores boréales et météo spatiale (NOAA) : action « now » : peut-on en voir d’ici maintenant (indice Kp, tempête géomagnétique, " +
            "probabilité du modèle OVATION près de chez l’utilisateur, nuit, nuages) et affiche l’ovale des aurores sur la carte du monde ; " +
            "« forecast » : le Kp prévu sur 3 jours, nuit par nuit, et s’il suffit ici ; « alert_on » (threshold : probabilité en %, 10 par " +
            "défaut) / « alert_off » : une notification la nuit quand une aurore devient visible d’ici."
    override val parameters = objectSchema(required = listOf("action")) {
        string("action", "now, forecast, alert_on ou alert_off.")
        string("threshold", "Pour alert_on : la probabilité à partir de laquelle prévenir, en % (10 par défaut).")
    }

    override suspend fun run(args: JsonObject, ctx: JarvisContainer): String {
        val action = args.stringArg("action").trim().lowercase()
        val c = ctx.appContext
        when (action) {
            "alert_on" -> {
                val t = args.stringArg("threshold").trim().removeSuffix("%").trim().toIntOrNull()?.coerceIn(1, 90) ?: 10
                AuroraWatch.set(c, true, t)
                return "Je surveille les aurores : la nuit, une notification si la probabilité d’en voir d’ici atteint $t % (vérifié toutes les 30 minutes environ)."
            }
            "alert_off" -> { AuroraWatch.set(c, false); return "Je ne surveille plus les aurores." }
        }
        val fix = (com.jarvis.android.location.locate(c) as? com.jarvis.android.location.LocationOutcome.Found)?.fix
            ?: return "Je n’ai pas votre position : autorisez la position pour Jarvis pour savoir si une aurore est visible d’ici."
        val o = Observer(fix.latitude, fix.longitude)
        val mag = geomagneticLatitude(o.latDeg, o.lonDeg)
        val needed = kpNeeded(mag)
        if (action == "forecast") {
            val zone = ZoneId.systemDefault()
            val byNight = nightlyKp(SpaceWeather.kpForecast(ctx), System.currentTimeMillis(), zone)
            if (byNight.isEmpty()) return "Prévision de l’indice Kp indisponible pour le moment."
            return byNight.entries.joinToString("\n", "Indice Kp prévu (NOAA ; il en faut environ %.0f ici, latitude géomagnétique %.0f°) :\n".format(Locale.FRANCE, kotlin.math.ceil(needed), mag)) { (day, s) ->
                val max = s.maxOf { it.kp }
                "- ${nightWords(day)} : Kp max %.1f, %s — %s".format(
                    Locale.FRANCE, max, stormScale(max), if (max >= needed) "aurore possible d’ici" else if (max >= needed - 1.5) "possible seulement plus au nord" else "pas d’aurore visible d’ici",
                )
            }
        }
        val kp = SpaceWeather.kpNow(ctx)
        val view = SpaceWeather.aurora(ctx)?.let { auroraFrom(it, o.latDeg, o.lonDeg) }
        val dark = sunElevation(o, System.currentTimeMillis()) < -12
        val clouds = SpaceWeather.clouds(ctx, o.latDeg, o.lonDeg)
        ctx.videoPanel.show(VideoPanel.Video(title = "Aurores boréales", sky = SkyModes.AURORA))
        return auroraWords(view, kp, needed, dark, clouds) + " L’ovale des aurores s’affiche sur la carte du monde. Dites-le en deux phrases."
    }
}
