package com.jarvis.android.weather

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.jarvis.android.JarvisApp
import com.jarvis.android.i18n.tr
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.time.Duration
import java.time.LocalDateTime
import java.util.Locale
import java.util.concurrent.TimeUnit

/*
 * "Est-ce qu'il va pleuvoir dans l'heure ?" and, if switched on, a notification when rain is about to start where the phone
 * is. Open-Meteo's 15-minute precipitation forecast (free, no key; fine-grained over Europe), the next two hours.
 */

/** Below this, per 15 minutes, it is not rain worth mentioning (a few drops). */
internal const val RAIN_THRESHOLD_MM = 0.1
/** Rain starting within this long is announced by the watcher. */
internal const val RAIN_ALERT_MINUTES = 45L
/** One alert per rain spell: none again within this long. */
internal const val RAIN_ALERT_COOLDOWN_MS = 3 * 3_600_000L

internal data class RainSlot(val start: LocalDateTime, val mm: Double)

/** Open-Meteo's `minutely_15` block (local times, since the request asks for timezone=auto). */
internal fun parseRainSlots(body: String): List<RainSlot> {
    val root = Json.parseToJsonElement(body) as? JsonObject ?: error("Invalid forecast")
    val block = root["minutely_15"] as? JsonObject ?: error("No 15-minute forecast")
    val times = (block["time"] as? JsonArray).orEmpty().map { (it as JsonPrimitive).contentOrNull.orEmpty() }
    val values = (block["precipitation"] as? JsonArray).orEmpty().map { (it as? JsonPrimitive)?.doubleOrNull ?: 0.0 }
    return times.zip(values).mapNotNull { (t, v) -> runCatching { RainSlot(LocalDateTime.parse(t), v) }.getOrNull() }
}

/** "Now" on the forecast's own clock: its times are local to the place asked about (timezone=auto), maybe not the phone's. */
internal fun forecastNow(body: String, utcNow: java.time.Instant = java.time.Instant.now()): LocalDateTime {
    val offset = ((Json.parseToJsonElement(body) as? JsonObject)?.get("utc_offset_seconds") as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
    return if (offset == null) LocalDateTime.now() else LocalDateTime.ofInstant(utcNow, java.time.ZoneOffset.ofTotalSeconds(offset))
}

/** mm per 15 minutes →"faible" / "modérée" / "forte" (the usual 2.5 and 7.6 mm/h limits). */
internal fun rainIntensity(mmPer15: Double): String {
    val perHour = mmPer15 * 4
    return when {
        perHour < 2.5 -> "faible"
        perHour < 7.6 -> "modérée"
        else -> "forte"
    }
}

private fun hm(t: LocalDateTime) = if (t.minute == 0) "${t.hour} h" else "${t.hour} h ${t.minute.toString().padStart(2, '0')}"

/** What the next two hours hold, in one sentence. */
internal fun describeRain(slots: List<RainSlot>, now: LocalDateTime): String {
    val ahead = slots.filter { !it.start.plusMinutes(15).isBefore(now) }.take(9)
    if (ahead.isEmpty()) return "Prévision de pluie indisponible pour le moment."
    val rainingNow = ahead.first().mm >= RAIN_THRESHOLD_MM
    if (rainingNow) {
        val end = ahead.firstOrNull { it.mm < RAIN_THRESHOLD_MM }
        val peak = ahead.takeWhile { it.mm >= RAIN_THRESHOLD_MM }.maxOf { it.mm }
        return "Il pleut en ce moment (pluie ${rainIntensity(peak)})" +
            (if (end != null) ", jusqu'à environ ${hm(end.start)}." else " et ça devrait durer au moins deux heures.")
    }
    val first = ahead.firstOrNull { it.mm >= RAIN_THRESHOLD_MM }
        ?: return "Pas de pluie prévue dans les deux prochaines heures."
    val minutes = Duration.between(now, first.start).toMinutes().coerceAtLeast(0)
    val peak = ahead.dropWhile { it.mm < RAIN_THRESHOLD_MM }.takeWhile { it.mm >= RAIN_THRESHOLD_MM }.maxOf { it.mm }
    return "Pluie ${rainIntensity(peak)} attendue vers ${hm(first.start)}, dans environ $minutes minutes."
}

/** The watcher's rule: rain starting soon, not already raining, and no alert in the last three hours. */
internal fun shouldAlertRain(slots: List<RainSlot>, now: LocalDateTime, lastAlertMs: Long, nowMs: Long): Boolean {
    if (nowMs - lastAlertMs < RAIN_ALERT_COOLDOWN_MS) return false
    val ahead = slots.filter { !it.start.plusMinutes(15).isBefore(now) }
    if (ahead.isEmpty() || ahead.first().mm >= RAIN_THRESHOLD_MM) return false
    val first = ahead.firstOrNull { it.mm >= RAIN_THRESHOLD_MM } ?: return false
    return Duration.between(now, first.start).toMinutes() <= RAIN_ALERT_MINUTES
}

internal fun rainForecastUrl(latitude: Double, longitude: Double): String =
    "https://api.open-meteo.com/v1/forecast".toHttpUrl().newBuilder()
        .addQueryParameter("latitude", "%.3f".format(Locale.ROOT, latitude))
        .addQueryParameter("longitude", "%.3f".format(Locale.ROOT, longitude))
        .addQueryParameter("minutely_15", "precipitation")
        .addQueryParameter("forecast_minutely_15", "12")
        .addQueryParameter("past_minutely_15", "1")
        .addQueryParameter("timezone", "auto")
        .build().toString()

/** Every 15 minutes while "alerte pluie" is on: looks at the next two hours where the phone is, warns once per spell. */
class RainWatchWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as JarvisApp).container
        if (!container.configStore.rainAlerts.first()) return Result.success()
        val fix = (locate(applicationContext) as? LocationOutcome.Found)?.fix ?: return Result.success()
        return try {
            val body = container.http.newCall(Request.Builder().url(rainForecastUrl(fix.latitude, fix.longitude)).build()).execute()
                .use { if (it.isSuccessful) it.body?.string() else null } ?: return Result.success()
            val slots = parseRainSlots(body)
            val prefs = applicationContext.getSharedPreferences("jarvis_rain_watch", Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            val localNow = forecastNow(body)
            if (shouldAlertRain(slots, localNow, prefs.getLong("last", 0L), now)) {
                prefs.edit().putLong("last", now).apply()
                notify(describeRain(slots, localNow))
            }
            Result.success()
        } catch (_: Exception) {
            Result.success()
        }
    }

    private fun notify(text: String) {
        try {
            val manager = applicationContext.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, tr("Alerte pluie"), NotificationManager.IMPORTANCE_DEFAULT))
            NotificationManagerCompat.from(applicationContext).notify(7_701, NotificationCompat.Builder(applicationContext, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(tr("Pluie en approche"))
                .setContentText(text)
                .setAutoCancel(true)
                .build())
        } catch (_: SecurityException) {
        }
    }

    companion object {
        private const val CHANNEL = "jarvis_rain"
        private const val WORK = "jarvis_rain_watch"

        fun apply(context: Context, enabled: Boolean) {
            val work = WorkManager.getInstance(context)
            if (enabled) work.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, PeriodicWorkRequestBuilder<RainWatchWorker>(15, TimeUnit.MINUTES).build())
            else work.cancelUniqueWork(WORK)
        }
    }
}
