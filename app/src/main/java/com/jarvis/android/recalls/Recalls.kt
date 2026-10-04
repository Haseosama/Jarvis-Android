package com.jarvis.android.recalls

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.jarvis.android.JarvisApp
import com.jarvis.android.JarvisContainer
import com.jarvis.android.tool.Tool
import com.jarvis.android.tool.objectSchema
import com.jarvis.android.tool.stringArg
import com.jarvis.android.i18n.tr
import com.jarvis.android.text.normalize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/*
 * Product recalls in France, from RappelConso (the State's recall site, as open data on data.economie.gouv.fr: free, no key). "Is there a
 * recall on this cheese?" by name, brand or barcode; the latest recalls; and a watch on the categories or brands the user follows
 * ("alimentation", "jouets", "Lactalis"), which notifies each new recall that falls under one of them, checked every six hours.
 */

internal object Recalls {
    private const val DATASET = "https://data.economie.gouv.fr/api/explore/v2.1/catalog/datasets/rappelconso-v2-gtin-espaces/records"

    private fun get(ctx: JarvisContainer, where: String?, limit: Int): String? = try {
        val url = DATASET.toHttpUrl().newBuilder().addQueryParameter("limit", limit.toString()).addQueryParameter("order_by", "date_publication desc")
            .apply { if (where != null) addQueryParameter("where", where) }.build()
        ctx.http.newCall(Request.Builder().url(url).header("User-Agent", "Jarvis-Android").build()).execute().use { if (it.isSuccessful) it.body?.string() else null }
    } catch (_: Exception) {
        null
    }

    /** The recalls naming these words (a barcode is found the same way), newest first; null if RappelConso did not answer. */
    suspend fun search(ctx: JarvisContainer, term: String): List<Recall>? = withContext(Dispatchers.IO) {
        get(ctx, searchWhere(term), 20)?.let { parseRecalls(it) }
    }

    /** The latest recalls, newest first; null if RappelConso did not answer. */
    suspend fun latest(ctx: JarvisContainer, count: Int = 60): List<Recall>? = withContext(Dispatchers.IO) { get(ctx, null, count)?.let { parseRecalls(it) } }

    private fun prefs(c: Context) = c.getSharedPreferences("recalls", Context.MODE_PRIVATE)
    fun followed(c: Context): List<String> = prefs(c).getString("followed", "").orEmpty().split('\n').filter { it.isNotBlank() }

    /** Saves the followed words and starts (or stops, when none is left) the six-hourly look. The watch starts from now: older recalls are not told. */
    fun setFollowed(c: Context, words: List<String>) {
        val p = prefs(c)
        val before = followed(c)
        val edit = p.edit().putString("followed", words.joinToString("\n"))
        if (before.isEmpty() && words.isNotEmpty()) edit.putLong("since", System.currentTimeMillis())
        edit.apply()
        val wm = WorkManager.getInstance(c)
        if (words.isNotEmpty()) wm.enqueueUniquePeriodicWork(
            "recalls", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<RecallsWorker>(6, TimeUnit.HOURS).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build(),
        ) else wm.cancelUniqueWork("recalls")
    }

    /** One look: the new recalls under a followed word, each told once, in one notification per recall (the sheet opens on a tap). */
    suspend fun check(c: Context) {
        val words = followed(c)
        if (words.isEmpty()) return
        val p = prefs(c)
        val ctx = (c.applicationContext as JarvisApp).container
        val all = latest(ctx) ?: return
        val told = p.getStringSet("told", emptySet()).orEmpty()
        // a day of margin: a sheet is sometimes published with the morning's date in the afternoon
        val fresh = newRecallsFor(all, words, told, p.getLong("since", 0L) - 86_400_000L)
        if (fresh.isEmpty()) return
        p.edit().putStringSet("told", (told + fresh.map { it.id }).toList().takeLast(300).toSet()).apply()
        c.getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("jarvis_recalls", tr("Rappels de produits"), NotificationManager.IMPORTANCE_DEFAULT))
        for (r in fresh.take(5)) {
            val text = recallLine(r, ZoneId.systemDefault())
            val open = r.link.takeIf { it.startsWith("https://") }?.let {
                PendingIntent.getActivity(c, r.id.hashCode(), Intent(Intent.ACTION_VIEW, Uri.parse(it)), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            }
            try {
                NotificationManagerCompat.from(c).notify(
                    "recall", r.id.hashCode(),
                    NotificationCompat.Builder(c, "jarvis_recalls").setSmallIcon(android.R.drawable.ic_dialog_alert).setContentTitle("Rappel : ${r.product.ifEmpty { r.brand }}")
                        .setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text)).setAutoCancel(true).apply { if (open != null) setContentIntent(open) }.build(),
                )
            } catch (_: SecurityException) {
            }
        }
    }
}

class RecallsWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result { try { Recalls.check(applicationContext) } catch (_: Exception) {}; return Result.success() }
}

/** "Est-ce que le comté de chez Lidl est rappelé ?", "les derniers rappels de jouets", "préviens-moi des rappels alimentaires". */
object RecallsTool : Tool {
    override val name = "rappel_produit"
    override val description =
        "Rappels de produits en France (RappelConso, le site officiel de l’État). action « check » (défaut) : est-ce qu’un produit, une " +
            "marque ou un code-barres (term) fait l’objet d’un rappel : produit, marque, date, motif, risque, que faire, où il était vendu. " +
            "« recent » : les derniers rappels, éventuellement d’une catégorie (term : alimentation, jouets, bébés, automobiles, beauté…). " +
            "« follow » (term : une catégorie ou une marque) : une notification à chaque nouveau rappel qui la touche (vérifié toutes les " +
            "six heures) ; « unfollow » (term) ; « list » : ce qui est suivi."
    override val parameters = objectSchema {
        string("action", "check, recent, follow, unfollow ou list.")
        string("term", "Le produit, la marque, le code-barres ou la catégorie.")
    }

    override suspend fun run(args: JsonObject, ctx: JarvisContainer): String {
        val c = ctx.appContext
        val term = args.stringArg("term").trim().take(100)
        val zone = ZoneId.systemDefault()
        when (args.stringArg("action").trim().lowercase()) {
            "follow" -> {
                if (term.isEmpty()) return "Dites quelle catégorie ou quelle marque suivre."
                val now = Recalls.followed(c)
                if (now.any { followKey(it) == followKey(term) }) return "Je suis déjà les rappels « $term »."
                if (now.size >= 20) return "Vous suivez déjà 20 catégories ou marques : retirez-en une d’abord."
                Recalls.setFollowed(c, now + term)
                return "Je vous préviens à chaque nouveau rappel « $term » (vérifié toutes les six heures). Suivis : ${(now + term).joinToString(", ")}."
            }
            "unfollow" -> {
                val now = Recalls.followed(c)
                val left = if (term.isEmpty() || normalize(term) in setOf("tout", "tous", "all")) emptyList() else now.filterNot { followKey(it) == followKey(term) }
                if (left.size == now.size) return "Je ne suivais pas « $term ». Suivis : ${now.joinToString(", ").ifEmpty { "rien" }}."
                Recalls.setFollowed(c, left)
                return if (left.isEmpty()) "Je ne surveille plus les rappels de produits." else "C’est retiré. Suivis : ${left.joinToString(", ")}."
            }
            "list" -> return Recalls.followed(c).let { if (it.isEmpty()) "Aucune catégorie ni marque suivie pour les rappels." else "Rappels suivis : ${it.joinToString(", ")}." }
            "recent" -> {
                val all = Recalls.latest(ctx, if (term.isEmpty()) 10 else 100) ?: return "RappelConso ne répond pas pour l’instant."
                return recentWords(if (term.isEmpty()) all else all.filter { recallMatches(it, term) }, term, zone) + " Dites-le simplement."
            }
        }
        if (term.isEmpty()) return "Dites quel produit, quelle marque ou quel code-barres chercher."
        val found = Recalls.search(ctx, term) ?: return "RappelConso ne répond pas pour l’instant."
        return checkWords(term, found, zone) + " Dites-le simplement."
    }
}
