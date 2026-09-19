package com.jarvis.android.reminders

import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Locale

object ReminderDates {
    private val format = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm", Locale.ROOT)
        .withResolverStyle(ResolverStyle.STRICT)
    private val shape = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}")

    fun parse(value: String, zone: ZoneId, now: Instant): Long {
        require(shape.matches(value)) { "Date invalide : utilisez yyyy-MM-dd HH:mm." }
        val local = try {
            LocalDateTime.parse(value, format)
        } catch (e: DateTimeException) {
            throw IllegalArgumentException("Date invalide : utilisez une date réelle au format yyyy-MM-dd HH:mm.")
        }
        require(local.year in 1..9999) { "L’année doit être comprise entre 0001 et 9999." }
        val offsets = zone.rules.getValidOffsets(local)
        require(offsets.isNotEmpty()) { "Cette heure n’existe pas dans le fuseau $zone (changement d’heure)." }
        require(offsets.size == 1) { "Cette heure est ambiguë dans le fuseau $zone (changement d’heure). Choisissez une autre heure." }
        val instant = local.toInstant(offsets.single())
        require(instant.isAfter(now)) { "La date du rappel doit être strictement dans le futur." }
        return instant.toEpochMilli()
    }
}
