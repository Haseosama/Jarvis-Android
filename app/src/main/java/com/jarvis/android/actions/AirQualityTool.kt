package com.jarvis.android.actions

import com.jarvis.android.JarvisContainer
import com.jarvis.android.weather.isHereRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.io.IOException
import java.util.Locale

/*
 * Air quality and pollen — not a Mark-LIII port. Same shape as weather_report: a city name is
 * geocoded through the exact same Open-Meteo geocoding endpoint (parseWeatherPlace, shared with
 * WeatherTool), "ici"/no city uses the phone's position through the same weather/DeviceLocation.kt
 * path. The data itself is Open-Meteo's free, keyless Air Quality API — pollen figures are Europe-only
 * (the API returns them null elsewhere, which this tool simply omits rather than reporting a false zero).
 */

/** The European Air Quality Index's own bands (health-relevant thresholds, not something this app invented). */
internal fun describeEuropeanAqi(aqi: Int): String = when {
    aqi <= 20 -> "bon"
    aqi <= 40 -> "moyen"
    aqi <= 60 -> "dégradé"
    aqi <= 80 -> "mauvais"
    aqi <= 100 -> "très mauvais"
    else -> "extrêmement mauvais"
}

internal fun formatAirQuality(body: String, label: String): String {
    val root = Json.parseToJsonElement(body) as? JsonObject ?: error("Invalid air quality data")
    require(root["error"]?.toString() != "true")
    val current = root["current"] as? JsonObject ?: error("Missing current air quality")
    fun num(key: String) = (current[key] as? JsonPrimitive)?.doubleOrNull // JSON null is a JsonPrimitive whose doubleOrNull is null
    val aqi = (current["european_aqi"] as? JsonPrimitive)?.intOrNull ?: num("european_aqi")?.toInt() ?: error("Missing AQI")
    val pm25 = num("pm2_5")
    val pm10 = num("pm10")
    // each pollen with its level in words; those under one grain are left out, a missing one (outside Europe) too
    val measured = com.jarvis.android.air.POLLENS.mapNotNull { p -> num(p.key)?.let { p to it } }
    val pollen = measured.filter { it.second >= 1 }.sortedByDescending { com.jarvis.android.air.pollenLevel(it.first, it.second) }
        .map { (p, v) -> "${p.name} ${fmt(v)} (${com.jarvis.android.air.levelWords(com.jarvis.android.air.pollenLevel(p, v))})" }
    val particles = listOfNotNull(pm25?.let { "PM2.5 ${fmt(it)} µg/m³" }, pm10?.let { "PM10 ${fmt(it)} µg/m³" })
    return buildString {
        append("Qualité de l'air à $label : indice européen $aqi (${describeEuropeanAqi(aqi)})")
        if (particles.isNotEmpty()) append(", ").append(particles.joinToString(", "))
        append(".")
        if (pollen.isNotEmpty()) append(" Pollens (grains/m³) : ").append(pollen.joinToString(", ")).append(".")
        else if (measured.isNotEmpty()) append(" Pas de pollen notable.")
    }
}

private fun fmt(v: Double) = String.format(Locale.FRANCE, "%.1f", v)

object AirQualityTool : Tool {
    override val name = "air_quality"
    override val description =
        "Qualité de l'air et pollens (indice européen, particules PM2.5/PM10, pollens d'aulne, bouleau, olivier, graminées, armoise et " +
            "ambroisie avec leur niveau, en Europe). Avec une ville, celle-ci ; SANS ville (ou « ici », « chez moi »), la position du " +
            "téléphone. action : « now » (défaut, maintenant), « forecast » (aujourd'hui et demain, au pire de la journée), « map » (la " +
            "carte de l'air autour, à la place du visage), « alert_on » (threshold : indice européen au-delà duquel prévenir, 60 par " +
            "défaut ; pollens : ceux qui gênent l'utilisateur, par exemple « graminées, bouleau », tous par défaut) / « alert_off » : une " +
            "notification dans la journée quand l'air est mauvais ou un pollen élevé."
    override val parameters = objectSchema {
        string("city", "Nom de la ville ; vide ou « ici » pour utiliser la position de l'utilisateur.")
        string("action", "now, forecast, map, alert_on ou alert_off.")
        string("threshold", "Pour alert_on : l'indice européen au-delà duquel prévenir (60 par défaut : « mauvais » au-delà).")
        string("pollens", "Pour alert_on : les pollens qui gênent l'utilisateur (aulne, bouleau, olivier, graminées, armoise, ambroisie), séparés par des virgules.")
    }

    override suspend fun run(args: JsonObject, ctx: JarvisContainer): String = withContext(Dispatchers.IO) {
        when (args.stringArg("action").trim().lowercase()) {
            "alert_off" -> { com.jarvis.android.air.AirWatch.set(ctx.appContext, false); return@withContext "Je ne surveille plus l'air et les pollens." }
            "alert_on" -> {
                val threshold = args.stringArg("threshold").trim().toIntOrNull()?.coerceIn(10, 150) ?: 60
                val wanted = args.stringArg("pollens").split(',', ';', '/').map { com.jarvis.android.offline.normalize(it) }.filter { it.isNotEmpty() }
                val keys = com.jarvis.android.air.POLLENS.filter { p -> wanted.any { w -> com.jarvis.android.offline.normalize(p.name).startsWith(w.take(5)) } }.map { it.key }.toSet()
                com.jarvis.android.air.AirWatch.set(ctx.appContext, true, threshold, keys)
                val names = if (keys.isEmpty()) "tous les pollens" else com.jarvis.android.air.POLLENS.filter { it.key in keys }.joinToString(", ") { it.name }
                return@withContext "Je surveille l'air (au-delà de l'indice $threshold) et les pollens ($names, quand leur niveau est élevé) : une notification " +
                    "dans la journée, une fois par jour pour la même gêne."
            }
            "forecast", "map" -> return@withContext forecastOrMap(ctx, args)
        }
        val rawCity = args.utilityString("city")
        if (isHereRequest(rawCity)) return@withContext airQualityHere(ctx)
        val city = normalizedUtilityQuery(rawCity, 200)
            ?: return@withContext "Indiquez une ville de 200 caractères maximum, ou demandez la qualité de l'air ici."
        try {
            val geoUrl = "https://geocoding-api.open-meteo.com/v1/search".toHttpUrl().newBuilder()
                .addQueryParameter("count", "10").addQueryParameter("language", "fr")
                .addQueryParameter("name", city).build()
            val geoBody = ctx.http.newCall(Request.Builder().url(geoUrl).build()).execute().use { response ->
                if (!response.isSuccessful) return@withContext utilityHttpError("Localisation", response.code)
                response.body?.string().orEmpty()
            }
            val place = parseWeatherPlace(geoBody, phoneCountry(ctx))
                ?: return@withContext "Aucune ville trouvée pour « $city ». Précisez son nom."
            val aqUrl = airQualityUrl(place.latitude, place.longitude)
            val aqBody = ctx.http.newCall(Request.Builder().url(aqUrl).build()).execute().use { response ->
                if (!response.isSuccessful) return@withContext utilityHttpError("Qualité de l'air", response.code)
                response.body?.string().orEmpty()
            }
            formatAirQuality(aqBody, place.label)
        } catch (e: CancellationException) {
            throw e
        } catch (_: IOException) {
            "Qualité de l'air indisponible : connexion impossible ou délai dépassé. Réessayez plus tard."
        } catch (_: Exception) {
            "Qualité de l'air indisponible : données du service incomplètes ou invalides."
        }
    }
}

/** The next two days, or the map, for a city or where the phone is. */
private suspend fun forecastOrMap(ctx: JarvisContainer, args: JsonObject): String {
    val rawCity = args.utilityString("city")
    val (lat, lon, label) = if (isHereRequest(rawCity)) {
        val f = com.jarvis.android.weather.locate(ctx.appContext) as? com.jarvis.android.weather.LocationOutcome.Found
            ?: return "Je n'ai pas votre position : dites le nom d'une ville."
        Triple(f.fix.latitude, f.fix.longitude, com.jarvis.android.weather.positionLabel(f.place))
    } else {
        val g = com.jarvis.android.driving.RouteWeather.geocode(ctx, rawCity.orEmpty().trim()) ?: return "Aucune ville trouvée pour « $rawCity »."
        g
    }
    if (args.stringArg("action").trim().lowercase() == "map") {
        com.jarvis.android.air.AirMapCenter.at = lat to lon
        ctx.videoPanel.show(com.jarvis.android.video.VideoPanel.Video(title = "Qualité de l'air", sky = com.jarvis.android.space.SkyModes.AIR))
        return "La carte de la qualité de l'air autour de $label s'affiche à la place du visage (une case tous les 35 km environ, aux couleurs de " +
            "l'indice européen). Dites-le en une phrase."
    }
    val body = com.jarvis.android.air.AirData.forecast(ctx, lat, lon) ?: return "Prévision de la qualité de l'air indisponible pour le moment."
    return "Qualité de l'air à $label (au pire entre 7 h et 21 h). " + com.jarvis.android.air.airForecastWords(body, java.time.LocalDate.now())
}

internal fun airQualityUrl(latitude: Double, longitude: Double): String =
    "https://air-quality-api.open-meteo.com/v1/air-quality".toHttpUrl().newBuilder()
        .addQueryParameter("latitude", latitude.toString())
        .addQueryParameter("longitude", longitude.toString())
        .addQueryParameter("current", "european_aqi,pm2_5,pm10," + com.jarvis.android.air.POLLENS.joinToString(",") { it.key })
        .build().toString()

/** Air quality at the phone's position, when the user has allowed location. */
private suspend fun airQualityHere(ctx: JarvisContainer): String {
    val fix = when (val outcome = com.jarvis.android.weather.locate(ctx.appContext)) {
        is com.jarvis.android.weather.LocationOutcome.Found -> outcome
        com.jarvis.android.weather.LocationOutcome.NoPermission ->
            return "Je n'ai pas accès à la position. L'utilisateur peut l'autoriser dans Paramètres > Position (météo), ou dire le nom d'une ville."
        com.jarvis.android.weather.LocationOutcome.ServicesOff ->
            return "La localisation du téléphone est désactivée. L'utilisateur doit l'activer, ou dire le nom d'une ville."
        com.jarvis.android.weather.LocationOutcome.Unavailable ->
            return "Position introuvable pour le moment (souvent : l'appli n'est pas au premier plan). Demandez le nom d'une ville, ou réessayez avec Jarvis ouvert."
    }
    return try {
        val body = withContext(Dispatchers.IO) {
            ctx.http.newCall(Request.Builder().url(airQualityUrl(fix.fix.latitude, fix.fix.longitude)).build()).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                response.body?.string().orEmpty()
            }
        } ?: return "Qualité de l'air indisponible pour le moment."
        formatAirQuality(body, com.jarvis.android.weather.positionLabel(fix.place))
    } catch (e: CancellationException) {
        throw e
    } catch (_: IOException) {
        "Qualité de l'air indisponible : connexion impossible ou délai dépassé."
    } catch (_: Exception) {
        "Qualité de l'air indisponible : données du service incomplètes ou invalides."
    }
}
