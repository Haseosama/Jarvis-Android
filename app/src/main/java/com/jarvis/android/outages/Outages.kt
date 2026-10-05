package com.jarvis.android.outages

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.jarvis.android.JarvisContainer
import com.jarvis.android.i18n.tr
import com.jarvis.android.reminders.ReminderService
import com.jarvis.android.text.normalize
import com.jarvis.android.tool.Tool
import com.jarvis.android.tool.objectSchema
import com.jarvis.android.tool.stringArg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/*
 * The planned cuts of electricity, water or gas at home. No open data lists them (Enedis shows its works cuts by address on its site,
 * the water services each their own way), but the notices reach the phone: Enedis's SMS or mail to the customers signed up for its
 * alerts, the water service's, GRDF's, the town hall's app. Jarvis reads them in the notifications (with the access already given for
 * that), notes the cut, says so, reminds the evening before with what to do, and says it in the morning briefing. A notice can also
 * be handed over by voice ("note une coupure d'eau jeudi de 8 h à 12 h").
 */

internal object Outages {
    private const val CHANNEL = "jarvis_outages"
    private val lock = Any()

    private fun prefs(c: Context) = c.getSharedPreferences("outages", Context.MODE_PRIVATE)

    /** The noted cuts of today and later, soonest first. */
    fun upcoming(c: Context, today: LocalDate = LocalDate.now()): List<Outage> = upcomingOutages(outagesFromJson(prefs(c).getString("all", null)), today)

    private fun save(c: Context, all: List<Outage>) = prefs(c).edit().putString("all", outagesToJson(all)).apply()

    /**
     * Notes a cut and sets its reminder (the one of a cut noted again is replaced); returns the cut as kept, or null when it was
     * already noted the same way.
     */
    fun note(c: Context, o: Outage, now: LocalDateTime = LocalDateTime.now()): Outage? = synchronized(lock) {
        val today = now.toLocalDate()
        val all = upcoming(c, today)
        val before = all.firstOrNull { it.key == o.key }
        if (before != null && before.end == o.end) return null
        before?.reminderId?.let { cancelReminder(c, it) }
        val reminder = outageReminderAt(o, now)?.let { at ->
            try {
                ReminderService.create(c, reminderText(o, at.toLocalDate()), at.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))).id
            } catch (_: Exception) {
                null
            }
        }
        val kept = o.copy(reminderId = reminder)
        save(c, addOutage(all, kept, today))
        kept
    }

    /** Forgets the cuts that [match] (and their reminders); returns those forgotten. */
    fun forget(c: Context, match: (Outage) -> Boolean): List<Outage> = synchronized(lock) {
        val all = upcoming(c)
        val gone = all.filter(match)
        gone.forEach { o -> o.reminderId?.let { cancelReminder(c, it) } }
        save(c, all - gone.toSet())
        gone
    }

    private fun cancelReminder(c: Context, id: Int) {
        try { ReminderService.cancel(c, id) } catch (_: Exception) { }
    }

    /** A notice seen in a notification: noted and told in a notification of Jarvis's own when it speaks of a cut to come. */
    fun offerFromMessage(c: Context, app: String, text: String) {
        val o = parseOutage(text, LocalDate.now(), source = app) ?: return
        val kept = note(c, o) ?: return
        val today = LocalDate.now()
        val body = outageLine(kept, today).replaceFirstChar { it.uppercase() } + " (vue dans $app)." +
            if (kept.reminderId != null) " " + tr("Je vous le rappelle la veille au soir.") else ""
        com.jarvis.android.journal.Journal.alert(c, com.jarvis.android.journal.AlertKind.OUTAGE, outageLine(kept, today))
        try {
            c.getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, tr("Coupures prévues"), NotificationManager.IMPORTANCE_DEFAULT))
            NotificationManagerCompat.from(c).notify(
                "outage", kept.key.hashCode(),
                NotificationCompat.Builder(c, CHANNEL).setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(tr("Coupure prévue notée"))
                    .setContentText(body).setStyle(NotificationCompat.BigTextStyle().bigText(body)).setAutoCancel(true).build(),
            )
        } catch (_: SecurityException) {
        }
    }

    /** One sentence for the morning briefing: the cuts of today and tomorrow, or null. */
    fun briefingLine(c: Context): String? = outagesBriefingLine(upcoming(c), LocalDate.now())
}

/** "Il y a une coupure de courant prévue ?", "note une coupure d'eau jeudi de 8 h à 12 h", "oublie la coupure de jeudi". */
object OutagesTool : Tool {
    override val name = "coupures_prevues"
    override val description =
        "Coupures d’électricité, d’eau ou de gaz prévues au domicile (travaux). Jarvis note tout seul celles annoncées par SMS, mail ou " +
            "notification (Enedis, GRDF, le service des eaux, la mairie), avec l’accès aux notifications, rappelle la veille au soir quoi " +
            "préparer et les dit dans le briefing du matin. action « list » (défaut) : les coupures notées. « add » : noter une coupure " +
            "annoncée autrement (courrier, affiche, voisin) : type (electricite, eau, gaz), date (yyyy-MM-dd), from et to (HH:mm) ; ou " +
            "text : le texte de l’avis tel quel. « remove » : oublier une coupure (date et/ou type ; « tout »)."
    override val parameters = objectSchema {
        string("action", "list, add ou remove.")
        string("type", "electricite, eau ou gaz.")
        string("date", "Le jour, yyyy-MM-dd.")
        string("from", "L’heure de début, HH:mm (si connue).")
        string("to", "L’heure de fin, HH:mm (si connue).")
        string("text", "Pour add : le texte de l’avis de coupure, à lire à la place des autres champs.")
    }

    private const val HOW =
        "Je note seul les coupures annoncées par SMS, mail ou notification (Enedis, GRDF, le service des eaux, la mairie) quand j’ai " +
            "l’accès aux notifications. Pour l’électricité, Enedis publie aussi ses coupures pour travaux par adresse sur enedis.fr " +
            "(« Info coupure ») et peut les envoyer par SMS ou mail : il suffit de s’y inscrire avec son adresse."

    private fun time(text: String): LocalTime? = text.trim().takeIf { it.isNotEmpty() }?.let {
        try { LocalTime.parse(it.replace('h', ':').let { t -> if (t.endsWith(":")) t + "00" else t }.padStart(5, '0')) } catch (_: Exception) { null }
    }

    override suspend fun run(args: JsonObject, ctx: JarvisContainer): String = withContext(Dispatchers.IO) {
        val c = ctx.appContext
        val today = LocalDate.now()
        val kind = OutageKind.of(args.stringArg("type"))
        val date = args.stringArg("date").trim().takeIf { it.isNotEmpty() }?.let { try { LocalDate.parse(it) } catch (_: Exception) { null } }
        when (args.stringArg("action").trim().lowercase()) {
            "add" -> {
                val text = args.stringArg("text").trim().take(1000)
                val parsed = if (text.isNotEmpty()) parseOutage(text, today, explicit = true) else null
                val o = when {
                    parsed != null -> parsed.copy(
                        kind = kind ?: parsed.kind, date = date ?: parsed.date,
                        start = time(args.stringArg("from")) ?: parsed.start, end = time(args.stringArg("to")) ?: parsed.end,
                    )
                    kind != null && date != null -> Outage(kind, date, time(args.stringArg("from")), time(args.stringArg("to")))
                    text.isNotEmpty() -> return@withContext "Je ne trouve pas dans ce texte quelle coupure, ni quel jour : dites le type (électricité, eau ou gaz) et la date."
                    else -> return@withContext "Dites ce qui sera coupé (électricité, eau ou gaz) et quel jour."
                }
                if (o.date.isBefore(today)) return@withContext "Ce jour est passé."
                val kept = Outages.note(c, o) ?: return@withContext "C’est déjà noté : ${outageLine(o, today)}."
                "C’est noté : ${outageLine(kept, today)}." +
                    (if (kept.reminderId != null) " Je vous le rappelle ${if (kept.date == today) "une heure avant" else "la veille au soir"}, avec quoi préparer." else "") +
                    " Dites-le simplement."
            }
            "remove" -> {
                val all = normalize(args.stringArg("text") + " " + args.stringArg("type")).split(' ').any { it in setOf("tout", "toutes", "tous", "all") }
                if (!all && kind == null && date == null) return@withContext "Dites quelle coupure oublier (le jour ou le type), ou « toutes »."
                val gone = Outages.forget(c) { all || ((kind == null || it.kind == kind) && (date == null || it.date == date)) }
                if (gone.isEmpty()) {
                    "Aucune coupure notée ne correspond. ${outagesWords(Outages.upcoming(c), today)}"
                } else {
                    "C’est oublié : ${gone.joinToString(" ; ") { outageLine(it, today) }}."
                }
            }
            else -> {
                val next = Outages.upcoming(c, today).filter { kind == null || it.kind == kind }
                if (next.isEmpty()) "Aucune coupure prévue notée. $HOW" else outagesWords(next, today) + " Dites-le simplement."
            }
        }
    }
}
