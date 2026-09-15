package com.jarvis.android.actions

import com.jarvis.android.JarvisContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request

/** Live weather — Android port of `actions/weather_report.py`, via Open-Meteo (no API key needed). */
object WeatherTool : Tool {
    override val name = "weather_report"
    override val description = "Get the current weather for a city."
    override val parameters = objectSchema(required = listOf("city")) {
        string("city", "City name, e.g. 'Paris' or 'Istanbul'.")
    }

    override suspend fun run(args: JsonObject, ctx: JarvisContainer): String = withContext(Dispatchers.IO) {
        val city = args.stringArg("city")
        if (city.isBlank()) return@withContext "Which city?"
        try {
            val geoUrl = "https://geocoding-api.open-meteo.com/v1/search?count=1&name=" +
                java.net.URLEncoder.encode(city, "UTF-8")
            val geoBody = ctx.http.newCall(Request.Builder().url(geoUrl).build()).execute().use { it.body?.string() }
                ?: return@withContext "Weather lookup failed for '$city'."
            val geo = Json.parseToJsonElement(geoBody).jsonObject
            val results = geo["results"]?.jsonArray
            if (results == null || results.isEmpty()) return@withContext "Could not find a city named '$city'."
            val place = results[0].jsonObject
            val lat = place["latitude"]!!.jsonPrimitive.content
            val lon = place["longitude"]!!.jsonPrimitive.content
            val label = place["name"]?.jsonPrimitive?.content ?: city

            val wxUrl = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                "&current=temperature_2m,relative_humidity_2m,weather_code,wind_speed_10m"
            val wxBody = ctx.http.newCall(Request.Builder().url(wxUrl).build()).execute().use { it.body?.string() }
                ?: return@withContext "Weather lookup failed for '$city'."
            val wx = Json.parseToJsonElement(wxBody).jsonObject["current"]?.jsonObject
                ?: return@withContext "Weather data unavailable for '$city'."

            val temp = wx["temperature_2m"]?.jsonPrimitive?.content ?: "?"
            val humidity = wx["relative_humidity_2m"]?.jsonPrimitive?.content ?: "?"
            val wind = wx["wind_speed_10m"]?.jsonPrimitive?.content ?: "?"
            val code = wx["weather_code"]?.jsonPrimitive?.content?.toIntOrNull() ?: -1

            "Weather in $label: ${temp}°C, ${describeWeatherCode(code)}, humidity $humidity%, wind ${wind} km/h."
        } catch (e: Exception) {
            "Weather lookup failed: ${e.message}"
        }
    }

    private fun describeWeatherCode(code: Int): String = when (code) {
        0 -> "clear sky"
        1, 2, 3 -> "partly cloudy"
        45, 48 -> "foggy"
        in 51..57 -> "drizzle"
        in 61..67 -> "rain"
        in 71..77 -> "snow"
        in 80..82 -> "rain showers"
        in 95..99 -> "thunderstorm"
        else -> "mixed conditions"
    }
}
