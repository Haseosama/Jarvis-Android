package com.jarvis.android.weather

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
import com.jarvis.android.text.normalize
import com.jarvis.android.video.VideoPanel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.Request
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.TimeUnit
import com.jarvis.android.location.LocationOutcome
import com.jarvis.android.location.locate

/*
 * The official warnings in France: Météo-France's weather vigilance (through MeteoAlarm, Europe's network of weather services: free,
 * no key) for the user's département, and the flood vigilance of Vigicrues (the rivers' sections and their colour), near the user.
 * Told on asking, drawn on the map, and a watch that says when it turns orange or red (or yellow, if asked).
 */

/** A weather warning: its words ("Vigilance orange orages"), its level (2 yellow, 3 orange, 4 red), the départements, when, what to do. */
internal data class Warning(val event: String, val level: Int, val areas: List<String>, val onsetMs: Long, val expiresMs: Long, val instruction: String)

/** MeteoAlarm's French warnings (the French text of each; the level from awareness_level, or the colour in the words). */
internal fun parseMeteoAlarm(json: String): List<Warning> = try {
    ((Json.parseToJsonElement(json) as JsonObject)["warnings"] as JsonArray).flatMap { w ->
        val alert = (w as? JsonObject)?.get("alert") as? JsonObject ?: return@flatMap emptyList()
        (alert["info"] as? JsonArray).orEmpty().mapNotNull { i ->
            val info = i as? JsonObject ?: return@mapNotNull null
            if ((info["language"] as? JsonPrimitive)?.contentOrNull?.startsWith("fr") != true) return@mapNotNull null
            val event = (info["event"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val params = (info["parameter"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            val levelNumber = params.firstOrNull { (it["valueName"] as? JsonPrimitive)?.contentOrNull == "awareness_level" }?.let { (it["value"] as? JsonPrimitive)?.contentOrNull?.substringBefore(';')?.trim()?.toIntOrNull() }
            val level = levelNumber ?: when { "rouge" in event -> 4; "orange" in event -> 3; "jaune" in event -> 2; else -> 1 }
            fun time(k: String) = (info[k] as? JsonPrimitive)?.contentOrNull?.let { try { OffsetDateTime.parse(it).toInstant().toEpochMilli() } catch (_: Exception) { null } }
            val areas = (info["area"] as? JsonArray).orEmpty().mapNotNull { ((it as? JsonObject)?.get("areaDesc") as? JsonPrimitive)?.contentOrNull }
            Warning(event, level, areas, time("onset") ?: time("effective") ?: 0L, time("expires") ?: Long.MAX_VALUE, (info["instruction"] as? JsonPrimitive)?.contentOrNull.orEmpty())
        }
    }
} catch (_: Exception) {
    emptyList()
}

/** The warnings in force for a département (by its name), the highest first, one per kind. */
internal fun warningsFor(all: List<Warning>, department: String, now: Long): List<Warning> {
    val d = normalize(department)
    return all.filter { w -> w.level >= 2 && w.expiresMs > now && w.areas.any { normalize(it) == d } }
        .sortedByDescending { it.level }.distinctBy { it.event.substringAfter(" ").substringAfter(" ") }
}

/** A river's section and its flood colour (2 yellow, 3 orange, 4 red), with its line. */
internal data class FloodSection(val name: String, val level: Int, val line: List<Pair<Double, Double>>)

/** Vigicrues' map of the sections (GeoJSON): those with a colour above green, their lines (latitude, longitude). */
internal fun parseVigicrues(json: String, minLevel: Int = 1): List<FloodSection> = try {
    ((Json.parseToJsonElement(json) as JsonObject)["features"] as JsonArray).mapNotNull { f ->
        val o = f as? JsonObject ?: return@mapNotNull null
        val p = o["properties"] as? JsonObject ?: return@mapNotNull null
        val level = (p["NivInfViCr"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
        if (level < minLevel) return@mapNotNull null
        val g = o["geometry"] as? JsonObject ?: return@mapNotNull null
        val coords = g["coordinates"] as? JsonArray ?: return@mapNotNull null
        val lines = if ((g["type"] as? JsonPrimitive)?.contentOrNull == "MultiLineString") coords.mapNotNull { it as? JsonArray } else listOf(coords)
        val pts = lines.flatMap { l -> l.mapNotNull { c -> (c as? JsonArray)?.let { a -> ((a.getOrNull(1) as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null) to ((a.getOrNull(0) as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null) } } }
        FloodSection((p["lbentcru"] as? JsonPrimitive)?.contentOrNull.orEmpty(), level, pts)
    }
} catch (_: Exception) {
    emptyList()
}

internal fun levelColour(level: Int) = when (level) { 2 -> "jaune"; 3 -> "orange"; 4 -> "rouge"; else -> "verte" }

/** The flooding sections within [km] of a place, nearest first, with how far. */
internal fun floodsNear(sections: List<FloodSection>, lat: Double, lon: Double, km: Double = 30.0): List<Pair<FloodSection, Double>> = sections.filter { it.level >= 2 }
    .mapNotNull { s -> s.line.minOfOrNull { distanceKm(lat, lon, it.first, it.second) }?.takeIf { it <= km }?.let { s to it } }.sortedBy { it.second }

internal object Vigilance {
    @Volatile var lastFloods: List<FloodSection> = emptyList()
    @Volatile var centre: Pair<Double, Double>? = null

    private fun get(ctx: JarvisContainer, url: String): String? = try {
        ctx.http.newCall(Request.Builder().url(url).header("User-Agent", "Jarvis-Android").build()).execute().use { if (it.isSuccessful) it.body?.string() else null }
    } catch (_: Exception) {
        null
    }

    suspend fun weather(ctx: JarvisContainer): List<Warning> = withContext(Dispatchers.IO) { get(ctx, "https://feeds.meteoalarm.org/api/v1/warnings/feeds-france")?.let { parseMeteoAlarm(it) }.orEmpty() }
    suspend fun floods(ctx: JarvisContainer): List<FloodSection> = withContext(Dispatchers.IO) { get(ctx, "https://www.vigicrues.gouv.fr/services/InfoVigiCru.geojson")?.let { parseVigicrues(it) }.orEmpty().also { if (it.isNotEmpty()) lastFloods = it } }

    /** The département of a place (the State's geographic API): its code and name. */
    suspend fun department(ctx: JarvisContainer, lat: Double, lon: Double): Pair<String, String>? = withContext(Dispatchers.IO) {
        get(ctx, "https://geo.api.gouv.fr/communes?lat=%.5f&lon=%.5f&fields=departement".format(Locale.US, lat, lon))?.let { body ->
            val d = ((Json.parseToJsonElement(body) as? JsonArray)?.firstOrNull() as? JsonObject)?.get("departement") as? JsonObject ?: return@let null
            ((d["code"] as? JsonPrimitive)?.contentOrNull ?: return@let null) to ((d["nom"] as? JsonPrimitive)?.contentOrNull ?: return@let null)
        }
    }

    /** What is in force for a place, in words. */
    fun words(dept: String, warnings: List<Warning>, floods: List<Pair<FloodSection, Double>>?, zone: ZoneId): String {
        fun hm(ms: Long) = java.time.Instant.ofEpochMilli(ms).atZone(zone).let { "%s %02dh".format(it.format(java.time.format.DateTimeFormatter.ofPattern("EEEE", Locale.FRANCE)), it.hour) }
        val parts = ArrayList<String>()
        parts += if (warnings.isEmpty()) "Pas de vigilance météo en cours pour $dept (vert)"
        else "$dept : " + warnings.joinToString(" ; ") { w -> w.event.replaceFirstChar { it.lowercase() } + (if (w.expiresMs < Long.MAX_VALUE) " jusqu’à ${hm(w.expiresMs)}" else "") }
        warnings.firstOrNull { it.level >= 3 }?.instruction?.takeIf { it.isNotBlank() }?.let { parts += "Conseil : " + it.substringBefore(". ").take(200) }
        // the rivers are looked at only near the user
        if (floods != null) parts += if (floods.isEmpty()) "pas de vigilance crues sur les rivières proches"
        else "crues : " + floods.take(4).joinToString(", ") { (s, km) -> "${s.name} en ${levelColour(s.level)} (à ${km.toInt()} km)" }
        return parts.joinToString(". ") { it.replaceFirstChar { c -> c.uppercase() } } + "."
    }

    private fun prefs(c: Context) = c.getSharedPreferences("vigilance", Context.MODE_PRIVATE)
    fun setWatch(c: Context, on: Boolean, minLevel: Int) {
        prefs(c).edit().putBoolean("on", on).putInt("min", minLevel).apply()
        val wm = WorkManager.getInstance(c)
        if (on) wm.enqueueUniquePeriodicWork(
            "vigilance", ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<VigilanceWorker>(1, TimeUnit.HOURS).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build(),
        ) else wm.cancelUniqueWork("vigilance")
    }

    /** One look: new warnings at the level asked for or above, each told once. */
    suspend fun check(c: Context) {
        val p = prefs(c)
        if (!p.getBoolean("on", false)) return
        val ctx = (c.applicationContext as JarvisApp).container
        val fix = (locate(c, 12 * 3_600_000L) as? LocationOutcome.Found)?.fix ?: return
        val dept = department(ctx, fix.latitude, fix.longitude) ?: return
        val min = p.getInt("min", 3)
        val now = System.currentTimeMillis()
        val warn = warningsFor(weather(ctx), dept.second, now).filter { it.level >= min }
        val fl = floodsNear(floods(ctx), fix.latitude, fix.longitude).filter { it.first.level >= min }
        val keys = warn.map { "${it.event}|${it.onsetMs}" } + fl.map { "${it.first.name}|${it.first.level}" }
        val told = p.getStringSet("told", emptySet()).orEmpty()
        val fresh = keys.filter { it !in told }
        if (fresh.isEmpty()) return
        p.edit().putStringSet("told", (told + fresh).toList().takeLast(100).toSet()).apply()
        val text = words(dept.second, warn, fl, ZoneId.systemDefault())
        val top = (warn.map { it.level } + fl.map { it.first.level }).maxOrNull() ?: 2
        com.jarvis.android.journal.Journal.alert(c, com.jarvis.android.journal.AlertKind.VIGILANCE, text)
        c.getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("jarvis_vigilance", tr("Vigilance météo et crues"), NotificationManager.IMPORTANCE_HIGH))
        try {
            NotificationManagerCompat.from(c).notify(
                7_996,
                NotificationCompat.Builder(c, "jarvis_vigilance").setSmallIcon(android.R.drawable.ic_dialog_alert).setContentTitle("Vigilance ${levelColour(top)} : ${dept.second}")
                    .setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text)).setAutoCancel(true).build(),
            )
        } catch (_: SecurityException) {
        }
    }
}

class VigilanceWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result { try { Vigilance.check(applicationContext) } catch (_: Exception) {}; return Result.success() }
}

/** "Il y a une vigilance météo ?", "est-ce que la Seine déborde ?", "préviens-moi des vigilances orange". */
object VigilanceTool : Tool {
    override val name = "vigilance"
    override val description =
        "Vigilances officielles en France : météo (Météo-France par MeteoAlarm : orages, pluie-inondation, vent, neige-verglas, canicule, " +
            "grand froid, vagues-submersion, avalanches ; jaune, orange, rouge) pour le département de l’utilisateur ou un autre (department), " +
            "et vigilance crues (Vigicrues) des rivières proches, avec les rivières en alerte dessinées sur la carte. action « now » (défaut) ; " +
            "« alert_on » (level : jaune, orange par défaut, ou rouge) / « alert_off » : une notification dès qu’une vigilance de ce niveau " +
            "commence ici (vérifié chaque heure)."
    override val parameters = objectSchema {
        string("action", "now, alert_on ou alert_off.")
        string("department", "Un autre département que celui de l’utilisateur (nom).")
        string("level", "Pour alert_on : jaune, orange (défaut) ou rouge.")
    }

    override suspend fun run(args: JsonObject, ctx: JarvisContainer): String {
        val c = ctx.appContext
        when (args.stringArg("action").trim().lowercase()) {
            "alert_on" -> {
                val lvl = args.stringArg("level").lowercase().let { if ("jaune" in it || "yellow" in it) 2 else if ("rouge" in it || "red" in it) 4 else 3 }
                Vigilance.setWatch(c, true, lvl)
                return "Je vous préviens dès qu’une vigilance ${levelColour(lvl)} (ou plus) météo ou crues commence là où vous êtes (vérifié chaque heure)."
            }
            "alert_off" -> { Vigilance.setWatch(c, false, 3); return "Je ne surveille plus les vigilances." }
        }
        val fix = (locate(c) as? LocationOutcome.Found)?.fix
        val asked = args.stringArg("department").trim()
        val dept = if (asked.isNotEmpty()) asked else fix?.let { Vigilance.department(ctx, it.latitude, it.longitude)?.second } ?: return "Je n’ai pas votre position : dites le département."
        val now = System.currentTimeMillis()
        val warn = warningsFor(Vigilance.weather(ctx), dept, now)
        val floods = Vigilance.floods(ctx)
        val near = if (fix != null && asked.isEmpty()) floodsNear(floods, fix.latitude, fix.longitude) else null
        if (fix != null) {
            Vigilance.centre = fix.latitude to fix.longitude
            ctx.videoPanel.show(VideoPanel.Video(title = "Vigilance crues", sky = com.jarvis.android.video.SkyModes.FLOODS))
        }
        return Vigilance.words(dept, warn, near, ZoneId.systemDefault()) + " (Les rivières en vigilance sont dessinées sur la carte.) Dites-le simplement."
    }
}
