package com.jarvis.android.actions

import com.jarvis.android.JarvisContainer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URLEncoder

object FlightSearchTool : Tool {
    override val name = "flight_search"
    override val description =
        "Chercher des vols entre deux villes : résultats Web avec prix indicatifs et lien Google Flights pour comparer et réserver. Les prix varient ; toujours vérifier sur le site de la compagnie."
    override val parameters = objectSchema(required = listOf("origin", "destination")) {
        string("origin", "Ville de départ, par exemple « Paris ».")
        string("destination", "Ville d’arrivée, par exemple « New York ».")
        string("date", "Date facultative en langage courant, par exemple « 12 décembre » ou « 2026-12-12 ».")
    }

    override suspend fun run(args: JsonObject, ctx: JarvisContainer): String {
        val origin = normalizedUtilityQuery(args.utilityString("origin"), 100)
            ?: return "Indiquez une ville de départ."
        val destination = normalizedUtilityQuery(args.utilityString("destination"), 100)
            ?: return "Indiquez une ville d’arrivée."
        val date = normalizedUtilityQuery(args.utilityString("date"), 100).orEmpty()
        val query = buildFlightQuery(origin, destination, date)
        val searchArgs = buildJsonObject { put("query", query) }
        val results = WebSearchTool.run(searchArgs, ctx)
        return "Vols $origin → $destination${if (date.isNotEmpty()) " ($date)" else ""} :\n$results\n\nComparer et réserver : ${buildGoogleFlightsUrl(query)}"
    }
}

internal fun buildFlightQuery(origin: String, destination: String, date: String): String =
    if (date.isBlank()) "vol $origin $destination prix billet"
    else "vol $origin $destination $date prix billet"

internal fun buildGoogleFlightsUrl(query: String): String =
    "https://www.google.com/travel/flights?q=" + URLEncoder.encode(query, Charsets.UTF_8.name())
