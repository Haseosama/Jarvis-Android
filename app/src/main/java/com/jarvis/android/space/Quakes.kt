package com.jarvis.android.space

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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
import com.jarvis.android.tool.Tool
import com.jarvis.android.location.distanceKm
import com.jarvis.android.tool.objectSchema
import com.jarvis.android.tool.stringArg
import com.jarvis.android.i18n.tr
import com.jarvis.android.video.VideoPanel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.Request
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.TimeUnit
import com.jarvis.android.video.SkyModes

/*
 * Earthquakes: the USGS feeds (the whole world, magnitude 2.5 and more over the last day, 4.5 and more over the week), on the world map
 * and in words, with how far each is from the user; and a watch that says when one is felt near home or near the places the user
 * asked to watch for their family or friends.
 */

/** An earthquake: where, how strong, how deep, when, and USGS's own words and page. */
internal data class Quake(
    val id: String, val mag: Double, val place: String, val timeMs: Long, val lat: Double, val lon: Double, val depthKm: Double,
    val url: String, val tsunami: Boolean, val alert: String?, val felt: Int?,
)

internal fun parseQuakes(json: String): List<Quake> = try {
    ((Json.parseToJsonElement(json) as JsonObject)["features"] as JsonArray).mapNotNull { f ->
        val o = f as? JsonObject ?: return@mapNotNull null
        val p = o["properties"] as? JsonObject ?: return@mapNotNull null
        val c = ((o["geometry"] as? JsonObject)?.get("coordinates") as? JsonArray) ?: return@mapNotNull null
        fun d(i: Int) = (c.getOrNull(i) as? JsonPrimitive)?.doubleOrNull
        fun s(k: String) = (p[k] as? JsonPrimitive)?.contentOrNull
        Quake(
            (o["id"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null, (p["mag"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null,
            s("place").orEmpty(), (p["time"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null, d(1) ?: return@mapNotNull null, d(0) ?: return@mapNotNull null,
            d(2) ?: 0.0, s("url").orEmpty(), (p["tsunami"] as? JsonPrimitive)?.intOrNull == 1, s("alert"), (p["felt"] as? JsonPrimitive)?.intOrNull,
        )
    }
} catch (_: Exception) {
    emptyList()
}

/** The magnitude in words, as seismologists class them. */
internal fun magnitudeWords(m: Double): String = when {
    m < 3 -> "très faible"
    m < 4 -> "faible"
    m < 5 -> "léger"
    m < 6 -> "modéré"
    m < 7 -> "fort"
    m < 8 -> "majeur"
    else -> "très majeur"
}

/** USGS's "100 km W of Petrolia, CA" in French: "à 100 km à l’ouest de Petrolia, CA". */
internal fun placeWords(place: String): String {
    val m = Regex("^(\\d+) km ([NSEW]{1,3}) of (.+)$").find(place.trim()) ?: return place.replaceFirstChar { it.uppercase() }
    val dir = mapOf("N" to "au nord", "S" to "au sud", "E" to "à l’est", "W" to "à l’ouest", "NE" to "au nord-est", "NW" to "au nord-ouest", "SE" to "au sud-est", "SW" to "au sud-ouest",
        "NNE" to "au nord-nord-est", "ENE" to "à l’est-nord-est", "ESE" to "à l’est-sud-est", "SSE" to "au sud-sud-est", "SSW" to "au sud-sud-ouest", "WSW" to "à l’ouest-sud-ouest",
        "WNW" to "à l’ouest-nord-ouest", "NNW" to "au nord-nord-ouest")[m.groupValues[2]] ?: m.groupValues[2]
    return "à ${m.groupValues[1]} km $dir de ${m.groupValues[3]}"
}

/** One quake in a sentence: "séisme de magnitude 4,7 (léger) à 100 km à l’ouest de Petrolia, CA, il y a 2 h, 2 km de profondeur". */
internal fun quakeWords(q: Quake, now: Long, fromLat: Double? = null, fromLon: Double? = null): String {
    val ago = countdownWords(q.timeMs - now)
    val away = if (fromLat != null && fromLon != null) ", à ${distanceKm(fromLat, fromLon, q.lat, q.lon).toInt()} km de vous" else ""
    return "séisme de magnitude %.1f (%s) %s, %s, %d km de profondeur%s".format(Locale.FRANCE, q.mag, magnitudeWords(q.mag), placeWords(q.place), ago, q.depthKm.toInt(), away) +
        (if (q.tsunami) ", alerte tsunami émise" else "") + when (q.alert) { "yellow" -> ", dégâts possibles (alerte jaune)"; "orange" -> ", dégâts importants probables (alerte orange)"; "red" -> ", dégâts très importants probables (alerte rouge)"; else -> "" }
}

/** A place watched for someone: its name and where. */
@Serializable
internal data class WatchedPlace(val name: String, val lat: Double, val lon: Double)

/** The quakes to tell: felt near a place (within a distance growing with the magnitude), not told before. */
internal fun quakesToTell(quakes: List<Quake>, places: List<WatchedPlace>, minMag: Double, told: Set<String>): List<Pair<Quake, WatchedPlace>> = quakes.mapNotNull { q ->
    if (q.id in told || q.mag < minMag) return@mapNotNull null
    // felt far when strong: about 100 km at 4, 250 at 5, 600 at 6, 1,500 at 7
    val reach = 100.0 * Math.pow(2.5, q.mag - 4)
    places.map { it to distanceKm(it.lat, it.lon, q.lat, q.lon) }.filter { it.second <= reach }.minByOrNull { it.second }?.let { q to it.first }
}

internal object Quakes {
    private const val FEED = "https://earthquake.usgs.gov/earthquakes/feed/v1.0/summary"
    @Volatile private var cache: Pair<Long, List<Quake>>? = null

    private fun get(ctx: JarvisContainer, url: String): String? = try {
        ctx.http.newCall(Request.Builder().url(url).header("User-Agent", "Jarvis-Android").build()).execute().use { if (it.isSuccessful) it.body?.string() else null }
    } catch (_: Exception) {
        null
    }

    /** The last day's quakes from 2.5 and the week's from 4.5, kept 5 minutes. */
    suspend fun recent(ctx: JarvisContainer): List<Quake> = withContext(Dispatchers.IO) {
        cache?.takeIf { System.currentTimeMillis() - it.first < 5 * 60_000L }?.second ?: run {
            val day = get(ctx, "$FEED/2.5_day.geojson")?.let { parseQuakes(it) }.orEmpty()
            val week = get(ctx, "$FEED/4.5_week.geojson")?.let { parseQuakes(it) }.orEmpty()
            (day + week).distinctBy { it.id }.sortedByDescending { it.timeMs }.also { if (it.isNotEmpty()) cache = System.currentTimeMillis() to it }
        }
    }

    private val json = Json { ignoreUnknownKeys = true }
    private fun prefs(c: Context) = c.getSharedPreferences("quake_watch", Context.MODE_PRIVATE)
    fun places(c: Context): List<WatchedPlace> = try { prefs(c).getString("places", null)?.let { json.decodeFromString<List<WatchedPlace>>(it) } ?: emptyList() } catch (_: Exception) { emptyList() }
    fun setPlaces(c: Context, list: List<WatchedPlace>) = prefs(c).edit().putString("places", json.encodeToString(list)).apply()
    fun enabled(c: Context) = prefs(c).getBoolean("on", false)

    fun setWatch(c: Context, on: Boolean, minMag: Double = 4.0) {
        prefs(c).edit().putBoolean("on", on).putFloat("min", minMag.toFloat()).apply()
        val wm = WorkManager.getInstance(c)
        if (on) wm.enqueueUniquePeriodicWork(
            "quake_watch", ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<QuakeWorker>(30, TimeUnit.MINUTES).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build(),
        ) else wm.cancelUniqueWork("quake_watch")
    }

    /** One look: the quakes near home (where the phone was last) or a watched place, each told once. */
    suspend fun check(c: Context) {
        if (!enabled(c)) return
        val ctx = (c.applicationContext as JarvisApp).container
        val home = (com.jarvis.android.location.locate(c, 24 * 3_600_000L) as? com.jarvis.android.location.LocationOutcome.Found)?.fix?.let { WatchedPlace("vous", it.latitude, it.longitude) }
        val places = listOfNotNull(home) + places(c)
        if (places.isEmpty()) return
        val p = prefs(c)
        val told = p.getStringSet("told", emptySet()).orEmpty()
        val now = System.currentTimeMillis()
        val fresh = recent(ctx).filter { now - it.timeMs < 6 * 3_600_000L }
        val tell = quakesToTell(fresh, places, p.getFloat("min", 4f).toDouble(), told)
        tell.forEach { (q, place) ->
            val near = if (place.name == "vous") "près de chez vous" else "près de ${place.name}"
            val text = quakeWords(q, now).replaceFirstChar { it.uppercase() } + ", à ${distanceKm(place.lat, place.lon, q.lat, q.lon).toInt()} km de ${if (place.name == "vous") "votre position" else place.name}."
            c.getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("jarvis_quakes", tr("Séismes"), NotificationManager.IMPORTANCE_HIGH))
            val open = PendingIntent.getActivity(c, q.id.hashCode(), Intent(Intent.ACTION_VIEW, Uri.parse(q.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
            try {
                NotificationManagerCompat.from(c).notify(
                    q.id.hashCode(),
                    NotificationCompat.Builder(c, "jarvis_quakes").setSmallIcon(android.R.drawable.ic_dialog_alert).setContentTitle("Séisme $near (M %.1f)".format(Locale.FRANCE, q.mag))
                        .setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text)).setContentIntent(open).setAutoCancel(true).build(),
                )
            } catch (_: SecurityException) {
            }
        }
        if (tell.isNotEmpty()) p.edit().putStringSet("told", (told + tell.map { it.first.id }).toList().takeLast(200).toSet()).apply()
    }
}

class QuakeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result { try { Quakes.check(applicationContext) } catch (_: Exception) {}; return Result.success() }
}

/** "Il y a eu des séismes ?", "montre les tremblements de terre", "préviens-moi s'il y a un séisme près de chez ma sœur à Tokyo". */
object QuakeTool : Tool {
    override val name = "earthquakes"
    override val description =
        "Séismes dans le monde (USGS : magnitude 2,5 et plus sur 24 h, 4,5 et plus sur 7 jours) : action « recent » : les plus forts du " +
            "jour et les plus proches de l’utilisateur, affichés sur la carte du monde ; « alert_on » (magnitude : 4 par défaut) : une " +
            "notification quand un séisme est ressenti près de chez l’utilisateur ou d’un lieu surveillé (la distance grandit avec la " +
            "magnitude) ; « alert_off » ; « watch_add » (place : une ville, label : pour qui, « ma sœur ») ; « watch_remove » ; « watch_list »."
    override val parameters = objectSchema(required = listOf("action")) {
        string("action", "recent, alert_on, alert_off, watch_add, watch_remove ou watch_list.")
        string("magnitude", "Pour alert_on : la magnitude minimale (4 par défaut).")
        string("place", "Pour watch_add / watch_remove : la ville à surveiller.")
        string("label", "Pour watch_add : pour qui (« ma sœur », « mes parents »).")
    }

    override suspend fun run(args: JsonObject, ctx: JarvisContainer): String {
        val c = ctx.appContext
        val now = System.currentTimeMillis()
        return when (args.stringArg("action").trim().lowercase()) {
            "alert_on" -> {
                val m = args.stringArg("magnitude").replace(',', '.').trim().toDoubleOrNull()?.coerceIn(2.5, 8.0) ?: 4.0
                Quakes.setWatch(c, true, m)
                val places = Quakes.places(c)
                "Je surveille les séismes de magnitude %.1f et plus près de chez vous".format(Locale.FRANCE, m) +
                    (if (places.isNotEmpty()) " et de ${places.joinToString(", ") { it.name }}" else "") + " (vérifié toutes les 30 minutes environ)."
            }
            "alert_off" -> { Quakes.setWatch(c, false); "Je ne surveille plus les séismes." }
            "watch_list" -> Quakes.places(c).let { l -> if (l.isEmpty()) "Aucun lieu surveillé à part votre position." else "Lieux surveillés : " + l.joinToString(", ") { it.name } + "." }
            "watch_remove" -> {
                val w = com.jarvis.android.text.normalize(args.stringArg("place"))
                val left = Quakes.places(c).filterNot { w.isNotEmpty() && w in com.jarvis.android.text.normalize(it.name) }
                Quakes.setPlaces(c, left)
                "Lieux surveillés : " + (left.joinToString(", ") { it.name }.ifEmpty { "aucun à part votre position" }) + "."
            }
            "watch_add" -> {
                val g = com.jarvis.android.driving.RouteWeather.geocode(ctx, args.stringArg("place").trim()) ?: return "Je ne trouve pas « ${args.stringArg("place")} »."
                val label = args.stringArg("label").trim()
                val name = if (label.isNotEmpty()) "${g.third} ($label)" else g.third
                Quakes.setPlaces(c, Quakes.places(c).filterNot { it.name == name } + WatchedPlace(name, g.first, g.second))
                "J’ajoute $name aux lieux surveillés pour les séismes." + if (!Quakes.enabled(c)) " (La surveillance n’est pas encore activée : dites « préviens-moi des séismes ».)" else ""
            }
            else -> {
                val list = Quakes.recent(ctx)
                if (list.isEmpty()) return "Liste des séismes indisponible pour le moment."
                val fix = (com.jarvis.android.location.locate(c) as? com.jarvis.android.location.LocationOutcome.Found)?.fix
                ctx.videoPanel.show(VideoPanel.Video(title = "Séismes", sky = SkyModes.QUAKES))
                val day = list.filter { now - it.timeMs < 24 * 3_600_000L }
                val strongest = day.sortedByDescending { it.mag }.take(3)
                val nearest = fix?.let { f -> list.minByOrNull { distanceKm(f.latitude, f.longitude, it.lat, it.lon) } }
                "Séismes (USGS), ${day.size} de magnitude 2,5 ou plus en 24 h dans le monde, affichés sur la carte. Les plus forts : " +
                    strongest.joinToString(" ; ") { quakeWords(it, now) } + "." +
                    (nearest?.let { " Le plus proche de vous cette semaine : ${quakeWords(it, now, fix.latitude, fix.longitude)}." } ?: "") + " Résumez en deux phrases."
            }
        }
    }
}
