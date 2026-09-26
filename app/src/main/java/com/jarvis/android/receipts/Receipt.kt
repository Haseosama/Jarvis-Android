package com.jarvis.android.receipts

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.jarvis.android.offline.normalize
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.time.LocalDate
import kotlin.coroutines.resume
import kotlin.math.abs

/*
 * "Scanne ce ticket": a photo of a receipt, read on the phone by ML Kit, gives the total, the shop and the day, which
 * become an expense. Nothing is sent anywhere; the photo taken for it is deleted once read.
 */

internal data class ReceiptInfo(val totalCents: Long?, val merchant: String?, val date: LocalDate?)

/** A line of text as ML Kit found it, with its place on the photo. */
internal data class OcrLine(val text: String, val left: Int, val top: Int, val bottom: Int)

/**
 * The lines put back into rows as printed: ML Kit reads a receipt column by column, so "TOTAL" and its amount, on the
 * same printed row but in two columns, come out far apart. Lines whose middles are within half a line height are one row.
 */
internal fun rowsOf(lines: List<OcrLine>): List<String> {
    if (lines.isEmpty()) return emptyList()
    val sorted = lines.sortedBy { (it.top + it.bottom) / 2 }
    val rows = mutableListOf<MutableList<OcrLine>>()
    for (l in sorted) {
        val mid = (l.top + l.bottom) / 2
        val row = rows.lastOrNull()
        val rowMid = row?.let { r -> r.sumOf { (it.top + it.bottom) / 2 } / r.size }
        val height = (l.bottom - l.top).coerceAtLeast(1)
        if (row != null && rowMid != null && abs(mid - rowMid) <= height / 2) row += l else rows += mutableListOf(l)
    }
    return rows.map { r -> r.sortedBy { it.left }.joinToString(" ") { it.text.trim() } }
}

private val AMOUNT = Regex("(?<![\\d,.])(\\d{1,4}(?:[ .]\\d{3})*)[,.](\\d{2})(?![\\d])")
private val DATE = Regex("(?<!\\d)(\\d{1,2})[/.-](\\d{1,2})[/.-](\\d{2}|\\d{4})(?!\\d)")
private val EXCLUDED = listOf("sous total", "sous-total", "s/total", "ss total", "stotal", "tva", " ht", "remise", "economie", "rendu", "rendre", "fidelite", "cagnotte", "nombre", "articles", "qte")
private val TOTAL_WORDS = listOf(
    listOf("net a payer", "a payer", "montant du", "montant a payer"),
    listOf("total ttc", "total eur", "total €", "total"),
    listOf("carte bancaire", "cb", "carte", "especes", "montant"),
)
private val NOT_MERCHANT = listOf("ticket", "bienvenue", "caisse", "facture", "merci", "tel", "siret", "www", "copie client", "duplicata")

internal fun amountsIn(line: String): List<Long> = AMOUNT.findAll(line).mapNotNull { m ->
    val units = m.groupValues[1].replace(" ", "").replace(".", "").toLongOrNull() ?: return@mapNotNull null
    (units * 100 + m.groupValues[2].toLong()).takeIf { it in 1..10_000_00 }
}.toList()

/** The total, the shop and the day of a receipt from its rows of text. */
internal fun parseReceipt(rows: List<String>, today: LocalDate): ReceiptInfo {
    val norm = rows.map { " " + normalize(it) + " " }
    var total: Long? = null
    loop@ for (words in TOTAL_WORDS) {
        val found = mutableListOf<Long>()
        for ((i, n) in norm.withIndex()) {
            if (words.none { w -> Regex("(?<![a-z])" + Regex.escape(normalize(w).ifEmpty { w }) + "(?![a-z])").containsMatchIn(n) }) continue
            if (EXCLUDED.any { n.contains(it) }) continue
            // The amount on the row itself, or alone on one of the next two rows.
            val here = amountsIn(rows[i])
            val amounts = here.ifEmpty {
                (i + 1..minOf(i + 2, rows.lastIndex)).map { rows[it] }
                    .firstOrNull { r -> amountsIn(r).isNotEmpty() && r.count { it.isLetter() } <= 3 }?.let { amountsIn(it) }.orEmpty()
            }
            amounts.maxOrNull()?.let { found += it }
        }
        if (found.isNotEmpty()) { total = found.max(); break@loop }
    }
    if (total == null) {
        val all = rows.flatMap { amountsIn(it) }
        // With no word to go by: the largest amount printed twice (the total and the payment), else the largest.
        total = all.groupingBy { it }.eachCount().filter { it.value >= 2 }.keys.maxOrNull() ?: all.maxOrNull()
    }
    val merchant = rows.take(6).map { it.trim() }.firstOrNull { r ->
        val n = normalize(r)
        r.count { it.isLetter() } >= 3 && r.count { it.isDigit() } <= 2 && NOT_MERCHANT.none { n.contains(it) }
    }?.let { m -> m.lowercase().split(' ').filter { it.isNotBlank() }.joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }.take(40) }
    val date = rows.firstNotNullOfOrNull { r ->
        DATE.find(r)?.let { m ->
            val y = m.groupValues[3].toInt().let { if (it < 100) 2000 + it else it }
            runCatching { LocalDate.of(y, m.groupValues[2].toInt(), m.groupValues[1].toInt()) }.getOrNull()
        }
    }?.takeIf { !it.isAfter(today) && it.isAfter(today.minusDays(90)) }
    return ReceiptInfo(total, merchant, date)
}

private val CATEGORY_HINTS = listOf(
    "courses" to listOf("carrefour", "leclerc", "auchan", "lidl", "intermarche", "super u", "hyper u", "casino", "monoprix", "franprix", "aldi", "netto", "spar", "biocoop", "picard", "grand frais", "u express", "supermarche"),
    "essence" to listOf("total energies", "totalenergies", "esso", "shell", "avia", "station", "carburant", "gazole", "sp95", "sp98"),
    "restaurant" to listOf("restaurant", "brasserie", "cafe", "pizzeria", "mcdonald", "burger", "kfc", "quick", "sushi", "creperie", "bistrot"),
    "santé" to listOf("pharmacie", "parapharmacie"),
    "boulangerie" to listOf("boulangerie", "patisserie"),
    "bricolage" to listOf("leroy merlin", "castorama", "brico"),
)

/** A category guessed from the shop's name and the receipt's words, or null. */
internal fun guessCategory(rows: List<String>): String? {
    val text = " " + normalize(rows.take(12).joinToString(" ")) + " "
    return CATEGORY_HINTS.firstOrNull { (_, words) -> words.any { text.contains(normalize(it)) } }?.first
}

private suspend fun <T> Task<T>.awaitOrNull(): T? = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { if (cont.isActive) cont.resume(it) }
    addOnFailureListener { if (cont.isActive) cont.resume(null) }
    addOnCanceledListener { if (cont.isActive) cont.resume(null) }
}

/** The rows of text of the photo at [uri], read on the phone; null when it cannot be read. */
internal suspend fun readReceipt(context: Context, uri: Uri): List<String>? {
    val image = try { InputImage.fromFilePath(context, uri) } catch (_: Exception) { return null }
    val client = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    try {
        val text = client.process(image).awaitOrNull() ?: return null
        val lines = text.textBlocks.flatMap { it.lines }.mapNotNull { l -> l.boundingBox?.let { b -> OcrLine(l.text, b.left, b.top, b.bottom) } }
        return rowsOf(lines)
    } finally {
        client.close()
    }
}

/** Hands the photo taken by [ReceiptCaptureActivity] back to the tool waiting for it. */
internal object ReceiptCapture {
    @Volatile var pending: CompletableDeferred<Uri?>? = null
}

/**
 * The screen-less activity that asks the camera app for one photo of the receipt (the camera permission first, if it was
 * never given), and hands it back. It closes itself when done.
 */
class ReceiptCaptureActivity : ComponentActivity() {
    private var target: Uri? = null

    private val take = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        ReceiptCapture.pending?.complete(if (ok) target else null)
        finish()
    }

    private val askCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchCamera() else { ReceiptCapture.pending?.complete(null); finish() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        target = savedInstanceState?.getString("target")?.let(Uri::parse)
        if (savedInstanceState != null) return
        // The app declares the camera permission, so Android wants it granted even to ask the camera app for a photo.
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) launchCamera()
        else askCamera.launch(Manifest.permission.CAMERA)
    }

    private fun launchCamera() {
        val dir = File(cacheDir, "receipts").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", File(dir, "receipt-${System.currentTimeMillis()}.jpg"))
        target = uri
        try {
            take.launch(uri)
        } catch (_: Exception) {
            ReceiptCapture.pending?.complete(null)
            finish()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        target?.let { outState.putString("target", it.toString()) }
    }

    companion object {
        fun intent(context: Context) = Intent(context, ReceiptCaptureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
