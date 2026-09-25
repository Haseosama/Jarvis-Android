package com.jarvis.android.device

/**
 * Retrouver sur l'écran l'élément que le modèle désigne par son texte.
 *
 * L'ancienne règle était « égalité exacte, sinon le libellé contient la demande ». Elle échouait surtout dans
 * un sens : une demande plus longue que le libellé ne correspondait jamais. « Appuie sur le bouton Envoyer »
 * arrivait ici en « bouton Envoyer » et ne trouvait pas le bouton « Envoyer » ; « Envoyer le message » non
 * plus. Le modèle recevait « Aucun élément », relisait l'écran, réessayait — un aller-retour perdu à chaque
 * fois, audible en conversation vocale. Elle ignorait aussi la langue : l'interface d'une application est en
 * anglais quand l'utilisateur parle français, et « Envoyer » ne rencontrait jamais « Send ».
 *
 * La correspondance est maintenant graduée. Le meilleur niveau atteint l'emporte, et il faut un vainqueur
 * strict : à égalité, on rend la main plutôt que de deviner, parce qu'un appui ne se rattrape pas.
 *
 * Tout est ici sans état ni entrée-sortie, donc testable seul (voir ScreenMatchTest).
 */

private val WORD_SEPARATORS = Regex("[^\\p{L}\\p{N}]+")

/**
 * Mots retirés de la demande seulement, jamais des libellés de l'écran : « appuie sur le bouton Envoyer »
 * doit se réduire à « envoyer », mais un libellé « Appuyer pour continuer » reste entier.
 */
private val QUERY_FILLER: Set<String> = setOf(
    // articles et prépositions
    "le", "la", "les", "l", "un", "une", "des", "du", "de", "d", "au", "aux", "a", "sur", "dans", "en", "et",
    "ou", "ce", "cet", "cette", "mon", "ma", "mes", "the", "of", "to", "on", "in", "for", "an",
    // ce que le modèle ajoute pour désigner un élément
    "bouton", "button", "onglet", "tab", "champ", "field", "case", "checkbox", "icone", "icon", "lien", "link",
    "element", "item", "entree", "entry",
    // ce que le modèle ajoute pour désigner le geste
    "appuyer", "appuie", "appuyez", "appui", "cliquer", "clique", "cliquez", "click", "tap", "press", "toucher",
    "touche", "selectionner", "selectionne", "choisir", "choisis", "ouvrir", "ouvre", "open",
)

/**
 * Mots d'interface qui désignent la même action dans les deux langues. Une application affiche « Send » là où
 * l'utilisateur dit « envoyer ». Chaque mot ne doit appartenir qu'à un seul groupe, sinon l'équivalence
 * deviendrait transitive et rapprocherait n'importe quoi.
 */
private val ALIAS_GROUPS: List<Set<String>> = listOf(
    setOf("envoyer", "envoi", "send"),
    setOf("rechercher", "recherche", "chercher", "search", "find"),
    setOf("retour", "revenir", "back", "precedent", "previous"),
    setOf("suivant", "next", "continuer", "continue"),
    setOf("parametres", "reglages", "settings"),
    setOf("valider", "confirmer", "confirm", "ok", "oui", "yes"),
    setOf("terminer", "termine", "done", "finish"),
    setOf("annuler", "cancel", "non", "no"),
    setOf("supprimer", "effacer", "delete", "remove"),
    setOf("ajouter", "add", "nouveau", "nouvelle", "new", "creer", "create"),
    setOf("partager", "share"),
    setOf("enregistrer", "sauvegarder", "save"),
    setOf("modifier", "editer", "edit"),
    setOf("fermer", "close"),
    setOf("lire", "jouer", "play", "lecture"),
    setOf("pause", "suspendre"),
    setOf("appeler", "telephoner", "call"),
    setOf("accueil", "home"),
    setOf("profil", "profile", "compte", "account"),
    setOf("actualiser", "rafraichir", "refresh", "reload"),
    setOf("telecharger", "download"),
    setOf("photo", "camera"),
    setOf("message", "messages", "sms"),
)

private val ALIAS_OF: Map<String, Int> = buildMap {
    ALIAS_GROUPS.forEachIndexed { group, words -> words.forEach { put(it, group) } }
}

/** Les mots d'un texte, normalisés comme les libellés. [dropFiller] ne vaut que pour la demande. */
internal fun screenWords(text: String, dropFiller: Boolean = false): List<String> {
    val all = normalizeLabel(text).split(WORD_SEPARATORS).filter { it.isNotEmpty() }
    if (!dropFiller) return all
    val kept = all.filter { it !in QUERY_FILLER }
    // Une demande faite uniquement de mots creux (« le bouton ») est rendue telle quelle : mieux vaut ne rien
    // trouver que chercher sur une liste vide.
    return kept.ifEmpty { all }
}

private fun isLatin(token: String): Boolean = token.all { (it in 'a'..'z') || (it in '0'..'9') }

/** Budget de fautes : rien sous cinq lettres, où une lettre change déjà le mot. */
private fun typoBudget(token: String): Int = when {
    !isLatin(token) -> 0
    token.length >= 8 -> 2
    token.length >= 5 -> 1
    else -> 0
}

/** Distance de Levenshtein bornée : on abandonne dès que la ligne entière dépasse [max]. */
internal fun withinScreenEditDistance(a: String, b: String, max: Int): Boolean {
    if (max <= 0) return a == b
    if (kotlin.math.abs(a.length - b.length) > max) return false
    var previous = IntArray(b.length + 1) { it }
    var current = IntArray(b.length + 1)
    for (i in 1..a.length) {
        current[0] = i
        var best = current[0]
        for (j in 1..b.length) {
            val substitution = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
            current[j] = minOf(current[j - 1] + 1, previous[j] + 1, substitution)
            if (current[j] < best) best = current[j]
        }
        if (best > max) return false
        val swap = previous
        previous = current
        current = swap
    }
    return previous[b.length] <= max
}

/** Deux mots qui désignent la même chose : le même mot, ou deux traductions d'une même action. */
private fun equivalentWord(a: String, b: String): Boolean {
    if (a == b) return true
    val groupA = ALIAS_OF[a] ?: return false
    return groupA == ALIAS_OF[b]
}

/** Comme [equivalentWord], en tolérant en plus une faute de frappe. */
private fun closeWord(a: String, b: String): Boolean =
    equivalentWord(a, b) || withinScreenEditDistance(a, b, typoBudget(a))

private fun allCovered(needles: List<String>, haystack: List<String>, fuzzy: Boolean = false): Boolean =
    needles.isNotEmpty() && needles.all { n -> haystack.any { h -> if (fuzzy) closeWord(n, h) else equivalentWord(n, h) } }

internal const val TIER_EXACT = 100
internal const val TIER_SAME_WORDS = 90
internal const val TIER_PREFIX = 70
internal const val TIER_QUERY_IN_LABEL = 60
internal const val TIER_LABEL_IN_QUERY = 55
internal const val TIER_SUBSTRING = 40
internal const val TIER_TYPO = 25

/**
 * À quel point [label] répond à [query] : 0 quand rien ne correspond. Les niveaux sont larges et espacés,
 * pour que la comparaison entre deux candidats soit franche et ne tienne pas à un point d'écart.
 */
internal fun screenMatchScore(query: String, label: String): Int {
    val wantedText = normalizeLabel(query)
    val labelText = normalizeLabel(label)
    if (wantedText.isEmpty() || labelText.isEmpty()) return 0
    if (wantedText == labelText) return TIER_EXACT

    val wanted = screenWords(query, dropFiller = true)
    val words = screenWords(label)
    if (wanted.isEmpty() || words.isEmpty()) return 0

    if (allCovered(wanted, words) && allCovered(words, wanted)) return TIER_SAME_WORDS
    // « Envoyer » demandé sur un bouton « Envoyer le message », et l'inverse.
    if (labelText.startsWith(wantedText) || wantedText.startsWith(labelText)) return TIER_PREFIX
    if (allCovered(wanted, words)) return TIER_QUERY_IN_LABEL
    // Le cas que l'ancienne règle ratait : la demande est plus longue que le libellé.
    if (allCovered(words, wanted)) return TIER_LABEL_IN_QUERY
    if (labelText.contains(wantedText) || wantedText.contains(labelText)) return TIER_SUBSTRING
    if (allCovered(wanted, words, fuzzy = true)) return TIER_TYPO
    return 0
}

/**
 * Le même libellé porté par un élément et par son conteneur donne deux candidats identiques, et donc une
 * ambiguïté inventée de toutes pièces. Quand l'un contient l'autre à l'écran, on garde le plus petit — c'est
 * celui que l'utilisateur voit — en préférant à taille égale celui sur lequel on peut appuyer.
 */
private fun ScreenElement.area(): Long = (right - left).toLong() * (bottom - top).toLong()

private fun ScreenElement.hasBounds(): Boolean = right > left && bottom > top

private fun ScreenElement.contains(other: ScreenElement): Boolean =
    hasBounds() && other.hasBounds() &&
        left <= other.left && top <= other.top && right >= other.right && bottom >= other.bottom

internal fun dropNestedDuplicates(elements: List<ScreenElement>): List<ScreenElement> =
    elements.filterNot { outer ->
        elements.any { inner ->
            inner !== outer &&
                normalizeLabel(inner.label) == normalizeLabel(outer.label) &&
                outer.contains(inner) && inner.area() < outer.area() &&
                (inner.clickable || !outer.clickable)
        }
    }

/**
 * Ce sur quoi on peut appuyer ici. Renvoyé quand la demande ne trouve rien : le modèle relance avec le bon
 * libellé au lieu d'annoncer un échec, comme la mémoire liste ses sujets quand une question ne donne rien.
 */
internal fun tappableSummary(elements: List<ScreenElement>, limit: Int = 8): String {
    val labels = dropNestedDuplicates(elements.filter { it.clickable && !it.password })
        .map { it.label.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
    if (labels.isEmpty()) return "Rien de cliquable n’est lisible sur cet écran."
    val shown = labels.take(limit).joinToString(", ") { "« ${it.take(40)} »" }
    val more = if (labels.size > limit) " (+${labels.size - limit} autres)" else ""
    return "Éléments sur lesquels appuyer ici : $shown$more."
}

/**
 * L'élément que la demande désigne. Un seul meilleur score gagne ; plusieurs à égalité sont rendus au modèle
 * pour qu'il tranche par numéro. Les champs de mot de passe ne sont jamais retrouvés par leur contenu.
 */
internal fun findByText(elements: List<ScreenElement>, query: String, onlyActionable: Boolean = true): ElementMatch {
    if (normalizeLabel(query).isEmpty()) return ElementMatch.None
    val pool = dropNestedDuplicates(
        elements.filter { !it.password && (!onlyActionable || it.clickable || it.editable || it.checked != null) }
    )
    val scored = pool.map { it to screenMatchScore(query, it.label) }.filter { it.second > 0 }
    if (scored.isEmpty()) return ElementMatch.None
    val best = scored.maxOf { it.second }
    val winners = scored.filter { it.second == best }.map { it.first }
    return if (winners.size == 1) ElementMatch.Found(winners[0]) else ElementMatch.Ambiguous(winners.take(8))
}
