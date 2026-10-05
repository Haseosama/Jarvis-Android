package com.jarvis.android.actions

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
import com.jarvis.android.i18n.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.time.Instant
import java.time.LocalDate
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * The fuel price watch (`prix_carburant` alert_on): a fuel, a price in €/L and a place, looked at every 4 hours in the
 * daytime; a notification when the cheapest station there is at or under the price. The place is fixed when the watch is
 * set (a city, or where the phone was), so the check needs no position in the background.
 */
internal object FuelWatch {
    data class Place(val city: String?, val latitude: Double?, val longitude: Double?, val radiusKm: Int, val label: String)

    private const val WORK = "fuel_watch"
    private const val CHANNEL = "jarvis_fuel"
    private fun prefs(c: Context) = c.getSharedPreferences("fuel_watch", Context.MODE_PRIVATE)

    fun on(c: Context, fuel: Fuel, threshold: Double, place: Place) {
        prefs(c).edit().clear()
            .putBoolean("on", true).putString("fuel", fuel.name).putFloat("threshold", threshold.toFloat())
            .putString("city", place.city).putString("label", place.label).putInt("radius", place.radiusKm)
            .putString("lat", place.latitude?.toString()).putString("lon", place.longitude?.toString())
            .apply()
        WorkManager.getInstance(c).enqueueUniquePeriodicWork(
            WORK, ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<FuelWatchWorker>(4, TimeUnit.HOURS).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build(),
        )
    }

    fun off(c: Context) {
        prefs(c).edit().putBoolean("on", false).apply()
        WorkManager.getInstance(c).cancelUniqueWork(WORK)
    }

    fun describe(c: Context): String {
        val p = prefs(c)
        if (!p.getBoolean("on", false)) return "Aucune alerte de prix du carburant."
        val fuel = p.getString("fuel", null)?.let { runCatching { Fuel.valueOf(it) }.getOrNull() } ?: return "Aucune alerte de prix du carburant."
        return String.format(Locale.FRANCE, "Je surveille le %s %s : alerte sous %.3f €/L.", fuel.label, p.getString("label", "").orEmpty(), p.getFloat("threshold", 0f).toDouble())
    }

    /** One look in the daytime; see [shouldTellFuel] for how often it tells. */
    suspend fun check(c: Context) {
        val p = prefs(c)
        if (!p.getBoolean("on", false) || java.time.LocalTime.now().hour !in 7..21) return
        val fuel = p.getString("fuel", null)?.let { runCatching { Fuel.valueOf(it) }.getOrNull() } ?: return
        val threshold = p.getFloat("threshold", 0f).toDouble().takeIf { it > 0 } ?: return
        val city = p.getString("city", null)
        val lat = p.getString("lat", null)?.toDoubleOrNull()
        val lon = p.getString("lon", null)?.toDoubleOrNull()
        val origin = if (lat != null && lon != null) lat to lon else null
        val filter = when {
            city != null -> cityFilter(city)
            origin != null -> circleFilter(origin.first, origin.second, p.getInt("radius", 5))
            else -> return
        }
        val ctx = (c.applicationContext as JarvisApp).container
        val body = withContext(Dispatchers.IO) {
            ctx.http.newCall(Request.Builder().url(fuelUrl(fuel, filter)).build()).execute().use { if (it.isSuccessful) it.body?.string() else null }
        } ?: return
        val ranked = rankStations(parseStations(body, fuel), fuel, Instant.now(), city, origin)
        val text = fuelAlertWords(ranked, fuel, threshold, p.getString("label", "").orEmpty(), origin) ?: return
        val best = ranked.first()
        val key = fuelAlertKey(best)
        val today = LocalDate.now().toString()
        if (!shouldTellFuel(p.getString("told", null), p.getString("told_day", null), p.getFloat("told_price", Float.MAX_VALUE).toDouble(), key, today, best.price)) return
        p.edit().putString("told", key).putString("told_day", today).putFloat("told_price", best.price.toFloat()).apply()
        com.jarvis.android.journal.Journal.alert(c, com.jarvis.android.journal.AlertKind.FUEL, text)
        c.getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, tr("Prix du carburant"), NotificationManager.IMPORTANCE_DEFAULT))
        try {
            NotificationManagerCompat.from(c).notify(
                7_975,
                NotificationCompat.Builder(c, CHANNEL).setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(tr("Carburant sous votre seuil"))
                    .setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text)).setAutoCancel(true).build(),
            )
        } catch (_: SecurityException) {
        }
    }
}

class FuelWatchWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result { try { FuelWatch.check(applicationContext) } catch (_: Exception) {}; return Result.success() }
}
