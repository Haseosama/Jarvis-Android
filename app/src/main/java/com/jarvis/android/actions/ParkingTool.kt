package com.jarvis.android.actions

import android.content.Intent
import android.net.Uri
import com.jarvis.android.JarvisContainer
import com.jarvis.android.parking.ParkingSaver
import com.jarvis.android.parking.directionWord
import com.jarvis.android.parking.distanceWords
import com.jarvis.android.parking.sinceWords
import com.jarvis.android.weather.LocationOutcome
import kotlinx.serialization.json.JsonObject
import java.util.Locale

/** "Retiens où je me suis garé" / "où est ma voiture ?" */
object ParkingTool : Tool {
    override val name = "parking"
    override val description =
        "La place de la voiture. save : « retiens où je me suis garé » (note facultative : « niveau -2, place 45 »). find : « où est ma voiture ? » — " +
            "distance, direction, depuis quand, et open = 'true' pour ouvrir l'itinéraire à pied. forget : oublier la place."
    override val parameters = objectSchema {
        string("action", "'save', 'find' (défaut) ou 'forget'.")
        string("note", "Pour save : un détail (étage, numéro de place…).")
        string("open", "Pour find : 'true' pour ouvrir l'itinéraire à pied dans Maps.")
    }

    override suspend fun run(args: JsonObject, ctx: JarvisContainer): String {
        val store = ctx.parkingStore
        return when (args.stringArg("action").trim().lowercase().ifEmpty { "find" }) {
            "save" -> ParkingSaver.saveHere(ctx.appContext, args.stringArg("note"), auto = false)
            "forget" -> { store.update { it.copy(car = null) }; "Place de la voiture oubliée." }
            "find" -> {
                val car = store.load().car ?: return "Aucune place enregistrée. Dites « retiens où je me suis garé » en sortant de la voiture."
                val now = System.currentTimeMillis()
                val note = if (car.note.isNotBlank()) " Note : ${car.note}." else ""
                val how = if (car.auto) " (enregistrée automatiquement en quittant le Bluetooth de la voiture)" else ""
                val where = car.label.ifEmpty { "l'endroit enregistré" }
                val relative = when (val here = com.jarvis.android.weather.locate(ctx.appContext, maxAgeMs = com.jarvis.android.parking.FRESH_FIX_MS)) {
                    is LocationOutcome.Found -> {
                        val km = distanceKm(here.fix.latitude, here.fix.longitude, car.latitude, car.longitude)
                        if (km < 0.03) " Vous êtes juste à côté." else " Elle est ${distanceWords(km)} ${directionWord(here.fix.latitude, here.fix.longitude, car.latitude, car.longitude)}."
                    }
                    else -> ""
                }
                if (args.stringArg("open").trim().lowercase() in setOf("true", "oui", "1")) {
                    val uri = Uri.parse("https://www.google.com/maps/dir/?api=1&travelmode=walking&destination=" +
                        String.format(Locale.ROOT, "%.6f,%.6f", car.latitude, car.longitude))
                    try { ctx.appContext.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
                }
                "Voiture garée ${sinceWords(car.at, now)} près de $where$how.$relative$note"
            }
            else -> "Action inconnue : save, find ou forget."
        }
    }
}
