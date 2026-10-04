package com.jarvis.android.weather

import com.jarvis.android.JarvisContainer
import com.jarvis.android.tool.Tool
import com.jarvis.android.tool.objectSchema
import com.jarvis.android.tool.stringArg
import com.jarvis.android.space.Observer
import com.jarvis.android.space.lookAtSky
import com.jarvis.android.space.sunPosition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import okhttp3.Request
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import com.jarvis.android.location.LocationOutcome
import com.jarvis.android.location.locate
import com.jarvis.android.location.positionLabel

/*
 * The Sun of the day: the UV index (Open-Meteo, hour by hour) with what protection it asks for (the WHO's scale), and the times of the
 * light computed here from the Sun's height: sunrise and sunset, the true noon, the golden hours (the Sun between 6° above and 4° under
 * the horizon, the warm soft light photographers look for) and the blue hours (4° to 6° under).
 */

/** The WHO's words for a UV index, and what to do. */
internal fun uvWords(uv: Double): String = when {
    uv < 3 -> "faible : pas de protection nécessaire"
    uv < 6 -> "modéré : lunettes de soleil, crème si vous restez dehors longtemps"
    uv < 8 -> "élevé : crème indice 30 ou plus, chapeau, ombre entre midi et 16 h"
    uv < 11 -> "très élevé : évitez le soleil de la mi-journée, crème 50, chapeau, lunettes"
    else -> "extrême : restez à l’ombre en milieu de journée, protection maximale"
}

/** The day's light: sunrise and sunset, true noon, golden and blue hours (morning and evening), as times (UTC ms), null when they do not happen. */
internal data class DayLight(
    val sunrise: Long?, val sunset: Long?, val noon: Long, val noonAltitude: Double,
    val morningBlue: Pair<Long, Long>?, val morningGolden: Pair<Long, Long>?, val eveningGolden: Pair<Long, Long>?, val eveningBlue: Pair<Long, Long>?,
)

/** The day's light at [o], minute by minute from local midnight (the Sun's height with its apparent radius and refraction at the horizon: −0.833°). */
internal fun dayLight(o: Observer, day: LocalDate, zone: ZoneId): DayLight {
    val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
    val alt = DoubleArray(24 * 60 + 1) { i -> val t = start + i * 60_000L; lookAtSky(o, sunPosition(t), t).elevationDeg }
    fun t(i: Int) = start + i * 60_000L
    fun cross(level: Double, rising: Boolean): Int? = (1 until alt.size).firstOrNull { i -> if (rising) alt[i - 1] < level && alt[i] >= level else alt[i - 1] >= level && alt[i] < level }
    val noon = alt.indices.maxBy { alt[it] }
    fun span(a: Int?, b: Int?) = if (a != null && b != null) t(minOf(a, b)) to t(maxOf(a, b)) else null
    return DayLight(
        cross(-0.833, true)?.let(::t), cross(-0.833, false)?.let(::t), t(noon), alt[noon],
        span(cross(-6.0, true), cross(-4.0, true)), span(cross(-4.0, true), cross(6.0, true)),
        span(cross(6.0, false), cross(-4.0, false)), span(cross(-4.0, false), cross(-6.0, false)),
    )
}

/** The day's UV from Open-Meteo's hours (UTC): its peak and when. */
internal fun uvPeak(json: String, day: LocalDate, zone: ZoneId): Pair<Double, Long>? {
    val h = (Json.parseToJsonElement(json) as JsonObject)["hourly"] as? JsonObject ?: return null
    val times = (h["time"] as? JsonArray).orEmpty().map { LocalDateTime.parse((it as JsonPrimitive).content).toInstant(ZoneOffset.UTC).toEpochMilli() }
    val uv = (h["uv_index"] as? JsonArray).orEmpty().map { (it as? JsonPrimitive)?.doubleOrNull }
    return times.indices.filter { Instant.ofEpochMilli(times[it]).atZone(zone).toLocalDate() == day }.mapNotNull { i -> uv.getOrNull(i)?.let { it to times[i] } }.maxByOrNull { it.first }
}

internal fun dayLightWords(d: DayLight, zone: ZoneId): String {
    fun hm(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toLocalTime().let { "%02dh%02d".format(it.hour, it.minute) }
    fun span(p: Pair<Long, Long>?) = p?.let { "${hm(it.first)}–${hm(it.second)}" }
    val parts = ArrayList<String>()
    if (d.sunrise != null && d.sunset != null) {
        val len = (d.sunset - d.sunrise) / 60_000
        parts += "lever ${hm(d.sunrise)}, coucher ${hm(d.sunset)} (${len / 60} h ${"%02d".format(len % 60)} de jour)"
    } else parts += if (d.noonAltitude > 0) "le Soleil ne se couche pas" else "le Soleil ne se lève pas"
    parts += "Soleil au plus haut à ${hm(d.noon)} (${d.noonAltitude.toInt()}°)"
    listOfNotNull(span(d.morningGolden)?.let { "heure dorée du matin $it" }, span(d.eveningGolden)?.let { "heure dorée du soir $it" }).takeIf { it.isNotEmpty() }?.let { parts += it.joinToString(", ") }
    listOfNotNull(span(d.morningBlue)?.let { "heure bleue $it le matin" }, span(d.eveningBlue)?.let { "$it le soir" }).takeIf { it.isNotEmpty() }?.let { parts += it.joinToString(", ") }
    return parts.joinToString(" ; ")
}

internal object SunUv {
    suspend fun uvJson(ctx: JarvisContainer, lat: Double, lon: Double): String? = withContext(Dispatchers.IO) {
        try {
            ctx.http.newCall(Request.Builder().url("https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f&hourly=uv_index&forecast_days=2&timezone=GMT".format(Locale.US, lat, lon)).build())
                .execute().use { if (it.isSuccessful) it.body?.string() else null }
        } catch (_: Exception) {
            null
        }
    }

    /** One sentence for the morning briefing when the UV will be high today. */
    suspend fun briefingLine(ctx: JarvisContainer, lat: Double, lon: Double): String? {
        val zone = ZoneId.systemDefault()
        val (uv, at) = uvJson(ctx, lat, lon)?.let { uvPeak(it, LocalDate.now(zone), zone) } ?: return null
        if (uv < 6) return null
        val h = Instant.ofEpochMilli(at).atZone(zone).hour
        return "UV %s aujourd’hui (indice %.0f vers %d h) : %s".format(Locale.FRANCE, if (uv < 8) "élevés" else "très élevés", uv, h, uvWords(uv).substringAfter(": "))
    }
}

/** "Faut-il de la crème solaire ?", "à quelle heure le coucher du soleil ?", "c'est quand l'heure dorée ?" */
object SunUvTool : Tool {
    override val name = "sun_uv"
    override val description =
        "Le Soleil du jour là où est l’utilisateur : indice UV (maximum et heure, et la protection à prévoir), lever et coucher, durée du " +
            "jour, Soleil au plus haut, heures dorées (lumière chaude pour les photos) et heures bleues. day : « today » (défaut) ou « tomorrow »."
    override val parameters = objectSchema { string("day", "today (défaut) ou tomorrow.") }

    override suspend fun run(args: JsonObject, ctx: JarvisContainer): String {
        val f = locate(ctx.appContext) as? LocationOutcome.Found ?: return "Je n’ai pas votre position : autorisez la position pour Jarvis."
        val zone = ZoneId.systemDefault()
        val dayWord = args.stringArg("day").lowercase()
        val tomorrow = "tom" in dayWord || "demain" in dayWord
        val day = LocalDate.now(zone).plusDays(if (tomorrow) 1 else 0)
        val light = withContext(Dispatchers.Default) { dayLight(Observer(f.fix.latitude, f.fix.longitude), day, zone) }
        val uv = SunUv.uvJson(ctx, f.fix.latitude, f.fix.longitude)?.let { uvPeak(it, day, zone) }
        val uvText = uv?.let { (v, at) -> "UV maximum %.0f vers %d h, %s".format(Locale.FRANCE, v, Instant.ofEpochMilli(at).atZone(zone).hour, uvWords(v)) } ?: "indice UV indisponible"
        return (if (tomorrow) "Demain" else "Aujourd’hui") + " à ${positionLabel(f.place)} : $uvText ; " + dayLightWords(light, zone) + "."
    }
}
