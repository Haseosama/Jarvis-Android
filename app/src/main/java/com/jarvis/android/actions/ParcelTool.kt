package com.jarvis.android.actions

import android.content.Intent
import android.net.Uri
import com.jarvis.android.JarvisContainer
import com.jarvis.android.notifications.JarvisNotificationListener
import com.jarvis.android.parcels.Carrier
import com.jarvis.android.parcels.Parcel
import com.jarvis.android.parcels.ParcelTracking
import com.jarvis.android.parcels.cleanNumber
import com.jarvis.android.parcels.detectCarriers
import com.jarvis.android.parcels.eventWhen
import com.jarvis.android.parcels.findTrackingNumbers
import com.jarvis.android.parcels.trackingUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.time.ZoneId

/** Parcel tracking (see parcels/Parcels.kt). */
object ParcelTool : Tool {
    override val name = "parcel"
    override val description =
        "Suivi de colis. add (« suis mon colis 6A12345678901 », number, label facultatif : « les chaussures ») ; status (« où en est mon colis ? » : " +
            "tous, ou choice) ; open (ouvre la page de suivi du transporteur) ; remove (choice ou number) ; detect (« suis le colis du SMS » : cherche " +
            "les numéros de suivi dans les messages reçus). La Poste, Colissimo et Chronopost sont suivis automatiquement (clé La Poste dans les " +
            "réglages) avec une notification à chaque étape ; pour les autres, Jarvis ouvre la page du transporteur."
    override val parameters = objectSchema {
        string("action", "'status' (défaut), 'add', 'open', 'remove' ou 'detect'.")
        string("number", "Numéro de suivi.")
        string("label", "Pour add : ce que c'est, ex. « chaussures ».")
        integer("choice", "Numéro du colis dans la liste.")
    }

    internal fun line(i: Int, p: Parcel, zone: ZoneId): String {
        val name = p.label.ifBlank { p.number }
        val where = when {
            p.status.isNotBlank() -> "${p.status}${eventWhen(p.statusAt, zone).let { if (it.isEmpty()) "" else " ($it)" }}"
            p.carrier == Carrier.LAPOSTE -> "pas encore d'information"
            else -> "à suivre sur le site de ${p.carrier.label} (« ouvre le suivi »)"
        }
        return "${i + 1}) $name — ${p.carrier.label} : $where"
    }

    override suspend fun run(args: JsonObject, ctx: JarvisContainer): String = withContext(Dispatchers.IO) {
        val store = ctx.parcelStore
        val zone = ZoneId.systemDefault()
        val items = store.all()
        fun pick(): Parcel? {
            val i = args.intArg("choice", 0)
            val n = cleanNumber(args.stringArg("number"))
            return when {
                i in 1..items.size -> items[i - 1]
                n.isNotEmpty() -> items.firstOrNull { it.number == n }
                items.size == 1 -> items[0]
                else -> null
            }
        }
        when (args.stringArg("action").trim().lowercase().ifEmpty { "status" }) {
            "add" -> {
                val number = cleanNumber(args.stringArg("number"))
                val carriers = detectCarriers(number)
                if (carriers.isEmpty()) return@withContext "« ${number.take(40)} » ne ressemble pas à un numéro de suivi."
                if (items.any { it.number == number }) return@withContext "Ce colis est déjà suivi."
                val p = Parcel(number, carriers.first(), args.stringArg("label").trim().take(60), addedAt = System.currentTimeMillis())
                store.update { it + p }
                ParcelTracking.schedule(ctx.appContext)
                if (p.carrier != Carrier.LAPOSTE) return@withContext "Colis ${p.label.ifBlank { number }} noté (${p.carrier.label}). Je ne peux pas le suivre " +
                    "automatiquement chez ce transporteur : dites « ouvre le suivi » pour voir sa page."
                val first = ParcelTracking.fetchLaPoste(ctx, number)
                when (first) {
                    is ParcelTracking.Fetch.Ok -> {
                        store.update { list -> list.map { if (it.number == number) it.copy(status = first.status.label, statusAt = first.status.date, delivered = first.status.delivered) else it } }
                        "Colis suivi. Dernière étape : ${first.status.label} ${eventWhen(first.status.date, zone)}. Je vous préviendrai à chaque nouvelle étape."
                    }
                    ParcelTracking.Fetch.NoKey -> "Colis noté. Pour le suivi automatique, il faut une clé La Poste (gratuite) dans les réglages de Jarvis, carte Colis ; en attendant, « ouvre le suivi »."
                    ParcelTracking.Fetch.BadKey -> "Colis noté, mais La Poste refuse la clé enregistrée : vérifiez-la dans les réglages (carte Colis)."
                    ParcelTracking.Fetch.NotFound -> "Colis noté. La Poste ne le connaît pas encore (c'est courant juste après l'envoi) : je réessaierai toutes les trois heures."
                    ParcelTracking.Fetch.Failed -> "Colis noté. La Poste ne répond pas pour le moment : je réessaierai plus tard."
                }
            }
            "open" -> {
                val p = pick() ?: cleanNumber(args.stringArg("number")).takeIf { it.isNotEmpty() }?.let { n -> detectCarriers(n).firstOrNull()?.let { Parcel(n, it) } }
                    ?: return@withContext "Quel colis ? " + items.mapIndexed { i, p -> line(i, p, zone) }.joinToString(" ; ")
                try {
                    ctx.appContext.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(trackingUrl(p.carrier, p.number))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    "Page de suivi ${p.carrier.label} ouverte."
                } catch (_: Exception) {
                    "Aucun navigateur pour ouvrir la page de suivi."
                }
            }
            "remove", "delete" -> {
                val p = pick() ?: return@withContext "Quel colis ? " + items.mapIndexed { i, p -> line(i, p, zone) }.joinToString(" ; ")
                store.update { list -> list.filterNot { it.number == p.number } }
                "Colis ${p.label.ifBlank { p.number }} retiré du suivi."
            }
            "detect" -> {
                val found = JarvisNotificationListener.HISTORY.since(System.currentTimeMillis() - 7 * 24 * 60 * 60_000L)
                    .flatMap { findTrackingNumbers(it.title + " " + it.text) }.distinct().filter { n -> items.none { it.number == n } }
                if (found.isEmpty()) "Aucun numéro de suivi dans les messages reçus récemment (Jarvis ne voit que ceux arrivés depuis que l'accès aux notifications est actif)."
                else "Numéros de suivi vus dans vos messages : " + found.joinToString(", ") { "$it (${detectCarriers(it).first().label})" } + ". Demandez lequel suivre (add)."
            }
            else -> {
                if (items.isEmpty()) return@withContext "Aucun colis suivi. Dites « suis mon colis » avec le numéro."
                val changed = ParcelTracking.refreshAll(ctx)
                val now = store.all()
                "${now.size} colis suivi${if (now.size > 1) "s" else ""}" + (if (changed.isNotEmpty()) " (${changed.size} nouvelle${if (changed.size > 1) "s" else ""} étape${if (changed.size > 1) "s" else ""})" else "") +
                    " :\n" + now.mapIndexed { i, p -> line(i, p, zone) }.joinToString("\n")
            }
        }
    }
}
