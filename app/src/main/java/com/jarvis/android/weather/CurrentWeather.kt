package com.jarvis.android.weather

import com.jarvis.android.JarvisContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.util.Locale

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

/** The current weather at a point, in words; null when the service answers with an error. Throws on a network failure. */
internal suspend fun weatherAt(ctx: JarvisContainer, latitude: Double, longitude: Double, label: String): String? {
    val wxUrl = "https://api.open-meteo.com/v1/forecast".toHttpUrl().newBuilder()
        .addQueryParameter("latitude", "%.3f".format(Locale.ROOT, latitude))
        .addQueryParameter("longitude", "%.3f".format(Locale.ROOT, longitude))
        .addQueryParameter("current", "temperature_2m,relative_humidity_2m,weather_code,wind_speed_10m")
        .addQueryParameter("temperature_unit", "celsius")
        .addQueryParameter("wind_speed_unit", "kmh").build()
    val body = withContext(Dispatchers.IO) {
        ctx.http.newCall(Request.Builder().url(wxUrl).build()).execute().use { response ->
            if (!response.isSuccessful) return@withContext null
            response.body?.string().orEmpty()
        }
    } ?: return null
    return formatCurrentWeather(body, label)
}
