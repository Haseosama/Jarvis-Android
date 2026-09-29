package com.jarvis.android.space

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.jarvis.android.JarvisApp
import com.jarvis.android.JarvisContainer
import com.jarvis.android.actions.Tool
import com.jarvis.android.actions.distanceKm
import com.jarvis.android.actions.objectSchema
import com.jarvis.android.actions.stringArg
import com.jarvis.android.i18n.tr
import com.jarvis.android.video.VideoPanel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.Request
import java.time.Instant
import java.time.ZoneId

/*
 * Following a flight: "suis le vol AF1234". Its route (adsbdb: from its flight number, IATA or ICAO), where it is now anywhere in the world
 * (adsb.lol, by callsign), shown on the world map with its route; and a watch that looks every 5 minutes (as the phone allows) and says
 * when it has landed.
 */

/** A flight's route with where its airports are. */
internal data class FlightRoute(val callsign: String, val route: Route, val originLat: Double?, val originLon: Double?, val destLat: Double?, val destLon: Double?)

/** adsbdb's answer for a flight number, with the ICAO callsign the aircraft transmits and the airports' positions. */
internal fun parseFlightRoute(json: String): FlightRoute? {
    return try {
        val r = Json.parseToJsonElement(json).jsonObject["response"] as? JsonObject ?: return null
        val f = r["flightroute"] as? JsonObject ?: return null
        fun o(k: String) = f[k] as? JsonObject
        fun d(obj: JsonObject?, k: String) = (obj?.get(k) as? JsonPrimitive)?.doubleOrNull
        val callsign = (f["callsign_icao"] as? JsonPrimitive)?.contentOrNull ?: (f["callsign"] as? JsonPrimitive)?.contentOrNull ?: return null
        FlightRoute(callsign, parseRoute(json) ?: return null, d(o("origin"), "latitude"), d(o("origin"), "longitude"), d(o("destination"), "latitude"), d(o("destination"), "longitude"))
    } catch (_: Exception) {
        null
    }
}

/** A flight number as the airline writes it ("AF 1234", "afr1234") in the form the services take. */
internal fun cleanFlight(words: String): String = words.uppercase().filter { it.isLetterOrDigit() }.take(8)

/** Estimated minutes to go: the great-circle distance left at the ground speed, plus a quarter of an hour for the descent and landing. */
internal fun minutesLeft(a: Aircraft, route: FlightRoute): Int? {
    val lat = route.destLat ?: return null
    val lon = route.destLon ?: return null
    val speed = a.speedKmh?.takeIf { it > 100 } ?: return null
    return (distanceKm(a.latitude, a.longitude, lat, lon) / speed * 60 + 15).toInt()
}

internal object Flights {
    private fun get(ctx: JarvisContainer, url: String): String? = try {
        ctx.http.newCall(Request.Builder().url(url).header("User-Agent", "Jarvis-Android").build()).execute().use { if (it.isSuccessful) it.body?.string() else null }
    } catch (_: Exception) {
        null
    }

    suspend fun route(ctx: JarvisContainer, flight: String): FlightRoute? = withContext(Dispatchers.IO) {
        get(ctx, "https://api.adsbdb.com/v0/callsign/${cleanFlight(flight)}")?.let { parseFlightRoute(it) }
    }

    /** Where the aircraft flying [callsign] is now (null: not seen, not flying yet or out of the receivers' reach). */
    suspend fun live(ctx: JarvisContainer, callsign: String): Aircraft? = withContext(Dispatchers.IO) {
        callsignForms(callsign).firstNotNullOfOrNull { cs -> get(ctx, "https://api.adsb.lol/v2/callsign/$cs")?.let { parseAdsbLol(it).firstOrNull() } }
    }
}

/** The forms a callsign may be transmitted in: as written, and with its number on three digits ("AMX45" is sent as "AMX045"). */
internal fun callsignForms(callsign: String): List<String> {
    val c = cleanFlight(callsign)
    val m = Regex("^([A-Z]{3})(\\d{1,2})([A-Z]?)$").find(c) ?: return listOf(c)
    return listOf(c, m.groupValues[1] + m.groupValues[2].padStart(3, '0') + m.groupValues[3])
}

/** A watched flight: its callsign, where it lands, and what was last seen (to tell a landing from a flight out of reach). */
@Serializable
internal data class WatchedFlight(
    val flight: String, val callsign: String, val destination: String, val destLat: Double?, val destLon: Double?,
    val until: Long, val lastSeen: Long = 0L, val lastLat: Double = 0.0, val lastLon: Double = 0.0, val lastAltFt: Double = -1.0, val missed: Int = 0,
    val scheduledMs: Long = 0L, val tookOff: Boolean = false,
)

/** "Le vol AF1234 a décollé vers 10h52, avec 17 minutes de retard." (seen in the air within 5 minutes of leaving the ground: about) */
internal fun takeoffWords(w: WatchedFlight, seenMs: Long): String {
    val hm = Instant.ofEpochMilli(seenMs).atZone(ZoneId.systemDefault()).toLocalTime().let { "%02dh%02d".format(it.hour, it.minute) }
    val late = if (w.scheduledMs > 0) ((seenMs - w.scheduledMs) / 60_000).toInt() - 10 else null
    return "Le vol ${w.flight} a décollé (vu en vol vers $hm, heure du téléphone)" + when {
        late == null -> "."
        late >= 15 -> ", avec environ $late minutes de retard."
        late <= -5 -> ", en avance."
        else -> ", à l’heure."
    }
}

/** Whether a flight has landed: on the ground, or low near its destination, or gone from view after coming down close to it. */
internal fun landed(w: WatchedFlight, now: Aircraft?): Boolean {
    val near = { lat: Double, lon: Double -> w.destLat != null && w.destLon != null && distanceKm(lat, lon, w.destLat, w.destLon) < 25 }
    if (now != null) return now.onGround && near(now.latitude, now.longitude) || (now.altitudeFt ?: 99_999.0) < 600 && near(now.latitude, now.longitude)
    return w.lastSeen > 0 && w.missed >= 2 && w.lastAltFt in 0.0..6_000.0 &&
        w.destLat != null && w.destLon != null && distanceKm(w.lastLat, w.lastLon, w.destLat, w.destLon) < 60
}

internal object FlightWatch {
    private val json = Json { ignoreUnknownKeys = true }
    private fun prefs(c: Context) = c.getSharedPreferences("flight_watch", Context.MODE_PRIVATE)
    fun get(c: Context): WatchedFlight? = try { prefs(c).getString("w", null)?.let { json.decodeFromString<WatchedFlight>(it) } } catch (_: Exception) { null }
    private fun put(c: Context, w: WatchedFlight?) = prefs(c).edit().apply { if (w == null) remove("w") else putString("w", json.encodeToString(w)) }.apply()

    fun start(c: Context, w: WatchedFlight) { put(c, w); next(c) }
    fun stop(c: Context) { put(c, null); c.getSystemService(AlarmManager::class.java).cancel(intent(c)) }

    private fun next(c: Context) {
        c.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 5 * 60_000L, intent(c))
    }

    private fun intent(c: Context) = PendingIntent.getBroadcast(c, 7_960, Intent(c, FlightWatchReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    suspend fun check(c: Context) {
        val w = get(c) ?: return
        if (System.currentTimeMillis() > w.until) { stop(c); return }
        val ctx = (c.applicationContext as JarvisApp).container
        val a = Flights.live(ctx, w.callsign)
        if (landed(w, a)) {
            val at = if (a != null) System.currentTimeMillis() else w.lastSeen
            val hm = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalTime().let { "%02dh%02d".format(it.hour, it.minute) }
            notify(c, "Le vol ${w.flight} a atterri à ${w.destination} (vers $hm).", 7_961, tr("Vol suivi"))
            stop(c)
            return
        }
        // the take-off, with its delay when the time it was due is known
        var tookOff = w.tookOff
        if (a != null && !a.onGround && (a.altitudeFt ?: 0.0) > 500 && !w.tookOff) {
            tookOff = true
            notify(c, takeoffWords(w, System.currentTimeMillis()), 7_962, tr("Décollage"))
        }
        put(c, if (a != null) w.copy(tookOff = tookOff, lastSeen = System.currentTimeMillis(), lastLat = a.latitude, lastLon = a.longitude, lastAltFt = a.altitudeFt ?: -1.0, missed = 0) else w.copy(missed = w.missed + 1))
        next(c)
    }

    private fun notify(c: Context, text: String, id: Int, title: String) {
        c.getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("jarvis_flights", tr("Vols suivis"), NotificationManager.IMPORTANCE_HIGH))
        try {
            NotificationManagerCompat.from(c).notify(id, NotificationCompat.Builder(c, "jarvis_flights").setSmallIcon(android.R.drawable.ic_menu_send)
                .setContentTitle(title).setContentText(text).setAutoCancel(true).build())
        } catch (_: SecurityException) {
        }
    }
}

class FlightWatchReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch { try { FlightWatch.check(context.applicationContext) } finally { pending.finish() } }
    }
}

/** "Suis le vol AF1234": its route, where it is, when it lands, on the world map; told when it has landed. */
object FlightTool : Tool {
    override val name = "flight"
    override val description =
        "Suivre un vol (numéro comme AF1234 ou AFR1234) : action « follow » : il s’affiche sur la carte du monde à la place du visage (sa route, " +
            "où il est, sa hauteur, sa vitesse, l’arrivée estimée) et une notification dira quand il a atterri ; « status » : où il en est, en " +
            "mots ; « stop » : ne plus le suivre. Données : adsbdb (route) et adsb.lol (position en direct, par des récepteurs bénévoles : " +
            "au-dessus des océans, il peut disparaître un moment)."
    override val parameters = objectSchema(required = listOf("action")) {
        string("action", "follow, status ou stop.")
        string("flight", "Le numéro du vol (AF1234, BA304, U24811…).")
    }

    override suspend fun run(args: JsonObject, ctx: JarvisContainer): String {
        val action = args.stringArg("action").trim().lowercase()
        if (action == "stop") { FlightWatch.stop(ctx.appContext); return "Je ne suis plus ce vol." }
        val flight = cleanFlight(args.stringArg("flight").ifBlank { FlightWatch.get(ctx.appContext)?.flight.orEmpty() })
        if (flight.length < 3) return "Donnez le numéro du vol (par exemple AF1234)."
        val route = Flights.route(ctx, flight)
        val callsign = route?.callsign ?: flight
        val a = Flights.live(ctx, callsign)
        val r = route?.route
        val where = if (r != null) "${r.airline.ifBlank { flight }} ${r.originCity.ifBlank { r.originCode }} (${r.originCode}) → ${r.destinationCity.ifBlank { r.destinationCode }} (${r.destinationCode})" else flight
        val state = when {
            a == null -> "Il n’est pas vu en vol en ce moment (pas encore parti, déjà arrivé, ou hors de portée des récepteurs)."
            a.onGround -> "Il est au sol."
            else -> "Il vole à ${(a.altitudeM ?: 0.0).toInt()} m, ${(a.speedKmh ?: 0.0).toInt()} km/h, cap ${compass(a.trackDeg ?: 0.0)}" +
                (route?.let { minutesLeft(a, it) }?.let { m -> ", arrivée estimée dans ${if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min"}" } ?: "") + "."
        }
        if (action == "status") return "Vol $where. $state"
        ctx.videoPanel.show(VideoPanel.Video(title = "Vol $flight", sky = SkyModes.FLIGHT + callsign + "|" + flight))
        FlightWatch.start(ctx.appContext, WatchedFlight(flight, callsign, r?.let { "${it.destinationCity.ifBlank { it.destinationAirport }} (${it.destinationCode})" } ?: "destination",
            route?.destLat, route?.destLon, System.currentTimeMillis() + 20 * 3_600_000L))
        return "Vol $where, affiché sur la carte du monde. $state Je préviendrai par une notification quand il aura atterri. Dites-le en une phrase courte."
    }
}
