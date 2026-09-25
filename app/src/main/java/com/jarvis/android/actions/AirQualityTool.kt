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
    val pollen = listOfNotNull(
        num("birch_pollen")?.let { "bouleau ${fmt(it)}" },
        num("grass_pollen")?.let { "graminées ${fmt(it)}" },
        num("ragweed_pollen")?.let { "ambroisie ${fmt(it)}" },
    )
    val particles = listOfNotNull(pm25?.let { "PM2.5 ${fmt(it)} µg/m³" }, pm10?.let { "PM10 ${fmt(it)} µg/m³" })
    return buildString {
        append("Qualité de l'air à $label : indice européen $aqi (${describeEuropeanAqi(aqi)})")
        if (particles.isNotEmpty()) append(", ").append(particles.joinToString(", "))
        append(".")
        if (pollen.isNotEmpty()) append(" Pollens (grains/m³) : ").append(pollen.joinToString(", ")).append(".")
    }
}

private fun fmt(v: Double) = String.format(Locale.FRANCE, "%.1f", v)

object AirQualityTool : Tool {
    override val name = "air_quality"
    override val description =
        "Qualité de l'air et pollens actuels (indice européen, particules PM2.5/PM10, pollens en Europe). Avec une ville, celle-ci ; " +
            "SANS ville (ou « ici », « chez moi »), la position du téléphone."
    override val parameters = objectSchema {
        string("city", "Nom de la ville ; vide ou « ici » pour utiliser la position de l'utilisateur.")
    }

    override suspend fun run(args: JsonObject, ctx: JarvisContainer): String = withContext(Dispatchers.IO) {
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

internal fun airQualityUrl(latitude: Double, longitude: Double): String =
    "https://air-quality-api.open-meteo.com/v1/air-quality".toHttpUrl().newBuilder()
        .addQueryParameter("latitude", latitude.toString())
        .addQueryParameter("longitude", longitude.toString())
        .addQueryParameter("current", "european_aqi,pm2_5,pm10,birch_pollen,grass_pollen,ragweed_pollen")
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
