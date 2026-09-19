package com.jarvis.android.actions

import com.jarvis.android.JarvisContainer
import com.jarvis.android.timers.TimerDurations
import com.jarvis.android.timers.TimerService
import kotlinx.serialization.json.JsonObject

object TimerTool : Tool {
    override val name = "timer"
    override val description =
        "Gérer des minuteurs : démarrer un compte à rebours, lister les minuteurs actifs, annuler par identifiant. Durée en langage courant (« 45 secondes », « 10 minutes », « 1h30 »). Notification et lecture à voix haute à la fin."
    override val parameters = objectSchema(required = listOf("action")) {
        string("action", "Opération : 'create', 'list' ou 'cancel'.")
        string("duration", "Durée pour 'create', par exemple « 10 minutes » ou « 1h30 ».")
        string("label", "Libellé facultatif du minuteur.")
        integer("id", "Identifiant pour 'cancel'.")
    }

    override suspend fun run(args: JsonObject, ctx: JarvisContainer): String {
        return when (args.stringArg("action").trim().lowercase()) {
            "create" -> create(args, ctx)
            "list" -> list(ctx)
            "cancel" -> cancel(args, ctx)
            else -> "Opération inconnue : utilisez 'create', 'list' ou 'cancel'."
        }
    }

    private fun create(args: JsonObject, ctx: JarvisContainer): String {
        val seconds = try {
            TimerDurations.parse(args.stringArg("duration"))
        } catch (e: IllegalArgumentException) {
            return e.message ?: "Durée incomprise."
        }
        TimerService.notificationProblem(ctx.appContext)?.let { return it }
        return try {
            val record = TimerService.create(ctx.appContext, seconds, args.stringArg("label"))
            val approx = if (record.approximate) " (heure approximative, alarme exacte non autorisée)" else ""
            "Minuteur #${record.id} démarré : ${record.label}, fin dans ${TimerDurations.format(seconds)}$approx."
        } catch (e: IllegalArgumentException) {
            e.message ?: "Le minuteur n’a pas pu être démarré."
        } catch (_: Exception) {
            "Le minuteur n’a pas pu être démarré. Réessayez."
        }
    }

    private fun list(ctx: JarvisContainer): String {
        val records = try {
            TimerService.list(ctx.appContext)
        } catch (_: Exception) {
            return "Liste des minuteurs indisponible."
        }
        if (records.isEmpty()) return "Aucun minuteur actif."
        val now = System.currentTimeMillis()
        return records.joinToString("\n") { record ->
            val remaining = ((record.triggerAt - now) / 1000).coerceAtLeast(0)
            "#${record.id} : ${record.label} — reste ${TimerDurations.format(remaining)}."
        }
    }

    private fun cancel(args: JsonObject, ctx: JarvisContainer): String {
        val id = args.intArg("id")
        if (id <= 0) return "Indiquez l’identifiant du minuteur à annuler."
        return try {
            val record = TimerService.cancel(ctx.appContext, id)
            "Minuteur #${record.id} annulé (${record.label})."
        } catch (e: IllegalArgumentException) {
            e.message ?: "Annulation impossible."
        } catch (_: Exception) {
            "L’annulation a échoué."
        }
    }
}
