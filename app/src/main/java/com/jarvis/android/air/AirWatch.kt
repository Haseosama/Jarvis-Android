package com.jarvis.android.air

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.compose.ui.graphics.Color
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
import com.jarvis.android.i18n.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import okhttp3.Request
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale
import java.util.concurrent.TimeUnit

/*
 * Air and pollen beyond the figures of the moment: each pollen's level in words, the next two days' worst hours, a map of the air
 * around the user, and a watch that warns when the air is bad or a pollen high (Open-Meteo's air quality, from Copernicus CAMS;
 * pollens are Europe only).
 */

/** A pollen: its API key, its French name, and the grains/m³ from which it is moderate, high, very high. */
internal data class Pollen(val key: String, val name: String, val moderate: Double, val high: Double, val veryHigh: Double)

internal val POLLENS = listOf(
    Pollen("alder_pollen", "aulne", 15.0, 90.0, 1500.0), Pollen("birch_pollen", "bouleau", 15.0, 90.0, 1500.0),
    Pollen("olive_pollen", "olivier", 15.0, 90.0, 1500.0), Pollen("grass_pollen", "graminées", 20.0, 50.0, 200.0),
    Pollen("mugwort_pollen", "armoise", 10.0, 50.0, 500.0), Pollen("ragweed_pollen", "ambroisie", 5.0, 20.0, 100.0),
)

/** 0 none, 1 low, 2 moderate, 3 high, 4 very high. */
internal fun pollenLevel(p: Pollen, v: Double): Int = when {
    v < 1 -> 0
    v < p.moderate -> 1
    v < p.high -> 2
    v < p.veryHigh -> 3
    else -> 4
}

internal fun levelWords(level: Int) = listOf("nul", "faible", "moyen", "élevé", "très élevé")[level.coerceIn(0, 4)]

/** The European index's colours, band by band (as the European Environment Agency draws them). */
internal fun aqiColor(aqi: Int): Color = when {
    aqi <= 20 -> Color(0xFF50F0E6)
    aqi <= 40 -> Color(0xFF50CCAA)
    aqi <= 60 -> Color(0xFFF0E641)
    aqi <= 80 -> Color(0xFFFF5050)
    aqi <= 100 -> Color(0xFF960032)
    else -> Color(0xFF7D2181)
}

private fun JsonElement?.num() = (this as? JsonPrimitive)?.doubleOrNull

/** The next days' worst: for each day, the highest index and each pollen's highest level (moderate or more), from the hourly figures. */
internal fun airForecastWords(json: String, today: LocalDate): String {
    val hourly = (Json.parseToJsonElement(json) as? JsonObject)?.get("hourly") as? JsonObject ?: return "Prévision indisponible."
    val times = (hourly["time"] as? JsonArray).orEmpty().map { LocalDateTime.parse((it as JsonPrimitive).content) }
    fun series(k: String) = (hourly[k] as? JsonArray).orEmpty().map { it.num() }
    val aqi = series("european_aqi")
    val days = times.map { it.toLocalDate() }.distinct().filter { !it.isBefore(today) }.take(2)
    return days.joinToString(" ") { day ->
        val idx = times.indices.filter { times[it].toLocalDate() == day && times[it].hour in 7..21 }
        val maxAqi = idx.mapNotNull { aqi.getOrNull(it) }.maxOrNull()?.toInt()
        val pollens = POLLENS.mapNotNull { p ->
            val s = series(p.key)
            val max = idx.mapNotNull { s.getOrNull(it) }.maxOrNull() ?: return@mapNotNull null
            pollenLevel(p, max).takeIf { it >= 2 }?.let { "${p.name} ${levelWords(it)}" }
        }
        (if (day == today) "Aujourd’hui" else "Demain") + " : " + (maxAqi?.let { "air ${describeEuropeanAqi(it)} au pire (indice $it)" } ?: "indice inconnu") +
            (if (pollens.isNotEmpty()) ", pollens : ${pollens.joinToString(", ")}." else ", pas de pollen notable.")
    }
}

/**
 * The morning briefing's sentence: today's pollens from moderate between 7 h and 21 h (only those that bother the user, when they said
 * which), and the air when it gets bad (above 60), or nothing on a clean day.
 */
internal fun airBriefingWords(json: String, today: LocalDate, pollens: Set<String> = emptySet()): String? {
    val hourly = (Json.parseToJsonElement(json) as? JsonObject)?.get("hourly") as? JsonObject ?: return null
    val times = (hourly["time"] as? JsonArray).orEmpty().map { LocalDateTime.parse((it as JsonPrimitive).content) }
    val idx = times.indices.filter { times[it].toLocalDate() == today && times[it].hour in 7..21 }
    if (idx.isEmpty()) return null
    fun dayMax(k: String) = (hourly[k] as? JsonArray).orEmpty().let { s -> idx.mapNotNull { s.getOrNull(it).num() }.maxOrNull() }
    val risky = POLLENS.filter { pollens.isEmpty() || it.key in pollens }.mapNotNull { p -> dayMax(p.key)?.let { p to pollenLevel(p, it) } }
        .filter { it.second >= 2 }.sortedByDescending { it.second }
    val parts = ArrayList<String>()
    if (risky.isNotEmpty()) parts += "risque pollen aujourd’hui : " + risky.joinToString(", ") { (p, l) -> "${p.name} ${levelWords(l)}" }
    dayMax("european_aqi")?.toInt()?.takeIf { it > 60 }?.let { parts += "air ${describeEuropeanAqi(it)} au pire (indice $it)" }
    return if (parts.isEmpty()) null else parts.joinToString(", ")
}

/** A cell of the air map: where, the index there. */
internal data class AirCell(val lat: Double, val lon: Double, val aqi: Int)

/** Open-Meteo's answer for many places: each one's current index. */
internal fun parseAirGrid(json: String): List<AirCell> {
    val root = Json.parseToJsonElement(json)
    val each = (root as? JsonArray) ?: listOf(root)
    return each.mapNotNull { e ->
        val o = e as? JsonObject ?: return@mapNotNull null
        val cur = o["current"] as? JsonObject ?: return@mapNotNull null
        AirCell(o["latitude"].num() ?: return@mapNotNull null, o["longitude"].num() ?: return@mapNotNull null, cur["european_aqi"].num()?.toInt() ?: return@mapNotNull null)
    }
}

internal object AirData {
    private const val BASE = "https://air-quality-api.open-meteo.com/v1/air-quality"

    private fun get(ctx: JarvisContainer, url: String): String? = try {
        ctx.http.newCall(Request.Builder().url(url).build()).execute().use { if (it.isSuccessful) it.body?.string() else null }
    } catch (_: Exception) {
        null
    }

    suspend fun forecast(ctx: JarvisContainer, lat: Double, lon: Double): String? = withContext(Dispatchers.IO) {
        get(ctx, "$BASE?latitude=%.3f&longitude=%.3f&hourly=european_aqi,${POLLENS.joinToString(",") { it.key }}&forecast_days=2&timezone=auto".format(Locale.US, lat, lon))
    }

    suspend fun now(ctx: JarvisContainer, lat: Double, lon: Double): String? = withContext(Dispatchers.IO) {
        get(ctx, com.jarvis.android.air.airQualityUrl(lat, lon))
    }

    /** The index on a 9 × 9 grid around a place, every 0.35° (about 35 km): 81 places in one question. */
    suspend fun grid(ctx: JarvisContainer, lat: Double, lon: Double): List<AirCell> = withContext(Dispatchers.IO) {
        val lats = ArrayList<String>()
        val lons = ArrayList<String>()
        for (i in -4..4) for (j in -4..4) { lats += "%.2f".format(Locale.US, lat + i * 0.35); lons += "%.2f".format(Locale.US, lon + j * 0.5) }
        // the answers come in the order asked, each moved to the model's own grid: drawn where asked, the cells meet
        val cells = get(ctx, "$BASE?latitude=${lats.joinToString(",")}&longitude=${lons.joinToString(",")}&current=european_aqi")?.let { try { parseAirGrid(it) } catch (_: Exception) { null } }.orEmpty()
        if (cells.size == lats.size) cells.mapIndexed { i, c -> c.copy(lat = lats[i].toDouble(), lon = lons[i].toDouble()) } else cells
    }
}

/** What the watch would say now: bad air, high pollens (only those asked for), or nothing. */
internal fun airAlertWords(json: String, threshold: Int, pollens: Set<String>): String? {
    val cur = (Json.parseToJsonElement(json) as? JsonObject)?.get("current") as? JsonObject ?: return null
    val parts = ArrayList<String>()
    cur["european_aqi"].num()?.toInt()?.takeIf { it > threshold }?.let { parts += "air ${describeEuropeanAqi(it)} (indice $it)" }
    POLLENS.filter { pollens.isEmpty() || it.key in pollens }.forEach { p ->
        cur[p.key].num()?.let { v -> pollenLevel(p, v).takeIf { it >= 3 }?.let { parts += "pollen de ${p.name} ${levelWords(it)} (${v.toInt()} grains/m³)" } }
    }
    return if (parts.isEmpty()) null else parts.joinToString(", ").replaceFirstChar { it.uppercase() } + " à votre position."
}

internal object AirWatch {
    private fun prefs(c: Context) = c.getSharedPreferences("air_watch", Context.MODE_PRIVATE)
    fun enabled(c: Context) = prefs(c).getBoolean("on", false)

    /** The pollens the user said bother them (API keys; empty: all). */
    fun pollens(c: Context): Set<String> = prefs(c).getStringSet("pollens", emptySet()).orEmpty()

    fun set(c: Context, on: Boolean, threshold: Int = 60, pollens: Set<String> = emptySet()) {
        prefs(c).edit().putBoolean("on", on).putInt("threshold", threshold).putStringSet("pollens", pollens).apply()
        val wm = WorkManager.getInstance(c)
        if (on) wm.enqueueUniquePeriodicWork(
            "air_watch", ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<AirWatchWorker>(2, TimeUnit.HOURS).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build(),
        ) else wm.cancelUniqueWork("air_watch")
    }

    /** One look in the daytime; told once a day for the same trouble. */
    suspend fun check(c: Context) {
        if (!enabled(c) || java.time.LocalTime.now().hour !in 7..21) return
        val ctx = (c.applicationContext as JarvisApp).container
        val fix = (com.jarvis.android.location.locate(c, 6 * 3_600_000L) as? com.jarvis.android.location.LocationOutcome.Found)?.fix ?: return
        val p = prefs(c)
        val body = AirData.now(ctx, fix.latitude, fix.longitude) ?: return
        val text = airAlertWords(body, p.getInt("threshold", 60), p.getStringSet("pollens", emptySet()).orEmpty()) ?: return
        val key = LocalDate.now().toString() + "|" + text.substringBefore(" (")
        if (p.getString("told", null) == key) return
        p.edit().putString("told", key).apply()
        c.getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("jarvis_air", tr("Qualité de l’air et pollens"), NotificationManager.IMPORTANCE_DEFAULT))
        try {
            NotificationManagerCompat.from(c).notify(
                7_995,
                NotificationCompat.Builder(c, "jarvis_air").setSmallIcon(android.R.drawable.ic_dialog_alert).setContentTitle(tr("Qualité de l’air et pollens"))
                    .setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text)).setAutoCancel(true).build(),
            )
        } catch (_: SecurityException) {
        }
    }
}

class AirWatchWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result { try { AirWatch.check(applicationContext) } catch (_: Exception) {}; return Result.success() }
}

/** Where the air map is centred (the city asked for, or the user). */
internal object AirMapCenter {
    @Volatile var at: Pair<Double, Double>? = null
}
