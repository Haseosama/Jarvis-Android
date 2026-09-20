package com.jarvis.android.actions

import com.jarvis.android.JarvisContainer
import com.jarvis.android.watch.KIND_CRYPTO
import com.jarvis.android.watch.KIND_MEMORY
import com.jarvis.android.watch.KIND_SITE
import com.jarvis.android.watch.KIND_TEMPERATURE
import com.jarvis.android.watch.Watch
import com.jarvis.android.watch.WatchScheduler
import com.jarvis.android.watch.evaluateWatch
import com.jarvis.android.watch.normaliseSite
import com.jarvis.android.watch.sampleWatch
import com.jarvis.android.watch.title
import com.jarvis.android.watch.validCoinId
import com.jarvis.android.watch.watchStore
import kotlinx.serialization.json.JsonObject
import java.util.Locale

/** Watches something in the background (a crypto price, a website, the battery temperature, the free memory) and alerts by notification. */
object WatchTool : Tool {
    override val name = "watch"
    override val description =
        "Surveiller quelque chose en arrière-plan et prévenir l’utilisateur par notification (vérification environ toutes les 15 minutes). " +
            "action=add avec kind : crypto (target = identifiant CoinGecko comme bitcoin ou ethereum, threshold = prix en euros, direction above ou below), " +
            "site (target = adresse ; alerte quand le site ne répond plus, et quand il revient), temperature (batterie, threshold en °C, direction above), " +
            "memory (mémoire libre, threshold en Mo, direction below). action=list ; remove (id) ; check (mesure tout de suite et donne les valeurs). " +
            "La batterie faible et le stockage plein sont déjà surveillés par les vérifications de fond des réglages."
    override val parameters = objectSchema(required = listOf("action")) {
        string("action", "add, list, remove ou check.")
        string("kind", "crypto, site, temperature ou memory (pour add).")
        string("target", "Identifiant de la crypto (bitcoin…) ou adresse du site.")
        string("threshold", "Seuil : prix en €, °C ou Mo.")
        string("direction", "above (alerter au-dessus du seuil) ou below (en dessous).")
        string("label", "Nom court affiché dans l’alerte (facultatif).")
        integer("id", "Numéro de la surveillance à supprimer.")
    }

    override suspend fun run(args: JsonObject, ctx: JarvisContainer): String {
        val context = ctx.appContext
        val store = watchStore(context)
        return when (val action = args.stringArg("action").trim().lowercase(Locale.ROOT)) {
            "add" -> {
                val kind = args.stringArg("kind").trim().lowercase(Locale.ROOT)
                val threshold = args.stringArg("threshold").replace(',', '.').trim().toDoubleOrNull()
                val above = args.stringArg("direction").trim().lowercase(Locale.ROOT).let { it != "below" && it != "en dessous" }
                val label = args.stringArg("label").trim().take(60)
                val watch = when (kind) {
                    KIND_CRYPTO -> {
                        val coin = args.stringArg("target").trim().lowercase(Locale.ROOT)
                        if (!validCoinId(coin)) return "Identifiant de crypto invalide : utilisez l’identifiant CoinGecko (bitcoin, ethereum, solana…)."
                        if (threshold == null || threshold <= 0) return "Indiquez le prix seuil en euros."
                        Watch(0, KIND_CRYPTO, coin, threshold, above, label)
                    }
                    KIND_SITE -> {
                        val site = normaliseSite(args.stringArg("target")) ?: return "Adresse de site invalide."
                        Watch(0, KIND_SITE, site, 0.0, true, label)
                    }
                    KIND_TEMPERATURE -> Watch(0, KIND_TEMPERATURE, "", threshold ?: 42.0, true, label)
                    KIND_MEMORY -> Watch(0, KIND_MEMORY, "", threshold ?: 400.0, false, label)
                    else -> return "Type inconnu. Types possibles : crypto, site, temperature, memory."
                }
                val created = try { store.add(watch) } catch (e: IllegalArgumentException) { return e.message ?: "Impossible d’ajouter la surveillance." }
                WatchScheduler.sync(context)
                "Surveillance n° ${created.id} ajoutée : ${describe(created)}. Je préviens par notification (vérification environ toutes les 15 minutes)."
            }
            "list" -> {
                val list = store.load()
                if (list.isEmpty()) "Aucune surveillance active." else list.joinToString("\n") { "n° ${it.id} : ${describe(it)}" + (it.lastValue?.let { v -> " — dernière valeur : ${format(v)}" } ?: "") }
            }
            "remove" -> {
                val id = args.intArg("id", -1)
                if (store.remove(id)) { WatchScheduler.sync(context); "Surveillance n° $id supprimée." } else "Aucune surveillance ne porte le numéro $id."
            }
            "check" -> {
                val list = store.load()
                if (list.isEmpty()) return "Aucune surveillance active."
                val now = System.currentTimeMillis()
                val lines = mutableListOf<String>()
                val updated = list.map { w ->
                    val value = sampleWatch(context, ctx.http, w)
                    val result = evaluateWatch(w, value, now)
                    lines += "n° ${w.id} ${w.title()} : " + (value?.let { if (w.kind == KIND_SITE) (if (it > 0.5) "répond" else "ne répond pas") else format(it) } ?: "mesure impossible") + (result.alert?.let { " → alerte : $it" } ?: "")
                    result.watch
                }
                store.replace(updated)
                lines.joinToString("\n")
            }
            else -> "Action inconnue : $action."
        }
    }

    private fun format(v: Double) = if (v >= 100) String.format(Locale.FRANCE, "%,.0f", v) else String.format(Locale.FRANCE, "%.2f", v)

    private fun describe(w: Watch): String = when (w.kind) {
        KIND_CRYPTO -> "${w.title()} ${if (w.above) "au-dessus de" else "en dessous de"} ${format(w.threshold)} €"
        KIND_SITE -> "site ${w.target} (alerte s’il ne répond plus)"
        KIND_TEMPERATURE -> "température de la batterie au-dessus de ${format(w.threshold)} °C"
        else -> "mémoire libre en dessous de ${format(w.threshold)} Mo"
    }
}
