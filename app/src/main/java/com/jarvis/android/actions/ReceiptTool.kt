package com.jarvis.android.actions

import android.net.Uri
import com.jarvis.android.JarvisContainer
import com.jarvis.android.expenses.formatCents
import com.jarvis.android.expenses.normalizeCategory
import com.jarvis.android.photos.hasPhotoPermission
import com.jarvis.android.photos.queryPhotos
import com.jarvis.android.receipts.ReceiptCapture
import com.jarvis.android.receipts.ReceiptCaptureActivity
import com.jarvis.android.receipts.guessCategory
import com.jarvis.android.receipts.parseReceipt
import com.jarvis.android.receipts.readReceipt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/** "Scanne ce ticket": photo of a receipt, read on the phone, noted as an expense (see receipts/Receipt.kt). */
object ReceiptTool : Tool {
    override val name = "receipt"
    override val description =
        "Scanner un ticket de caisse et le noter en dépense : « scanne ce ticket », « ajoute ce ticket à mes dépenses », « lis le ticket " +
            "que je viens de photographier » (source last_photo). L'appareil photo s'ouvre, l'utilisateur prend le ticket, le total, le magasin " +
            "et la date sont lus sur le téléphone et la dépense est notée (catégorie devinée d'après le magasin, ou celle que vous donnez). " +
            "Si le total est faux, « annule la dernière dépense » (expenses) l'efface."
    override val parameters = objectSchema {
        string("source", "'camera' (défaut : prendre la photo maintenant) ou 'last_photo' (la dernière photo prise, moins de 30 minutes).")
        string("category", "Catégorie de la dépense si l'utilisateur la dit (facultatif).")
    }

    override suspend fun run(args: JsonObject, ctx: JarvisContainer): String {
        val context = ctx.appContext
        val zone = ZoneId.systemDefault()
        val uri: Uri = if (args.stringArg("source").trim().lowercase() == "last_photo") {
            if (!hasPhotoPermission(context)) return "Je n'ai pas accès aux photos : autorisez-le dans les réglages de Jarvis (carte Photos), ou dites « scanne ce ticket » pour le photographier."
            val now = System.currentTimeMillis()
            withContext(Dispatchers.IO) { queryPhotos(context, now - 30 * 60_000L, now + 60_000L, "", limit = 1) }.firstOrNull()?.uri
                ?: return "Aucune photo prise dans la dernière demi-heure : dites « scanne ce ticket » pour le photographier."
        } else {
            val pending = CompletableDeferred<Uri?>()
            ReceiptCapture.pending?.complete(null)
            ReceiptCapture.pending = pending
            try {
                context.startActivity(ReceiptCaptureActivity.intent(context))
            } catch (_: Exception) {
                return "Impossible d'ouvrir l'appareil photo."
            }
            withTimeoutOrNull(3 * 60_000L) { pending.await() }
                ?: return "Pas de photo du ticket (appareil photo fermé, autorisation refusée ou délai de 3 minutes dépassé)."
        }
        val rows = withContext(Dispatchers.Default) { readReceipt(context, uri) }
        if (uri.authority?.endsWith(".fileprovider") == true) File(context.cacheDir, "receipts").listFiles()?.forEach { it.delete() }
        if (rows.isNullOrEmpty()) return "Je n'arrive pas à lire le ticket : reprenez la photo bien à plat, nette et éclairée."
        val today = LocalDate.now(zone)
        val info = parseReceipt(rows, today)
        val total = info.totalCents
            ?: return "Je n'ai pas trouvé le total sur le ticket. Début du texte lu : " + rows.take(8).joinToString(" / ").take(300) +
                ". Dites le montant et je le note (expenses)."
        val category = normalizeCategory(args.stringArg("category").ifBlank { guessCategory(rows) })
        val at = info.date?.takeIf { it != today }?.atTime(12, 0)?.atZone(zone)?.toInstant()?.toEpochMilli() ?: System.currentTimeMillis()
        if (!ctx.expenseStore.add(total, category, info.merchant.orEmpty(), at)) return "Impossible d'enregistrer la dépense."
        val shop = info.merchant?.let { " chez $it" }.orEmpty()
        val day = info.date?.takeIf { it != today }?.let { " le ${it.dayOfMonth}/${it.monthValue.toString().padStart(2, '0')}" }.orEmpty()
        return "Ticket lu : ${formatCents(total)}$shop$day, noté en « $category ». Si ce n'est pas le bon montant, dites « annule la dernière dépense »."
    }
}
