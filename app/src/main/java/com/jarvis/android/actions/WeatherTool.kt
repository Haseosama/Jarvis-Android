package com.jarvis.android.actions

import com.jarvis.android.JarvisContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.io.IOException
import java.util.Locale

object WeatherTool : Tool {
    override val name = "weather_report"
    override val description = "Obtenir les conditions météo actuelles d’une ville."
    override val parameters = objectSchema(required = listOf("city")) {
        string("city", "Nom de la ville à rechercher.")
    }

    override suspend fun run(args: JsonObject, ctx: JarvisContainer): String = withContext(Dispatchers.IO) {
        val city = normalizedUtilityQuery(args.utilityString("city"), 200)
            ?: return@withContext "Indiquez une ville non vide, de 200 caractères maximum."
        try {
            val geoUrl = "https://geocoding-api.open-meteo.com/v1/search".toHttpUrl().newBuilder()
                .addQueryParameter("count", "1").addQueryParameter("language", "fr")
                .addQueryParameter("name", city).build()
            val geoBody = ctx.http.newCall(Request.Builder().url(geoUrl).build()).execute().use { response ->
                if (!response.isSuccessful) return@withContext utilityHttpError("Localisation", response.code)
                response.body?.string().orEmpty()
            }
            val place = parseWeatherPlace(geoBody)
                ?: return@withContext "Aucune ville trouvée pour « $city ». Précisez son nom."
            val wxUrl = "https://api.open-meteo.com/v1/forecast".toHttpUrl().newBuilder()
                .addQueryParameter("latitude", place.latitude.toString())
                .addQueryParameter("longitude", place.longitude.toString())
                .addQueryParameter("current", "temperature_2m,relative_humidity_2m,weather_code,wind_speed_10m")
                .addQueryParameter("temperature_unit", "celsius")
                .addQueryParameter("wind_speed_unit", "kmh").build()
            val wxBody = ctx.http.newCall(Request.Builder().url(wxUrl).build()).execute().use { response ->
                if (!response.isSuccessful) return@withContext utilityHttpError("Météo", response.code)
                response.body?.string().orEmpty()
            }
            formatCurrentWeather(wxBody, place.label)
        } catch (e: CancellationException) {
            throw e
        } catch (_: IOException) {
            "Météo indisponible : connexion impossible ou délai dépassé. Réessayez plus tard."
        } catch (_: Exception) {
            "Météo indisponible : données du service incomplètes ou invalides."
        }
    }
}

internal data class WeatherPlace(val latitude: Double, val longitude: Double, val label: String)

internal fun parseWeatherPlace(body: String): WeatherPlace? {
    val root = Json.parseToJsonElement(body) as? JsonObject ?: error("Invalid weather data")
    require(root["error"]?.toString() != "true")
    val results = root["results"] ?: return null
    require(results is JsonArray)
    if (results.isEmpty()) return null
    val place = results.first() as? JsonObject ?: error("Invalid place")
    val lat = (place["latitude"] as? JsonPrimitive)?.doubleOrNull
    val lon = (place["longitude"] as? JsonPrimitive)?.doubleOrNull
    require(lat != null && lat.isFinite() && lat in -90.0..90.0)
    require(lon != null && lon.isFinite() && lon in -180.0..180.0)
    val name = (place["name"] as? JsonPrimitive)?.contentOrNull?.trim()
    require(!name.isNullOrBlank())
    val label = listOfNotNull(name, (place["admin1"] as? JsonPrimitive)?.contentOrNull,
        (place["country"] as? JsonPrimitive)?.contentOrNull)
        .filter { it.isNotBlank() }.distinct().joinToString(", ").take(300)
    return WeatherPlace(lat, lon, label)
}

internal fun formatCurrentWeather(body: String, label: String): String {
    val root = Json.parseToJsonElement(body) as? JsonObject ?: error("Invalid weather data")
    require(root["error"]?.toString() != "true")
    val current = root["current"] as? JsonObject ?: error("Missing current weather")
    fun number(key: String): Double {
        val value = (current[key] as? JsonPrimitive)?.doubleOrNull
        require(value != null && value.isFinite())
        return value
    }
    val temp = number("temperature_2m")
    val humidity = number("relative_humidity_2m")
    val wind = number("wind_speed_10m")
    require(humidity in 0.0..100.0 && wind >= 0.0)
    val code = (current["weather_code"] as? JsonPrimitive)?.intOrNull
        ?: error("Missing weather code")
    return "Météo à $label : ${describeWeatherCode(code)}, ${String.format(Locale.FRANCE, "%.1f", temp)} °C, " +
        "humidité ${String.format(Locale.FRANCE, "%.0f", humidity)} %, vent ${String.format(Locale.FRANCE, "%.1f", wind)} km/h."
}

internal fun describeWeatherCode(code: Int): String = when (code) {
    0 -> "ciel dégagé"
    1 -> "ciel principalement dégagé"
    2 -> "partiellement nuageux"
    3 -> "ciel couvert"
    45, 48 -> "brouillard"
    51, 53, 55 -> "bruine"
    56, 57 -> "bruine verglaçante"
    61, 63, 65 -> "pluie"
    66, 67 -> "pluie verglaçante"
    71, 73, 75 -> "neige"
    77 -> "grains de neige"
    80, 81, 82 -> "averses de pluie"
    85, 86 -> "averses de neige"
    95 -> "orage"
    96, 99 -> "orage avec grêle"
    else -> "conditions non précisées"
}
