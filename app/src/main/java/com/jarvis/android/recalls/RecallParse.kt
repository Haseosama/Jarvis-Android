package com.jarvis.android.recalls

import com.jarvis.android.offline.normalize
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * The reading of RappelConso (the State's site for product recalls, published as open data by data.economie.gouv.fr), kept free of
 * Android so it can be tested on its own: the records, the recalls a followed word catches, and the words said.
 */

/** A product recall: its sheet number, the product's name, brand, category, why, the risk, what to do, where it was sold, when, the sheet's link. */
internal data class Recall(
    val id: String,
    val product: String,
    val brand: String,
    val category: String,
    val subCategory: String,
    val reason: String,
    val risk: String,
    val advice: String,
    val sellers: String,
    val publishedMs: Long,
    val link: String,
)

private fun JsonObject.text(vararg keys: String): String =
    keys.firstNotNullOfOrNull { k -> (this[k] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } }.orEmpty()

/** "2026-09-30T08:12:00+00:00" or "2026-09-30", in milliseconds (0 if unreadable). */
internal fun recallDateMs(text: String): Long = try {
    OffsetDateTime.parse(text).toInstant().toEpochMilli()
} catch (_: Exception) {
    try {
        LocalDate.parse(text.take(10)).atStartOfDay(ZoneId.of("Europe/Paris")).toInstant().toEpochMilli()
    } catch (_: Exception) {
        0L
    }
}

/** The records of the API (`{"results":[…]}`), one per recall: the GTIN dataset repeats a recall once per barcode, so they are merged by sheet. */
internal fun parseRecalls(json: String): List<Recall> = try {
    ((Json.parseToJsonElement(json) as JsonObject)["results"] as JsonArray).mapNotNull { r ->
        val o = r as? JsonObject ?: return@mapNotNull null
        val product = o.text("libelle", "noms_des_modeles_ou_references", "modeles_ou_references")
        val brand = o.text("marque_produit")
        if (product.isEmpty() && brand.isEmpty()) return@mapNotNull null
        val date = o.text("date_publication")
        Recall(
            id = o.text("numero_fiche", "reference_fiche", "rappel_guid", "id").ifEmpty { "$brand|$product|$date" },
            product = product,
            brand = brand,
            category = o.text("categorie_produit", "categorie_de_produit"),
            subCategory = o.text("sous_categorie_produit", "sous_categorie_de_produit"),
            reason = o.text("motif_rappel", "motif_du_rappel"),
            risk = o.text("risques_encourus", "risques_encourus_par_le_consommateur"),
            advice = o.text("conduites_a_tenir_par_le_consommateur"),
            sellers = o.text("distributeurs"),
            publishedMs = recallDateMs(date),
            link = o.text("lien_vers_la_fiche_rappel"),
        )
    }.distinctBy { it.id }
} catch (_: Exception) {
    emptyList()
}

/** The words people use for RappelConso's categories ("alimentation", "bébés-enfants", "automobiles et moyens de déplacement"…). */
private val CATEGORY_WORDS = mapOf(
    "alimentaire" to "alimentation", "nourriture" to "alimentation", "aliments" to "alimentation", "bouffe" to "alimentation",
    "bebe" to "bebes", "bebes" to "bebes", "enfant" to "enfants", "jouet" to "jouets",
    "voiture" to "automobiles", "voitures" to "automobiles", "auto" to "automobiles", "velo" to "deplacement", "velos" to "deplacement",
    "trottinette" to "deplacement", "cosmetique" to "beaute", "cosmetiques" to "beaute", "maquillage" to "beaute", "hygiene" to "hygiene",
    "animal" to "animaux", "chien" to "animaux", "chat" to "animaux", "electromenager" to "electriques", "electrique" to "electriques",
    "vetement" to "vetements", "habits" to "vetements", "sport" to "sports", "maison" to "maison", "bricolage" to "outils",
    "telephone" to "communication", "jardin" to "jardins",
)

/** What a followed word ("alimentation", "Lactalis", "jouets") is matched against, folded: the category, sub-category, brand and product. */
internal fun followKey(word: String): String = normalize(word).let { w -> CATEGORY_WORDS[w] ?: CATEGORY_WORDS[w.removeSuffix("s")] ?: w }

/** Whether a recall falls under a followed word: each of its words begins a word of the recall's category, sub-category, brand or product. */
internal fun recallMatches(r: Recall, followed: String): Boolean {
    val key = followKey(followed)
    if (key.isEmpty()) return false
    val words = normalize("${r.category} ${r.subCategory} ${r.brand} ${r.product}").split(' ')
    return key.split(' ').all { k -> words.any { it.startsWith(k) } }
}

/** The recalls, among the latest, that are new since [sinceMs], not yet told, and that fall under one of the followed words. */
internal fun newRecallsFor(all: List<Recall>, followed: List<String>, told: Set<String>, sinceMs: Long): List<Recall> =
    all.filter { r -> r.id !in told && r.publishedMs >= sinceMs && followed.any { recallMatches(r, it) } }

/** ODSQL's full-text condition on the words asked, with any double quote taken out. */
internal fun searchWhere(term: String): String = "\"" + term.replace("\"", " ").trim() + "\""

private fun day(ms: Long, zone: ZoneId) = java.time.Instant.ofEpochMilli(ms).atZone(zone).format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.FRANCE))

private fun firstSentence(text: String, max: Int) = text.replace(Regex("\\s+"), " ").substringBefore(". ").take(max).trim().trimEnd('.')

/** One recall in a sentence: the product and brand, the category, when, why, the risk, what to do. */
internal fun recallLine(r: Recall, zone: ZoneId, detailed: Boolean = true): String {
    val what = listOf(r.product, r.brand.takeIf { b -> b.isNotEmpty() && !normalize(r.product).contains(normalize(b)) }?.let { "de $it" })
        .filterNot { it.isNullOrEmpty() }.joinToString(" ")
    val parts = ArrayList<String>()
    parts += what + (if (r.category.isNotEmpty()) " (${r.category.lowercase(Locale.FRANCE)})" else "") + (if (r.publishedMs > 0) ", rappelé le ${day(r.publishedMs, zone)}" else "")
    if (r.reason.isNotEmpty()) parts += "motif : " + firstSentence(r.reason, 160)
    if (detailed && r.risk.isNotEmpty()) parts += "risque : " + firstSentence(r.risk, 120)
    if (detailed && r.advice.isNotEmpty()) parts += "à faire : " + firstSentence(r.advice, 160)
    if (detailed && r.sellers.isNotEmpty()) parts += "vendu chez " + firstSentence(r.sellers, 100)
    return parts.joinToString(" ; ")
}

/** The answer to "is this product recalled?": the recalls found, newest first, or that there is none. */
internal fun checkWords(term: String, found: List<Recall>, zone: ZoneId): String {
    if (found.isEmpty()) return "Aucun rappel trouvé sur RappelConso pour « $term »."
    val top = found.sortedByDescending { it.publishedMs }.take(3)
    val head = if (found.size == 1) "Oui, un rappel pour « $term » : " else "Oui, ${found.size} rappels pour « $term » (les plus récents) : "
    return head + top.joinToString(". ") { recallLine(it, zone) } + "."
}

/** The latest recalls, optionally in one category, briefly. */
internal fun recentWords(found: List<Recall>, category: String, zone: ZoneId): String {
    val where = if (category.isNotBlank()) " ($category)" else ""
    if (found.isEmpty()) return "Aucun rappel récent$where sur RappelConso."
    return "Derniers rappels$where : " + found.take(5).joinToString(". ") { recallLine(it, zone, detailed = false) } + "."
}
