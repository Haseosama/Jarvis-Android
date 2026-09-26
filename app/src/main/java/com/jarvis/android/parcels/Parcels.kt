package com.jarvis.android.parcels

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import com.jarvis.android.i18n.trf
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import java.io.File
import java.net.URLEncoder
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/*
 * Parcels: "suis mon colis 6A12345678901". The carrier is recognised from the number; La Poste, Colissimo and
 * Chronopost parcels are followed through La Poste's official tracking API (with the user's own free developer key),
 * checked every few hours, and each new step is notified. For the other carriers, Jarvis opens their tracking page.
 * A tracking number seen in a message ("votre colis … est expédié") can be followed with one tap.
 */

internal enum class Carrier(val label: String) {
    LAPOSTE("La Poste / Colissimo / Chronopost"), UPS("UPS"), DHL("DHL"), DPD("DPD"), GLS("GLS"),
    MONDIAL_RELAY("Mondial Relay"), AMAZON("Amazon"), UNKNOWN("transporteur inconnu"),
}

internal fun cleanNumber(raw: String): String = raw.uppercase().filter { it.isLetterOrDigit() }

/** The carriers a number can belong to, most likely first; empty when it looks like no tracking number. */
internal fun detectCarriers(raw: String): List<Carrier> {
    val n = cleanNumber(raw)
    return when {
        Regex("^1Z[0-9A-Z]{16}$").matches(n) -> listOf(Carrier.UPS)
        Regex("^TBA\\d{12}$").matches(n) -> listOf(Carrier.AMAZON)
        Regex("^(?:JJD|JVGL)\\d{10,24}$").matches(n) -> listOf(Carrier.DHL)
        // The international postal format (CB123456789FR), and Colissimo's (6A12345678901): La Poste's API covers both.
        Regex("^[A-Z]{2}\\d{9}[A-Z]{2}$").matches(n) -> listOf(Carrier.LAPOSTE)
        Regex("^\\d[A-Z]\\d{11}$").matches(n) || Regex("^[A-Z]{2}\\d{11}$").matches(n) -> listOf(Carrier.LAPOSTE)
        Regex("^\\d{14}$").matches(n) -> listOf(Carrier.DPD)
        Regex("^\\d{11}$").matches(n) -> listOf(Carrier.GLS, Carrier.LAPOSTE)
        Regex("^\\d{10}$").matches(n) -> listOf(Carrier.DHL)
        Regex("^\\d{8}$").matches(n) || Regex("^\\d{12}$").matches(n) -> listOf(Carrier.MONDIAL_RELAY)
        Regex("^[0-9A-Z]{10,30}$").matches(n) && n.any { it.isDigit() } -> listOf(Carrier.UNKNOWN)
        else -> emptyList()
    }
}

/** The carrier's own tracking page for [number]. */
internal fun trackingUrl(carrier: Carrier, number: String): String {
    val n = URLEncoder.encode(cleanNumber(number), "UTF-8")
    return when (carrier) {
        Carrier.LAPOSTE -> "https://www.laposte.fr/outils/suivre-vos-envois?code=$n"
        Carrier.UPS -> "https://www.ups.com/track?loc=fr_FR&tracknum=$n"
        Carrier.DHL -> "https://www.dhl.com/fr-fr/home/suivi.html?tracking-id=$n"
        Carrier.DPD -> "https://trace.dpd.fr/fr/trace/$n"
        Carrier.GLS -> "https://gls-group.com/FR/fr/suivi-colis?match=$n"
        Carrier.MONDIAL_RELAY -> "https://www.mondialrelay.fr/suivi-de-colis/?NumeroExpedition=$n"
        Carrier.AMAZON -> "https://track.amazon.fr/tracking/$n"
        Carrier.UNKNOWN -> "https://www.google.com/search?q=" + URLEncoder.encode("suivi colis ${cleanNumber(number)}", "UTF-8")
    }
}

private val PARCEL_WORDS = Regex("(?i)colis|suivi|livraison|livr[ée]|exp[ée]di|envoi|shipped|shipment|package|tracking|parcel")
private val CANDIDATE = Regex("\\b(?:1Z[0-9A-Z]{16}|TBA\\d{12}|JJD\\d{10,24}|[A-Z]{2}\\d{9}[A-Z]{2}|\\d[A-Z]\\d{11}|[A-Z]{2}\\d{11})\\b")

/**
 * Tracking numbers in a message that talks about a parcel. Only the unmistakable formats: a bare run of digits is as
 * likely a phone number or an order number, so it is never picked up by itself.
 */
internal fun findTrackingNumbers(text: String): List<String> =
    if (!PARCEL_WORDS.containsMatchIn(text)) emptyList() else CANDIDATE.findAll(text.uppercase()).map { it.value }.distinct().toList()

internal data class TrackStatus(val label: String, val date: String, val delivered: Boolean)

/** La Poste's tracking answer: the latest event (the list comes newest first) and whether the parcel has arrived. */
internal fun parseLaPoste(body: String): TrackStatus? = try {
    val root = Json.parseToJsonElement(body).jsonObject
    val ship = root["shipment"]?.jsonObject ?: return null
    val events = ship["event"]?.jsonArray.orEmpty().map { it.jsonObject }
    val latest = events.maxByOrNull { it["date"]?.jsonPrimitive?.contentOrNull.orEmpty() } ?: return null
    val delivered = ship["isFinal"]?.jsonPrimitive?.booleanOrNull == true || latest["code"]?.jsonPrimitive?.contentOrNull.orEmpty().startsWith("DI")
    TrackStatus(latest["label"]?.jsonPrimitive?.contentOrNull.orEmpty().trim(), latest["date"]?.jsonPrimitive?.contentOrNull.orEmpty(), delivered)
} catch (_: Exception) {
    null
}

/** "le 26/09 à 14 h 05" from an ISO date with offset, in [zone]. */
internal fun eventWhen(iso: String, zone: ZoneId): String = try {
    val t = OffsetDateTime.parse(iso).atZoneSameInstant(zone)
    "le ${t.dayOfMonth}/${t.monthValue.toString().padStart(2, '0')} à ${t.hour} h" + if (t.minute == 0) "" else " ${t.minute.toString().padStart(2, '0')}"
} catch (_: Exception) {
    ""
}

@Serializable
internal data class Parcel(
    val number: String,
    val carrier: Carrier,
    val label: String = "",
    val status: String = "",
    val statusAt: String = "",
    val delivered: Boolean = false,
    val addedAt: Long = 0,
    val deliveredSeenAt: Long = 0,
)

@Serializable
private data class ParcelData(val items: List<Parcel> = emptyList(), val offered: List<String> = emptyList())

internal class ParcelStore(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    private fun load(): ParcelData = try {
        if (file.exists()) json.decodeFromString<ParcelData>(file.readText()) else ParcelData()
    } catch (_: Exception) {
        ParcelData()
    }

    @Synchronized
    private fun save(d: ParcelData) {
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.encodeToString(d))
            if (!tmp.renameTo(file)) { file.writeText(tmp.readText()); tmp.delete() }
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun all(): List<Parcel> = load().items

    @Synchronized
    fun update(change: (List<Parcel>) -> List<Parcel>): List<Parcel> {
        val d = load()
        val next = change(d.items)
        save(d.copy(items = next))
        return next
    }

    /** True the first time [number] is offered for tracking (so a message seen twice is not offered twice). */
    @Synchronized
    fun offerOnce(number: String): Boolean {
        val d = load()
        if (number in d.offered || d.items.any { it.number == number }) return false
        save(d.copy(offered = (d.offered + number).takeLast(100)))
        return true
    }
}

internal const val DELIVERED_KEEP_MS = 7 * 24 * 60 * 60_000L

internal object ParcelTracking {
    private const val CHANNEL = "jarvis_parcels"

    fun container(context: Context): JarvisContainer = (context.applicationContext as JarvisApp).container

    fun schedule(context: Context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "parcels", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<ParcelWorker>(3, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build(),
        )
    }

    sealed interface Fetch {
        data class Ok(val status: TrackStatus) : Fetch
        data object NoKey : Fetch
        data object BadKey : Fetch
        data object NotFound : Fetch
        data object Failed : Fetch
    }

    /** Asks La Poste where [number] is. Blocking: call off the main thread. */
    fun fetchLaPoste(ctx: JarvisContainer, number: String): Fetch {
        val key = ctx.configStore.getLaPosteKey()?.takeIf { it.isNotBlank() } ?: return Fetch.NoKey
        val request = Request.Builder()
            .url("https://api.laposte.fr/suivi/v2/idships/" + URLEncoder.encode(cleanNumber(number), "UTF-8") + "?lang=fr_FR")
            .header("X-Okapi-Key", key).header("Accept", "application/json").build()
        return try {
            ctx.http.newCall(request).execute().use { r ->
                val body = r.body?.string().orEmpty()
                when (r.code) {
                    200, 207 -> parseLaPoste(body)?.let { Fetch.Ok(it) } ?: Fetch.NotFound
                    401, 403 -> Fetch.BadKey
                    400, 404 -> Fetch.NotFound
                    else -> Fetch.Failed
                }
            }
        } catch (_: Exception) {
            Fetch.Failed
        }
    }

    /** Refreshes the La Poste parcels; returns those whose status changed. Forgets parcels delivered a week ago. */
    fun refreshAll(ctx: JarvisContainer, now: Long = System.currentTimeMillis()): List<Parcel> {
        val changed = mutableListOf<Parcel>()
        val fresh = ctx.parcelStore.all().filterNot { it.delivered && it.deliveredSeenAt > 0 && now - it.deliveredSeenAt > DELIVERED_KEEP_MS }.map { p ->
            if (p.carrier != Carrier.LAPOSTE || p.delivered) return@map p
            val f = fetchLaPoste(ctx, p.number) as? Fetch.Ok ?: return@map p
            val next = p.copy(status = f.status.label, statusAt = f.status.date, delivered = f.status.delivered, deliveredSeenAt = if (f.status.delivered) now else 0)
            if (next.status != p.status && next.status.isNotBlank()) changed += next
            next
        }
        ctx.parcelStore.update { fresh }
        return changed
    }

    fun notifyChange(context: Context, p: Parcel) {
        try {
            channel(context)
            NotificationManagerCompat.from(context).notify(
                p.number.hashCode(),
                NotificationCompat.Builder(context, CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_menu_send)
                    .setContentTitle(trf("Colis {0}", p.label.ifBlank { p.number }))
                    .setContentText(p.status)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(p.status))
                    .setContentIntent(PendingIntent.getActivity(context, p.number.hashCode(),
                        Intent(Intent.ACTION_VIEW, Uri.parse(trackingUrl(p.carrier, p.number))), PendingIntent.FLAG_IMMUTABLE))
                    .setAutoCancel(true)
                    .build(),
            )
        } catch (_: SecurityException) {
        }
    }

    /** A message mentions a tracking number not followed yet: offer to follow it (one tap), once. */
    fun offerFromMessage(context: Context, app: String, text: String) {
        val store = container(context).parcelStore
        for (number in findTrackingNumbers(text).take(2)) {
            if (!store.offerOnce(number)) continue
            try {
                channel(context)
                val follow = PendingIntent.getBroadcast(
                    context, number.hashCode(), Intent(context, ParcelReceiver::class.java).setAction(ParcelReceiver.ACTION_FOLLOW).putExtra("number", number),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                NotificationManagerCompat.from(context).notify(
                    number.hashCode(),
                    NotificationCompat.Builder(context, CHANNEL)
                        .setSmallIcon(android.R.drawable.ic_menu_send)
                        .setContentTitle(tr("Suivre ce colis ?"))
                        .setContentText(trf("Numéro {0} vu dans {1}.", number, app))
                        .addAction(0, tr("Suivre"), follow)
                        .setAutoCancel(true)
                        .build(),
                )
            } catch (_: SecurityException) {
            }
        }
    }

    private fun channel(context: Context) {
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, tr("Colis"), NotificationManager.IMPORTANCE_DEFAULT))
    }
}

class ParcelWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val ctx = ParcelTracking.container(applicationContext)
        if (ctx.parcelStore.all().isEmpty()) return Result.success()
        ParcelTracking.refreshAll(ctx).forEach { ParcelTracking.notifyChange(applicationContext, it) }
        return Result.success()
    }
}

/** The "Suivre" button of a tracking number seen in a message. */
class ParcelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FOLLOW) return
        val number = cleanNumber(intent.getStringExtra("number").orEmpty())
        val carrier = detectCarriers(number).firstOrNull() ?: return
        val ctx = ParcelTracking.container(context)
        ctx.parcelStore.update { list -> if (list.any { it.number == number }) list else list + Parcel(number, carrier, addedAt = System.currentTimeMillis()) }
        ParcelTracking.schedule(context)
        NotificationManagerCompat.from(context).cancel(number.hashCode())
    }

    companion object {
        const val ACTION_FOLLOW = "com.jarvis.android.PARCEL_FOLLOW"
    }
}
