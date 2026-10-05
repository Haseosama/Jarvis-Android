package com.jarvis.android.widget

import com.jarvis.android.reminders.ReminderRecord
import com.jarvis.android.reminders.ReminderStatus
import com.jarvis.android.weather.describeWeatherCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.roundToInt

/** The weather the widget shows: Open-Meteo's current temperature and WMO weather code, and whether the sun is up. */
internal data class WidgetWeather(val temperature: Double, val code: Int, val day: Boolean)

/** Open-Meteo's answer (`current=temperature_2m,weather_code,is_day`); null when it is not one. */
internal fun parseWidgetWeather(body: String): WidgetWeather? = try {
    val current = (Json.parseToJsonElement(body) as? JsonObject)?.get("current") as? JsonObject
    val temperature = (current?.get("temperature_2m") as? JsonPrimitive)?.doubleOrNull
    val code = (current?.get("weather_code") as? JsonPrimitive)?.intOrNull
    val day = (current?.get("is_day") as? JsonPrimitive)?.intOrNull ?: 1
    if (temperature == null || !temperature.isFinite() || temperature !in -90.0..70.0 || code == null) null
    else WidgetWeather(temperature, code, day != 0)
} catch (_: Exception) {
    null
}

/** A symbol for a WMO weather code: a sun (or a moon at night), clouds, rain, snow, a storm. */
internal fun weatherSymbol(code: Int, day: Boolean): String = when (code) {
    0 -> if (day) "☀️" else "🌙"
    1, 2 -> if (day) "⛅" else "☁️"
    3 -> "☁️"
    45, 48 -> "🌫️"
    51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82 -> "🌧️"
    71, 73, 75, 77, 85, 86 -> "🌨️"
    95, 96, 99 -> "⛈️"
    else -> "🌡️"
}

/** "☀️ 18 °C, ciel dégagé · Lyon" (the place left out when it is not known). */
internal fun weatherLine(weather: WidgetWeather, place: String?): String {
    val base = "${weatherSymbol(weather.code, weather.day)} ${weather.temperature.roundToInt()} °C, ${describeWeatherCode(weather.code)}"
    return if (place.isNullOrBlank()) base else "$base · ${place.trim()}"
}

/** The next reminder still to come, or null. */
internal fun nextReminder(records: List<ReminderRecord>, nowMs: Long): ReminderRecord? =
    records.filter { it.status == ReminderStatus.SCHEDULED && it.triggerAt >= nowMs }.minByOrNull { it.triggerAt }

/**
 * When a reminder comes and what it says, as short as the widget needs: "Aujourd'hui 18:00", "Demain 08:30", a weekday within the
 * week ("Jeudi 09:00"), else the date ("12 oct. 09:00"); the text cut at [maxChars].
 */
internal fun reminderLine(record: ReminderRecord, nowMs: Long, zone: ZoneId, maxChars: Int = 80): String {
    val now = Instant.ofEpochMilli(nowMs).atZone(zone)
    val at = Instant.ofEpochMilli(record.triggerAt).atZone(zone)
    val days = ChronoUnit.DAYS.between(now.toLocalDate(), at.toLocalDate())
    val hour = at.format(DateTimeFormatter.ofPattern("HH:mm"))
    val day = when {
        days <= 0L -> "Aujourd’hui"
        days == 1L -> "Demain"
        days < 7L -> at.format(DateTimeFormatter.ofPattern("EEEE", Locale.FRANCE)).replaceFirstChar { it.titlecase(Locale.FRANCE) }
        else -> at.format(DateTimeFormatter.ofPattern("d MMM", Locale.FRANCE))
    }
    val text = record.text.trim().replace(Regex("\\s+"), " ")
    val cut = if (text.length <= maxChars) text else text.take(maxChars - 1).trimEnd() + "…"
    return "$day $hour · $cut"
}
