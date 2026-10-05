package com.jarvis.android.air

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.net.URLEncoder
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

/*
 * The French ATMO index, the one the regional air agencies (Airparif, Atmo AuRA…) publish for each commune every day around 14 h, for
 * the day and the next: 1 bon to 6 extrêmement mauvais, the worst of five pollutants. Read from Atmo France's open WFS (no key), the
 * commune found by the State's geographic API. No Android here, so the tests run on the JVM.
 */

/** The day's index for a commune: its level (1 to 6), the agency's words for it, and the pollutants that set it. */
internal data class AtmoDay(val code: Int, val label: String, val pollutants: List<String>)

private val ATMO_LABELS = listOf("bon", "moyen", "dégradé", "mauvais", "très mauvais", "extrêmement mauvais")

private val ATMO_POLLUTANTS = listOf(
    "code_o3" to "de l’ozone", "code_no2" to "du dioxyde d’azote", "code_pm10" to "des particules PM10",
    "code_pm25" to "des particules fines PM2,5", "code_so2" to "du dioxyde de soufre",
)

internal fun atmoUrl(insee: String): String =
    "https://data.atmo-france.org/geoserver/ind/ows?service=WFS&version=2.0.0&request=GetFeature&typeNames=ind:ind_atmo_2021" +
        "&outputFormat=application/json&count=20&CQL_FILTER=" + URLEncoder.encode("code_zone='${insee.filter { it.isLetterOrDigit() }}'", "UTF-8").replace("+", "%20")

/** "2026-10-05", "2026-10-05Z" or an instant ("2026-10-04T22:00:00Z", midnight in Paris): the day it stands for. */
internal fun atmoDate(s: String): LocalDate? = try {
    if (s.length > 11 && 'T' in s) OffsetDateTime.parse(s).atZoneSameInstant(ZoneId.of("Europe/Paris")).toLocalDate() else LocalDate.parse(s.take(10))
} catch (_: Exception) {
    null
}

/** The commune's index for that day in the WFS answer, or null when it is not there (not published yet, no data: code 0 or 7). */
internal fun parseAtmo(json: String, insee: String, day: LocalDate): AtmoDay? {
    val features = (Json.parseToJsonElement(json) as? JsonObject)?.get("features") as? JsonArray ?: return null
    val props = features.mapNotNull { (it as? JsonObject)?.get("properties") as? JsonObject }.lastOrNull { p ->
        (p["code_zone"] as? JsonPrimitive)?.contentOrNull == insee && (p["date_ech"] as? JsonPrimitive)?.contentOrNull?.let(::atmoDate) == day
    } ?: return null
    fun code(k: String) = (props[k] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() }
    val code = code("code_qual")?.takeIf { it in 1..6 } ?: return null
    val label = (props["lib_qual"] as? JsonPrimitive)?.contentOrNull?.lowercase()?.takeIf { it.isNotBlank() } ?: ATMO_LABELS[code - 1]
    return AtmoDay(code, label, ATMO_POLLUTANTS.filter { code(it.first) == code }.map { it.second })
}

/** "air du jour : moyen (indice Atmo 2 sur 6)", and from dégradé on, what makes it so. */
internal fun atmoBriefingWords(d: AtmoDay): String =
    "air du jour : ${d.label} (indice Atmo ${d.code} sur 6" + (if (d.code >= 3 && d.pollutants.isNotEmpty()) ", à cause ${joinFr(d.pollutants)})" else ")")

private fun joinFr(items: List<String>) = if (items.size < 2) items.joinToString() else items.dropLast(1).joinToString(", ") + " et " + items.last()

/** The INSEE code of the commune at a place, from geo.api.gouv.fr's answer. */
internal fun parseCommuneCode(json: String): String? =
    (((Json.parseToJsonElement(json) as? JsonArray)?.firstOrNull() as? JsonObject)?.get("code") as? JsonPrimitive)?.contentOrNull
