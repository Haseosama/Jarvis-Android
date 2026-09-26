package com.jarvis.android.actions

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import com.jarvis.android.JarvisContainer
import com.jarvis.android.health.HealthStatus
import com.jarvis.android.health.activityBetween
import com.jarvis.android.health.describeActivity
import com.jarvis.android.health.describeNight
import com.jarvis.android.health.grantedHealth
import com.jarvis.android.health.healthClient
import com.jarvis.android.health.healthStatus
import com.jarvis.android.health.heartBetween
import com.jarvis.android.health.nightEndingOn
import com.jarvis.android.health.thousands
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Steps, sleep and heart rate from Health Connect (see health/Health.kt). */
object HealthTool : Tool {
    override val name = "health"
    override val description =
        "Santé, lue dans Health Connect (montre, compteur de pas, appli de sport) : « combien de pas aujourd'hui ? » (steps), « mes pas cette " +
            "semaine » (steps, period week), « comment j'ai dormi ? » (sleep), « mon rythme cardiaque aujourd'hui » (heart), « mon bilan santé » " +
            "(summary). Chiffres seulement : aucun avis médical."
    override val parameters = objectSchema {
        string("action", "'summary' (défaut), 'steps', 'sleep' ou 'heart'.")
        string("period", "Pour steps : 'today' (défaut), 'yesterday' ou 'week'. Pour sleep : 'today' (la nuit dernière, défaut) ou 'yesterday' (la nuit d'avant).")
    }

    override suspend fun run(args: JsonObject, ctx: JarvisContainer): String {
        val context = ctx.appContext
        when (healthStatus(context)) {
            HealthStatus.UNAVAILABLE -> return "Health Connect n'est pas disponible sur ce téléphone."
            HealthStatus.NEEDS_UPDATE -> return "Health Connect doit être mis à jour (Play Store) avant que Jarvis puisse le lire."
            HealthStatus.AVAILABLE -> Unit
        }
        val client = healthClient(context) ?: return "Health Connect n'est pas disponible sur ce téléphone."
        val granted = grantedHealth(context)
        if (granted.isEmpty()) return "Jarvis n'a pas encore accès à Health Connect : autorisez-le dans les réglages de Jarvis (carte Santé)."
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val now = Instant.now()
        fun has(p: String) = p in granted
        val period = args.stringArg("period").trim().lowercase()
        return try {
            when (args.stringArg("action").trim().lowercase().ifEmpty { "summary" }) {
                "steps" -> {
                    if (!has(HealthPermission.getReadPermission(StepsRecord::class))) return "L'accès aux pas n'est pas autorisé (carte Santé des réglages)."
                    when (period) {
                        "week" -> {
                            val days = (6 downTo 0).map { today.minusDays(it.toLong()) }
                            val counts = days.map { d ->
                                activityBetween(client, d.atStartOfDay(zone).toInstant(), minOf(now, d.plusDays(1).atStartOfDay(zone).toInstant()), granted).steps.coerceAtLeast(0)
                            }
                            val total = counts.sum()
                            if (total == 0L) return "Aucun pas enregistré dans Health Connect ces 7 derniers jours."
                            "Sur 7 jours : ${thousands(total)} pas, ${thousands(total / 7)} par jour en moyenne ; le plus : ${thousands(counts.max())} pas " +
                                "(${days[counts.indexOf(counts.max())].let { if (it == today) "aujourd'hui" else "le ${it.dayOfMonth}/${it.monthValue.toString().padStart(2, '0')}" }})."
                        }
                        "yesterday" -> {
                            val a = activityBetween(client, today.minusDays(1).atStartOfDay(zone).toInstant(), today.atStartOfDay(zone).toInstant(), granted)
                            "Hier : ${describeActivity(a)}."
                        }
                        else -> {
                            val a = activityBetween(client, today.atStartOfDay(zone).toInstant(), now, granted)
                            "Aujourd'hui : ${describeActivity(a)}."
                        }
                    }
                }
                "sleep" -> {
                    if (!has(HealthPermission.getReadPermission(SleepSessionRecord::class))) return "L'accès au sommeil n'est pas autorisé (carte Santé des réglages)."
                    val day = if (period == "yesterday") today.minusDays(1) else today
                    val night = nightEndingOn(client, day, zone) ?: return "Aucune nuit enregistrée dans Health Connect pour ${if (day == today) "la nuit dernière" else "la nuit d'avant"}."
                    (if (day == today) "La nuit dernière : " else "La nuit d'avant : ") + describeNight(night, zone) + "."
                }
                "heart" -> {
                    if (!has(HealthPermission.getReadPermission(HeartRateRecord::class))) return "L'accès au rythme cardiaque n'est pas autorisé (carte Santé des réglages)."
                    val h = heartBetween(client, today.atStartOfDay(zone).toInstant(), now) ?: return "Aucune mesure de rythme cardiaque aujourd'hui."
                    "Rythme cardiaque aujourd'hui : ${h.avg} battements par minute en moyenne (de ${h.min} à ${h.max})."
                }
                else -> {
                    val parts = mutableListOf<String>()
                    val a = activityBetween(client, today.atStartOfDay(zone).toInstant(), now, granted)
                    if (a.steps >= 0) parts += "aujourd'hui ${describeActivity(a)}"
                    if (has(HealthPermission.getReadPermission(SleepSessionRecord::class))) nightEndingOn(client, today, zone)?.let { parts += "la nuit dernière ${describeNight(it, zone)}" }
                    if (has(HealthPermission.getReadPermission(HeartRateRecord::class))) heartBetween(client, today.atStartOfDay(zone).toInstant(), now)?.let { parts += "rythme cardiaque moyen ${it.avg} par minute" }
                    if (parts.isEmpty()) "Aucune donnée de santé pour aujourd'hui dans Health Connect."
                    else parts.joinToString(" ; ").replaceFirstChar { it.uppercase() } + "."
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            "Health Connect a refusé la lecture : vérifiez les autorisations de Jarvis (carte Santé des réglages)."
        } catch (e: Exception) {
            "Lecture de Health Connect impossible pour le moment."
        }
    }
}
