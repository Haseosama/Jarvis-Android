package com.jarvis.android.actions

import com.jarvis.android.JarvisContainer
import com.jarvis.android.habits.HabitAlarms
import com.jarvis.android.habits.HabitAnswer
import com.jarvis.android.habits.adherence
import com.jarvis.android.habits.describeHabit
import com.jarvis.android.habits.findHabit
import com.jarvis.android.habits.formatTime
import com.jarvis.android.habits.parseHabitDays
import com.jarvis.android.habits.parseHabitTimes
import com.jarvis.android.habits.slotToAnswer
import com.jarvis.android.habits.todayStatus
import com.jarvis.android.reminders.ReminderService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.time.LocalDateTime
import java.time.ZoneId

/** Medications and habits at fixed times, with a log of what was done, skipped or not noted. */
object HabitsTool : Tool {
    override val name = "habits"
    override val description =
        "Médicaments et habitudes à heure fixe (« mon médicament à 8 h tous les jours », « boire de l'eau à 10 h, 14 h et 17 h », « sport lundi et jeudi à 18 h »). " +
            "À chaque heure, Jarvis le dit et affiche une notification avec « Fait » / « Pas cette fois ». Actions : create (name, times, days, medication), " +
            "done (« j'ai pris mon médicament », « c'est fait »), skip, status (aujourd'hui, ou period = semaine / mois pour les oublis), list, delete. " +
            "C'est un aide-mémoire : ne donnez jamais de conseil de dose, de posologie ou de traitement ; pour cela, renvoyez vers un médecin ou un pharmacien."
    override val parameters = objectSchema {
        string("action", "'create', 'done', 'skip', 'status' (défaut), 'list' ou 'delete'.")
        string("name", "Nom de l'habitude ou du médicament, tel que dit (« Doliprane », « boire de l'eau »). Facultatif pour done/status s'il n'y en a qu'une.")
        string("times", "Pour create : heures séparées par des virgules, « 8h, 20h » ou « 08:00 ».")
        string("days", "Pour create : jours (« lundi, jeudi », « en semaine », « le week-end ») ; vide = tous les jours.")
        string("medication", "Pour create : 'true' si c'est un médicament.")
        string("period", "Pour status : vide pour aujourd'hui, 'semaine' ou 'mois' pour compter les oublis.")
    }

    override suspend fun run(args: JsonObject, ctx: JarvisContainer): String = withContext(Dispatchers.IO) {
        val store = ctx.habitStore
        val zone = ZoneId.systemDefault()
        val now = LocalDateTime.now(zone)
        val name = args.stringArg("name").trim()
        val habits = store.load().habits
        fun pick(): com.jarvis.android.habits.Habit? = findHabit(habits, name)
            ?: if (name.isEmpty() || name.lowercase() in setOf("medicament", "médicament", "mon médicament", "mon medicament")) {
                com.jarvis.android.habits.habitWaitingForAnswer(habits.filter { name.isEmpty() || it.medication }, store.load().log, now, zone)
            } else null
        val none = if (habits.isEmpty()) "Aucune habitude ni aucun médicament enregistré." else
            "Je ne trouve pas « $name ». Enregistrés : ${habits.joinToString(", ") { it.name }}."

        when (args.stringArg("action").trim().lowercase().ifEmpty { "status" }) {
            "create" -> {
                if (name.isEmpty()) return@withContext "Indiquez le nom de l'habitude ou du médicament."
                val times = parseHabitTimes(args.stringArg("times"))
                    ?: return@withContext "Heures invalides : dites par exemple « 8h, 20h »."
                ReminderService.notificationProblem(ctx.appContext)?.let { return@withContext "$it Sans notifications, je ne pourrai pas vous prévenir." }
                val medication = args.stringArg("medication").trim().lowercase() in setOf("true", "oui", "1", "yes")
                val habit = store.upsert(name, times, parseHabitDays(args.stringArg("days")), medication, System.currentTimeMillis())
                    ?: return@withContext "Impossible d'enregistrer (au plus 30 habitudes et 8 heures chacune)."
                HabitAlarms.reschedule(ctx.appContext)
                "Enregistré : ${describeHabit(habit)}. Je vous préviendrai à chaque heure." +
                    if (HabitAlarms.exactAlarmsAllowed(ctx.appContext)) "" else
                        " Attention : Android ne m'autorise pas encore les alarmes exactes, le rappel peut arriver avec du retard. L'utilisateur peut l'autoriser dans Réglages de Jarvis > Médicaments et habitudes."
            }
            "done", "skip" -> {
                val habit = pick() ?: return@withContext none
                val answer = if (args.stringArg("action").trim().lowercase() == "done") HabitAnswer.DONE else HabitAnswer.SKIPPED
                val slot = slotToAnswer(habit, store.load().log, now, zone)
                val slotMs = slot?.atZone(zone)?.toInstant()?.toEpochMilli() ?: 0L
                if (slotMs != 0L) HabitAlarms.answer(ctx.appContext, habit.id, slotMs, answer)
                else store.answer(habit.id, 0L, answer, System.currentTimeMillis())
                val what = if (answer == HabitAnswer.DONE) "noté comme fait" else "noté comme sauté"
                if (slot != null) "${habit.name} de ${formatTime(slot.toLocalTime())} $what."
                else "${habit.name} $what (en dehors des heures prévues)."
            }
            "status" -> {
                val log = store.load().log
                val targets = if (name.isEmpty()) habits else listOfNotNull(pick())
                if (targets.isEmpty()) return@withContext none
                val period = com.jarvis.android.offline.normalize(args.stringArg("period"))
                targets.joinToString("\n") { h ->
                    when {
                        "semaine" in period -> adherence(h, log, now.toLocalDate().minusDays(6).atStartOfDay(), now, zone, "sur 7 jours")
                        "mois" in period -> adherence(h, log, now.toLocalDate().minusDays(29).atStartOfDay(), now, zone, "sur 30 jours")
                        else -> todayStatus(h, log, now, zone)
                    }
                }
            }
            "list" -> if (habits.isEmpty()) "Aucune habitude ni aucun médicament enregistré." else habits.joinToString("\n") { describeHabit(it) }
            "delete" -> {
                val habit = pick() ?: return@withContext none
                store.remove(habit.id)
                HabitAlarms.reschedule(ctx.appContext)
                "« ${habit.name} » supprimé, avec son historique."
            }
            else -> "Action inconnue : create, done, skip, status, list ou delete."
        }
    }
}
