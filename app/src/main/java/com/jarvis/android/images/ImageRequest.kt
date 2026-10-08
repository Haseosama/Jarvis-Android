package com.jarvis.android.images

import com.jarvis.android.text.normalize
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * What image_pc sends to the PC, and what it refuses before sending: plain Kotlin, tested without a phone (ImagePcToolTest).
 */

/** create, install or status, from what the model wrote. */
internal fun imageAction(raw: String): String = when (normalize(raw)) {
    "installer", "installe", "install", "installation" -> "install"
    "etat", "status", "statut", "verifier" -> "status"
    else -> "create"
}

/** What goes to the PC: the description, the size for the format, and adult only when allowed. */
internal fun imageRequest(prompt: String, format: String, avoid: String, seed: Int, adult: Boolean): JsonObject {
    val (w, h) = when (normalize(format)) {
        "paysage", "landscape", "horizontal" -> 1216 to 832
        "carre", "square" -> 1024 to 1024
        else -> 832 to 1216
    }
    return buildJsonObject {
        put("prompt", prompt.trim().take(1500))
        if (avoid.isNotBlank()) put("negative", avoid.trim().take(600))
        put("width", w)
        put("height", h)
        if (seed >= 0) put("seed", seed)
        put("adult", adult)
    }
}

// The same lists as on the PC (electron/imageGen.cjs), accents folded by normalize().
private val MINOR_WORDS = listOf(
    "enfant", "enfants", "gamin", "gamine", "gosse", "mineur", "mineure", "mineurs", "mineures", "bebe", "fillette", "petite fille",
    "petit garcon", "ado", "ados", "adolescent", "adolescente", "adolescents", "collegien", "collegienne", "lyceen", "lyceenne",
    "ecoliere", "ecolier", "child", "children", "kid", "kids", "minor", "minors", "underage", "under age", "teen", "teens", "teenager",
    "teenage", "preteen", "young girl", "young boy", "little girl", "little boy", "schoolgirl", "schoolboy", "toddler", "baby", "infant",
    "loli", "lolita", "shota", "jailbait", "juvenile", "pubescent", "prepubescent",
)
private val EXPLICIT_WORDS = listOf(
    "nu", "nue", "nus", "nues", "nudite", "sexe", "sexuel", "sexuelle", "erotique", "porno", "seins", "topless", "nude", "naked",
    "nudity", "nsfw", "sex", "sexual", "erotic", "porn", "explicit", "breasts", "nipples", "lingerie", "hentai",
)
private val UNDERAGE_AGE = Regex("""\b(?:[0-9]|1[0-7])\s*(?:ans|an|years?\s*old|y\.?o\.?|yrs?)\b""", RegexOption.IGNORE_CASE)

/** Why this request is refused before it reaches the PC, or null. */
internal fun imageRefusal(prompt: String, adult: Boolean): String? {
    if (prompt.isBlank()) return "Décrivez l'image à créer."
    val words = " ${normalize(prompt)} "
    fun has(list: List<String>) = list.any { words.contains(" $it ") }
    if (has(MINOR_WORDS) || UNDERAGE_AGE.containsMatchIn(prompt)) return "Refusé : je ne crée aucune image d'enfant ni de mineur."
    if (!adult && has(EXPLICIT_WORDS)) {
        return "Le contenu adulte est désactivé : activez-le dans Réglages > Images IA pour ce genre d'image."
    }
    return null
}
