package com.jarvis.android.space

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.android.JarvisApp
import com.jarvis.android.JarvisContainer
import com.jarvis.android.tool.Tool
import com.jarvis.android.tool.objectSchema
import com.jarvis.android.video.VideoPanel
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
import java.time.format.DateTimeFormatter
import java.util.Locale
import com.jarvis.android.video.SkyModes

/*
 * Is tonight good to look at the sky or photograph it? Open-Meteo's hourly clouds (low, middle, high), humidity, dew point and wind,
 * with how dark it is (the Sun's depth under the horizon) and the Moon (up or down, how lit), computed here: a score an hour, the best
 * stretch of each night, the dew that fogs lenses. For three nights, told and drawn.
 */

/** One hour of the night: the weather, the Sun and the Moon, and what it gives (0 useless … 100 perfect). */
internal data class ObsHour(
    val timeMs: Long, val clouds: Int, val low: Int, val mid: Int, val high: Int, val humidity: Int, val tempC: Double, val dewC: Double,
    val windKmh: Double, val sunAlt: Double, val moonAlt: Double, val moonLit: Double, val score: Int,
)

/** The score of an hour: clouds first, then darkness, the Moon's light, damp and wind. */
internal fun observingScore(clouds: Int, humidity: Int, windKmh: Double, sunAlt: Double, moonAlt: Double, moonLit: Double): Int {
    if (sunAlt > -6) return 0
    var s = 100.0 - clouds
    s *= when { sunAlt < -18 -> 1.0; sunAlt < -12 -> 0.8; else -> 0.5 }
    if (moonAlt > 0) s -= 30 * moonLit
    if (humidity > 90) s -= 15 else if (humidity > 80) s -= 8
    if (windKmh > 40) s -= 20 else if (windKmh > 25) s -= 10
    return s.toInt().coerceIn(0, 100)
}

/** Open-Meteo's hours (UTC) with the Sun and the Moon from [o]: only the dark ones (the Sun 6° or more under the horizon). */
internal fun parseObsHours(json: String, o: Observer): List<ObsHour> {
    val h = (Json.parseToJsonElement(json) as JsonObject)["hourly"] as? JsonObject ?: return emptyList()
    fun arr(k: String) = (h[k] as? JsonArray).orEmpty()
    fun d(k: String, i: Int) = (arr(k).getOrNull(i) as? JsonPrimitive)?.doubleOrNull
    return arr("time").mapIndexedNotNull { i, t ->
        val ms = LocalDateTime.parse((t as JsonPrimitive).content).toInstant(ZoneOffset.UTC).toEpochMilli()
        val sun = lookAtSky(o, sunPosition(ms), ms).elevationDeg
        if (sun > -6) return@mapIndexedNotNull null
        val moon = lookAtSky(o, moonVector(ms), ms).elevationDeg
        val lit = moonPhase(ms).lit
        val clouds = d("cloud_cover", i)?.toInt() ?: return@mapIndexedNotNull null
        val hum = d("relative_humidity_2m", i)?.toInt() ?: 0
        val wind = d("wind_speed_10m", i) ?: 0.0
        ObsHour(
            ms, clouds, d("cloud_cover_low", i)?.toInt() ?: 0, d("cloud_cover_mid", i)?.toInt() ?: 0, d("cloud_cover_high", i)?.toInt() ?: 0, hum,
            d("temperature_2m", i) ?: 0.0, d("dew_point_2m", i) ?: 0.0, wind, sun, moon, lit, observingScore(clouds, hum, wind, sun, moon, lit),
        )
    }
}

/** A night: its evening's date, its dark hours, the best stretch (hours in a row at the best score, within 10 points). */
internal data class ObsNight(val evening: LocalDate, val hours: List<ObsHour>) {
    val best: List<ObsHour> get() {
        val top = hours.maxOfOrNull { it.score } ?: return emptyList()
        if (top < 30) return emptyList()
        var bestRun = emptyList<ObsHour>()
        var run = ArrayList<ObsHour>()
        for (h in hours) {
            if (h.score >= top - 10 && h.score >= 30) run.add(h) else { if (run.size > bestRun.size) bestRun = run; run = ArrayList() }
        }
        if (run.size > bestRun.size) bestRun = run
        return bestRun
    }
}

internal fun obsNights(hours: List<ObsHour>, zone: ZoneId): List<ObsNight> =
    hours.groupBy { Instant.ofEpochMilli(it.timeMs - 12 * 3_600_000L).atZone(zone).toLocalDate() }.map { (d, h) -> ObsNight(d, h.sortedBy { it.timeMs }) }.sortedBy { it.evening }

/** A night in words: its best stretch and why, or why not. */
internal fun obsNightWords(n: ObsNight, zone: ZoneId, today: LocalDate): String {
    fun hm(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).hour.toString() + "h"
    val name = when (n.evening) { today -> "Cette nuit"; today.plusDays(1) -> "Demain soir"; else -> "La nuit du " + n.evening.format(DateTimeFormatter.ofPattern("EEEE d", Locale.FRANCE)) }
    val b = n.best
    if (b.isEmpty()) {
        val clouds = n.hours.map { it.clouds }.average().toInt()
        return "$name : pas bon pour observer (${clouds} % de nuages en moyenne)"
    }
    val score = b.maxOf { it.score }
    val quality = when { score >= 80 -> "excellent"; score >= 60 -> "bon"; score >= 45 -> "moyen"; else -> "médiocre" }
    val clouds = b.map { it.clouds }.average().toInt()
    val moonUp = b.count { it.moonAlt > 0 }
    val lit = (b.first().moonLit * 100).toInt()
    val moon = when { moonUp == 0 -> "Lune couchée"; lit < 25 -> "Lune fine, peu gênante"; moonUp == b.size -> "Lune levée, éclairée à $lit %"; else -> "Lune levée une partie du temps (éclairée à $lit %)" }
    val dew = b.any { it.tempC - it.dewC < 2.5 || it.humidity > 90 }
    val cold = b.minOf { it.tempC }
    return "$name : $quality de ${hm(b.first().timeMs)} à ${hm(b.last().timeMs + 3_600_000L)} (${clouds} % de nuages, $moon, ${cold.toInt()} °C au plus froid" +
        (if (b.maxOf { it.windKmh } > 25) ", du vent" else "") + ")" + (if (dew) " ; buée probable sur les optiques" else "")
}

internal object Observing {
    suspend fun hours(ctx: JarvisContainer, o: Observer): List<ObsHour> = withContext(Dispatchers.IO) {
        val url = "https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f&hourly=cloud_cover,cloud_cover_low,cloud_cover_mid,cloud_cover_high,relative_humidity_2m,dew_point_2m,temperature_2m,wind_speed_10m&forecast_days=4&timezone=GMT".format(Locale.US, o.latDeg, o.lonDeg)
        try {
            ctx.http.newCall(Request.Builder().url(url).build()).execute().use { r -> if (r.isSuccessful) parseObsHours(r.body?.string().orEmpty(), o) else emptyList() }
                .filter { it.timeMs > System.currentTimeMillis() - 3_600_000L }
        } catch (_: Exception) {
            emptyList()
        }
    }
}

private fun scoreColor(s: Int) = when { s >= 70 -> Color(0xFF4DFFB8); s >= 45 -> Color(0xFFFFD54F); s > 0 -> Color(0xFFFF9F43); else -> Color(0xFF3E5C7A) }

/** The nights to come, hour by hour: a bar for the clouds, a colour for the score, the Moon when it is up. */
@Composable
internal fun ObservingView(big: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val container = remember(context) { (context.applicationContext as JarvisApp).container }
    val zone = ZoneId.systemDefault()
    val nights by produceState<List<ObsNight>?>(null) {
        val f = com.jarvis.android.location.locate(container.appContext) as? com.jarvis.android.location.LocationOutcome.Found
        value = if (f == null) emptyList() else obsNights(Observing.hours(container, Observer(f.fix.latitude, f.fix.longitude)), zone).take(3)
    }
    val text = Color(0xFFDCEBFA)
    val dim = Color(0xFF8FA9C4)
    Column(modifier.fillMaxSize().background(Color(0xFF050B14)).verticalScroll(rememberScrollState()).padding(10.dp)) {
        val list = nights
        when {
            list == null -> Text("Chargement de la météo de la nuit…", color = dim)
            list.isEmpty() -> Text("Prévision indisponible (position ou réseau).", color = dim)
            else -> list.forEach { n ->
                Text(obsNightWords(n, zone, LocalDate.now(zone)), color = text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                Canvas(Modifier.fillMaxWidth().height(if (big) 110.dp else 80.dp).padding(vertical = 4.dp)) {
                    val w = size.width / n.hours.size.coerceAtLeast(1)
                    val chart = size.height - 16.dp.toPx()
                    n.hours.forEachIndexed { i, h ->
                        val x = i * w
                        drawRect(scoreColor(h.score).copy(alpha = 0.35f), Offset(x + 1, 0f), Size(w - 2, chart))
                        val ch = chart * h.clouds / 100f
                        drawRect(Color(0xFF8FA9C4), Offset(x + w * 0.25f, chart - ch), Size(w * 0.5f, ch))
                        if (h.moonAlt > 0) drawCircle(Color(0xFFE8EEF5).copy(alpha = 0.3f + 0.7f * h.moonLit.toFloat()), 3.dp.toPx(), Offset(x + w / 2, 5.dp.toPx()))
                        if (i % 2 == 0) drawContext.canvas.nativeCanvas.drawText(
                            Instant.ofEpochMilli(h.timeMs).atZone(zone).hour.toString() + "h", x + 2, size.height - 2,
                            android.graphics.Paint().apply { color = android.graphics.Color.rgb(143, 169, 196); textSize = 10.sp.toPx(); isAntiAlias = true },
                        )
                    }
                }
            }
        }
        Text("Fond : vert bon, jaune moyen, orange médiocre · barre : nuages · point : la Lune levée · Open-Meteo", color = dim, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
    }
}

/** "Est-ce que ce soir est bon pour observer le ciel ?" */
object ObservingTool : Tool {
    override val name = "observing_weather"
    override val description =
        "La météo du ciel pour observer ou photographier les étoiles, heure par heure pour les 3 prochaines nuits : nuages (bas, moyens, " +
            "hauts), obscurité (crépuscule astronomique), la Lune levée ou non et sa lumière, humidité et buée, vent, froid ; la meilleure " +
            "tranche horaire de chaque nuit avec une note, et un graphique à la place du visage. Pour « ce soir est-il bon pour observer ? », " +
            "« quelle nuit cette semaine pour photographier la Voie lactée ? »."
    override val parameters = objectSchema { string("action", "show (par défaut).") }

    override suspend fun run(args: JsonObject, ctx: JarvisContainer): String {
        val f = com.jarvis.android.location.locate(ctx.appContext) as? com.jarvis.android.location.LocationOutcome.Found
            ?: return "Je n’ai pas votre position : autorisez la position pour Jarvis."
        val zone = ZoneId.systemDefault()
        val nights = obsNights(Observing.hours(ctx, Observer(f.fix.latitude, f.fix.longitude)), zone).take(3)
        if (nights.isEmpty()) return "Prévision de la nuit indisponible pour le moment."
        ctx.videoPanel.show(VideoPanel.Video(title = "Météo du ciel", sky = SkyModes.OBSERVE))
        return nights.joinToString(". ", postfix = ".") { obsNightWords(it, zone, LocalDate.now(zone)) } + " (Le graphique s’affiche à la place du visage.) Dites-le en deux ou trois phrases."
    }
}
