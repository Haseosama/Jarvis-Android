package com.jarvis.android.actions

import com.jarvis.android.JarvisContainer
import com.jarvis.android.core.UndoEntry
import com.jarvis.android.reminders.ReminderRecord
import com.jarvis.android.reminders.ReminderService
import com.jarvis.android.reminders.ReminderStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException

object ReminderTool : Tool {
    override val name = "reminder"
    override val description =
        "Créer, lister ou annuler un rappel local. Calculez la date à partir du contexte. " +
            "Le mode create est implicite. Les alarmes peuvent être approximatives ; " +
            "reprogrammation automatique après redémarrage. Le rappel est lu à voix haute à l’échéance."
    override val parameters = objectSchema {
        string("mode", "create (défaut), list ou cancel. list ne crée aucune alarme.")
        string("text", "Texte obligatoire pour create, limité à 1000 caractères.")
        string("when_iso", "Date locale stricte yyyy-MM-dd HH:mm, obligatoire pour create, dans le futur, sans heure ambiguë.")
        integer("id", "Identifiant retourné par create ou list, obligatoire pour cancel.")
        integer("offset", "Pour list : décalage de pagination, défaut 0. Dix rappels par page.")
    }

    override suspend fun run(args: JsonObject, ctx: JarvisContainer): String = safely {
        when (val mode = strictString(args, "mode", "create")) {
            "create" -> {
                val text = strictString(args, "text").trim()
                val date = strictString(args, "when_iso")
                val record = withContext(Dispatchers.IO) { ReminderService.create(ctx.appContext, text, date) }
                ctx.undoManager.push(UndoEntry("Créer le rappel ${record.id}") {
                    safely {
                        withContext(Dispatchers.IO) { ReminderService.cancel(ctx.appContext, record.id, record.token) }
                        "Rappel ${record.id} annulé."
                    }
                })
                created(record)
            }
            "list" -> withContext(Dispatchers.IO) {
                val offset = strictInt(args, "offset", 0)
                require(offset >= 0) { "Le décalage de liste doit être positif ou nul." }
                val records = ReminderService.list(ctx.appContext)
                val page = records.drop(offset).take(10)
                val notificationWarning = try {
                    ReminderService.notificationProblem(ctx.appContext)
                } catch (_: RuntimeException) {
                    "Impossible de vérifier les notifications."
                }
                buildString {
                    if (records.isEmpty()) append("Aucun rappel enregistré.")
                    else if (page.isEmpty()) append("Aucun rappel à ce décalage (${records.size} au total).")
                    else {
                        append("Rappels ${offset + 1} à ${offset + page.size} sur ${records.size} :")
                        page.forEach { record ->
                            append("\n#${record.id} — ${record.whenIso} (${record.zoneId}) — ${status(record)}")
                            append(if (record.approximate) " — approximatif" else " — exact demandé")
                            append(" — ${record.text.replace('\n', ' ').replace('\r', ' ')}")
                        }
                        if (offset + page.size < records.size) append("\nSuite : mode=list, offset=${offset + page.size}.")
                    }
                    if (notificationWarning != null) append("\n$notificationWarning")
                    append("\n${ReminderService.LIMITATION}")
                    append(" Un état programmé décrit le dernier enregistrement, pas une vérification auprès d’Android.")
                }
            }
            "cancel" -> {
                val id = strictInt(args, "id")
                require(id > 0) { "L’identifiant du rappel doit être un entier strictement positif." }
                val record = withContext(Dispatchers.IO) { ReminderService.cancel(ctx.appContext, id) }
                val reversible = record.status == ReminderStatus.SCHEDULED && record.triggerAt > System.currentTimeMillis()
                if (reversible) {
                    ctx.undoManager.push(UndoEntry("Annuler le rappel $id") {
                        safely {
                            val restored = withContext(Dispatchers.IO) { ReminderService.restore(ctx.appContext, record) }
                            created(restored)
                        }
                    })
                }
                "Rappel $id annulé et retiré de la liste." +
                    if (reversible) " Cette annulation peut être annulée tant que la date reste future." else ""
            }
            else -> "Mode inconnu « $mode » : utilisez create, list ou cancel."
        }
    }

    private fun strictString(args: JsonObject, key: String, default: String = ""): String {
        val value = args[key] ?: return default
        require(value is JsonPrimitive && value.isString) { "Le paramètre $key doit être du texte." }
        return value.content
    }

    private fun strictInt(args: JsonObject, key: String, default: Int? = null): Int {
        val value = args[key]
        if (value == null && default != null) return default
        require(value is JsonPrimitive && !value.isString) { "Le paramètre $key doit être un entier." }
        return value.content.toIntOrNull() ?: throw IllegalArgumentException("Le paramètre $key doit être un entier valide.")
    }

    private fun created(record: ReminderRecord): String =
        "Rappel #${record.id} enregistré pour ${record.whenIso} (${record.zoneId}) : ${record.text}. " +
            (if (record.approximate) "Alarme approximative : l’accès aux alarmes exactes est interdit ; Android peut la retarder. "
            else "Alarme exacte demandée à Android ; la livraison n’est pas garantie. ") + ReminderService.LIMITATION

    private fun status(record: ReminderRecord): String = when (record.status) {
        ReminderStatus.PREPARING -> "programmation non confirmée"
        ReminderStatus.SCHEDULED -> if (record.triggerAt <= System.currentTimeMillis()) "échéance atteinte, livraison non confirmée" else "programmé"
        ReminderStatus.DELIVERING -> "affichage non confirmé, pas de nouvelle tentative automatique"
        ReminderStatus.DELIVERED -> "notification transmise à Android"
        ReminderStatus.BLOCKED -> "notification bloquée, pas de nouvelle tentative automatique"
        ReminderStatus.FAILED -> "échec, annulez ce rappel avant de réessayer"
    }

    private suspend fun safely(block: suspend () -> String): String = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: IllegalArgumentException) {
        e.message ?: "Paramètres du rappel invalides."
    } catch (e: IOException) {
        e.message ?: "Stockage des rappels indisponible."
    } catch (e: IllegalStateException) {
        e.message ?: "Service de rappel indisponible."
    } catch (_: SecurityException) {
        "Android a refusé l’opération de rappel. Vérifiez les autorisations existantes dans les réglages."
    } catch (_: Exception) {
        "L’opération de rappel n’a pas pu être confirmée. Consultez la liste avant de réessayer."
    }
}
