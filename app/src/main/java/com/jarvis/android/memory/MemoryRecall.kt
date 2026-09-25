package com.jarvis.android.memory

import java.text.Normalizer
import java.time.LocalDate
import java.util.Locale
import kotlin.math.abs

/*
 * Retrouver un souvenir à partir d'une question posée à voix haute.
 *
 * La recherche d'origine comparait bêtement des sous-chaînes : « quel est le prénom de ma sœur ? »
 * donnait des points à tout souvenir contenant « de » ou « ma », et n'en donnait aucun à la clé
 * `sister_name` (l'extracteur écrit ses clés en anglais, l'utilisateur parle français). Ici :
 *
 *  - les mots vides de la question sont écartés (jamais ceux des souvenirs, qui restent indexés tels quels) ;
 *  - un radical est comparé plutôt que le mot entier, pour que « voitures » retrouve « voiture » ;
 *  - une table d'équivalents relie le français et l'anglais (« métier » ↔ `job`, « sœur » ↔ `sister`) ;
 *  - une petite distance d'édition rattrape les erreurs de dictée (« camile » → « Camille ») ;
 *  - un souvenir qui répond à plusieurs mots de la question passe devant un souvenir qui n'en couvre qu'un.
 *
 * Tout est ici sans état ni entrée-sortie, donc testable seul (voir MemoryRecallTest).
 */

private val COMBINING_MARKS = Regex("\\p{M}+")
private val WORD_SEPARATORS = Regex("[^\\p{L}\\p{N}]+")

/** Minuscules, accents retirés : la forme utilisée pour toutes les comparaisons. */
internal fun memoryNormalize(value: String): String =
    COMBINING_MARKS.replace(Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD), "")

/** Les mots d'un texte, normalisés. Les clés `snake_case` se coupent d'elles-mêmes. */
internal fun memoryTokens(value: String): List<String> =
    memoryNormalize(value).split(WORD_SEPARATORS).filter { it.isNotEmpty() }

/** Vrai pour un mot en alphabet latin : les règles de radical et de faute de frappe ne valent que pour lui. */
private fun isLatin(token: String): Boolean = token.all { (it in 'a'..'z') || (it in '0'..'9') }

/**
 * Mots vides français et anglais. Ils ne sont retirés que de la question : « Quel est le nom de mon chien ? »
 * ne doit pas donner de points à tout souvenir contenant « de ».
 */
private val STOP_WORDS: Set<String> = (
    "a ai as au aux avec avoir ce ces cet cette c d dans de des du elle elles en encore es est et " +
        "ete etes etre eu il ils j je l la le les leur lui ma mais me mes moi mon n ne nos notre nous " +
        "on ont ou oui par pas plus pour qu que quel quelle quelles quels qui quoi sa se ses son sont " +
        "suis sommes sur t ta te tes toi ton tu un une vos votre vous y " +
        "alors aussi comment combien deja dire dis dit donc encore quand pourquoi " +
        "peut peux rappelle rappeler sais sait savoir souviens souvenir veut veux " +
        "fais fait faire tout tous toute toutes " +
        "the an of my me i is are was were do does did you your yours he she it we they them his her " +
        "what which who whom whose when where why how and or but for to in on at from with about " +
        "can could would should will shall have has had know remember tell say said get got"
    ).split(' ').filter { it.isNotEmpty() }.toSet()

/**
 * Terminaisons coupées pour obtenir un radical, les plus longues d'abord. Volontairement grossier :
 * il s'agit de rapprocher « voitures »/« voiture » ou « déménagement »/« déménager », pas de faire de la
 * morphologie. Le radical doit garder quatre lettres, sinon le mot est laissé entier.
 */
private val SUFFIXES = listOf(
    "issements", "issement", "ations", "ation", "ements", "ement", "aient", "erait", "eront", "erons",
    "ions", "iez", "ais", "ait", "ant", "ent", "ons", "ez", "er", "ir", "re", "es", "e",
)

/**
 * Le radical grossier d'un mot déjà normalisé. La marque du pluriel est retirée d'abord, sinon « voitures »
 * perdrait « es » et « voiture » perdrait « re » : les deux mots ne se retrouveraient jamais.
 */
internal fun memoryStem(token: String): String {
    if (!isLatin(token) || token.length <= 4) return token
    var base = token
    if ((base.endsWith("s") || base.endsWith("x")) && base.length - 1 >= 4) base = base.dropLast(1)
    if (base.length <= 4) return base
    for (suffix in SUFFIXES) {
        if (base.length - suffix.length >= 4 && base.endsWith(suffix)) return base.dropLast(suffix.length)
    }
    return base
}

/**
 * Familles de mots équivalents. Le premier de chaque ligne sert de représentant (voir [memoryCanonicalKey]).
 * L'extracteur écrit ses clés en anglais alors que l'utilisateur parle français : sans cette table,
 * « mon métier » ne retrouve jamais `identity/job`.
 */
private val ALIAS_GROUPS = listOf(
    "name prenom nom appelle appeler appelles surnom",
    "age ans",
    "birthday anniversaire naissance nee",
    "city ville habite habiter reside residence domicile",
    "address adresse rue habite",
    "job travail travaille travailler metier boulot emploi profession poste entreprise",
    "language langue langues parle",
    "school ecole etude etudes fac faculte universite lycee college",
    "nationality nationalite pays origine originaire",
    "sister soeur soeurs",
    "brother frere freres",
    "mother mere maman",
    "father pere papa",
    "parents parent famille",
    "wife femme epouse compagne copine",
    "husband mari epoux compagnon copain",
    "son fils",
    "daughter fille filles",
    "child enfant enfants",
    "friend ami amie amis copains",
    "colleague collegue collegues bureau",
    "neighbour voisin voisine",
    "pet animal animaux",
    "dog chien chienne chiens",
    "cat chat chatte chats",
    "car voiture auto automobile vehicule",
    "home maison logement appartement appart",
    "food nourriture manger mange plat cuisine repas",
    "drink boisson boire bois boit",
    "coffee cafe",
    "tea the",
    "allergy allergie allergies allergique",
    "diet regime vegetarien vegan",
    "music musique ecoute chanson chansons groupe",
    "sport sportif courir court course entrainement salle",
    "book livre livres lecture lire lit",
    "movie film films cinema serie series",
    "game jeu jeux",
    "trip voyage voyager vacances partir",
    "project projet projets chantier",
    "goal objectif objectifs but",
    "work_schedule horaire horaires planning",
    "doctor medecin docteur",
    "health sante maladie",
    "phone telephone portable numero",
    "mail email courriel adresse_mail",
    "money argent budget salaire",
    "size taille pointure",
    "color couleur couleurs",
    "wish souhait souhaits envie envies voudrait aimerait",
    "holiday vacances conge conges ferie",
    "wake_up reveil lever leve matin",
    "sleep sommeil coucher dort dormir",
    "hobby loisir loisirs passion passions",
)

/** mot → tous ses équivalents (lui compris). */
private val ALIASES: Map<String, Set<String>> = run {
    val index = HashMap<String, MutableSet<String>>()
    for (group in ALIAS_GROUPS) {
        val words = group.split(' ').filter { it.isNotEmpty() }
        for (word in words) index.getOrPut(word) { mutableSetOf() }.addAll(words)
    }
    index
}

/** mot → représentant de sa famille, pour comparer deux clés écrites dans deux langues. */
private val ALIAS_REPRESENTATIVE: Map<String, String> = run {
    val index = HashMap<String, String>()
    for (group in ALIAS_GROUPS) {
        val words = group.split(' ').filter { it.isNotEmpty() }
        val head = words.firstOrNull() ?: continue
        for (word in words) index.putIfAbsent(word, head)
    }
    index
}

/** Distance d'édition bornée : vrai dès que [a] et [b] sont à [max] corrections l'un de l'autre. */
internal fun withinEditDistance(a: String, b: String, max: Int): Boolean {
    if (max <= 0) return a == b
    if (abs(a.length - b.length) > max) return false
    if (a == b) return true
    var previous = IntArray(b.length + 1) { it }
    var current = IntArray(b.length + 1)
    for (i in 1..a.length) {
        current[0] = i
        var best = i
        for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + cost)
            if (current[j] < best) best = current[j]
        }
        if (best > max) return false
        val swap = previous
        previous = current
        current = swap
    }
    return previous[b.length] <= max
}

/** Corrections tolérées sur un mot : aucune s'il est court, une au-delà de cinq lettres, deux au-delà de huit. */
private fun fuzzyBudget(token: String): Int = when {
    !isLatin(token) -> 0
    token.length >= 8 -> 2
    token.length >= 5 -> 1
    else -> 0
}

/** Un mot de la question, avec ses formes équivalentes calculées une fois pour toute la recherche. */
internal class MemoryQueryWord(val text: String) {
    val variants: Set<String> = run {
        val out = linkedSetOf(text, memoryStem(text))
        for (seed in out.toList()) ALIASES[seed]?.let { out.addAll(it) }
        out
    }

    /** Les formes assez longues pour qu'une faute de dictée soit rattrapable. */
    val fuzzyVariants: List<String> = variants.filter { fuzzyBudget(it) > 0 }

    /** Un mot trop court en alphabet latin ne doit pas être cherché comme sous-chaîne (« ai » dans « travail »). */
    val substringSearchable: Boolean = !isLatin(text) || text.length >= 3
}

/** Une question, réduite à ses mots utiles. Vide quand elle ne contenait aucun mot. */
internal class MemoryQuery(val words: List<MemoryQueryWord>) {
    val isEmpty: Boolean get() = words.isEmpty()

    companion object {
        fun of(raw: String): MemoryQuery {
            val all = memoryTokens(raw).distinct()
            val useful = all.filter { it !in STOP_WORDS && (!isLatin(it) || it.length >= 2) }
            // Une question faite seulement de mots vides (« et moi ? ») est cherchée telle quelle.
            return MemoryQuery((if (useful.isNotEmpty()) useful else all).map { MemoryQueryWord(it) })
        }
    }
}

private const val MATCH_EXACT = 3
private const val MATCH_RELATED = 2
private const val MATCH_NEAR = 1
private const val MATCH_NONE = 0

/** Le meilleur rapprochement entre un mot de la question et les mots d'un texte. */
private fun matchLevel(word: MemoryQueryWord, tokens: List<String>, stems: List<String>): Int {
    if (tokens.any { it in word.variants }) return MATCH_EXACT
    if (stems.any { it in word.variants }) return MATCH_RELATED
    if (word.variants.any { memoryStem(it) in stems }) return MATCH_RELATED
    for (token in tokens) {
        if (word.fuzzyVariants.any { withinEditDistance(it, token, fuzzyBudget(it)) }) return MATCH_NEAR
    }
    return MATCH_NONE
}

/**
 * Ce que vaut un souvenir pour une question. Zéro quand rien ne correspond — le souvenir n'est alors pas proposé.
 * La clé pèse plus que la valeur (c'est elle qui nomme le fait), et couvrir plusieurs mots de la question
 * compte davantage que répéter le même.
 */
internal fun scoreMemoryEntry(query: MemoryQuery, category: String, key: String, value: String): Int {
    if (query.isEmpty) return 0
    val readableKey = key.replace('_', ' ')
    val keyTokens = memoryTokens(readableKey)
    val valueTokens = memoryTokens(value)
    val keyStems = keyTokens.map { memoryStem(it) }
    val valueStems = valueTokens.map { memoryStem(it) }
    val keyText = memoryNormalize(readableKey)
    val valueText = memoryNormalize(value)

    var total = 0
    var matched = 0
    for (word in query.words) {
        var best = when (matchLevel(word, keyTokens, keyStems)) {
            MATCH_EXACT -> if (keyTokens.size == 1) 12 else 9
            MATCH_RELATED -> 8
            MATCH_NEAR -> 4
            else -> 0
        }
        if (best == 0 && word.substringSearchable && keyText.contains(word.text)) best = 5
        val onValue = when (matchLevel(word, valueTokens, valueStems)) {
            MATCH_EXACT -> 6
            MATCH_RELATED -> 5
            MATCH_NEAR -> 2
            else -> 0
        }
        if (onValue > best) best = onValue
        if (best == 0 && word.substringSearchable && valueText.contains(word.text)) best = 3
        if (best == 0 && word.text == category) best = 2
        if (best > 0) matched++
        total += best
    }
    if (matched == 0) return 0
    return total * (2 + matched) / 2
}

/**
 * La forme comparable d'une clé : radicaux, ramenés au représentant de leur famille, triés et dédoublonnés.
 * `prenom_soeur`, `soeur_prenom` et `sister_name` donnent tous `[name, sister]`, ce qui permet de reconnaître
 * deux écritures du même fait au lieu d'en garder deux versions contradictoires.
 */
internal fun memoryCanonicalKey(key: String): List<String> =
    memoryTokens(key.replace('_', ' '))
        // Le mot entier est cherché avant son radical : « ville » est dans la table, « vill » n'y est pas.
        .map { token -> ALIAS_REPRESENTATIVE[token] ?: memoryStem(token).let { ALIAS_REPRESENTATIVE[it] ?: it } }
        .distinct()
        .sorted()

/** La forme stricte d'une clé : même chose sans les équivalents, pour reconnaître « Ville » et « ville ». */
internal fun memoryLiteralKey(key: String): List<String> =
    memoryTokens(key.replace('_', ' ')).map { memoryStem(it) }.distinct().sorted()

/*
 * Ce qui entre dans l'invite de départ. La sélection se faisait sur la seule date de mise à jour : trois notes
 * écrites hier chassaient le prénom de la sœur appris il y a six mois. Un poids par catégorie garde les faits
 * durables devant les notes de passage, sans pour autant figer la mémoire (une note récente reste devant un
 * projet abandonné depuis un an).
 */

/** Poids d'une catégorie : ce qui définit la personne pèse plus que ce qu'elle a noté en passant. */
internal fun memoryImportance(category: String): Double = when (category) {
    "identity" -> 1.0
    "relationships" -> 1.0
    "preferences" -> 0.9
    "projects" -> 0.85
    "wishes" -> 0.7
    else -> 0.5
}

/** Nombre de jours au bout duquel un souvenir ne vaut plus que la moitié de sa fraîcheur. */
internal const val MEMORY_FRESHNESS_HALF_LIFE_DAYS = 30.0

/** Fraîcheur dans [0, 1] : 1 aujourd'hui, 0,5 au bout d'un mois, 0 pour une date absente ou illisible. */
internal fun memoryFreshness(updated: String, today: LocalDate): Double {
    val date = try {
        LocalDate.parse(updated)
    } catch (_: Exception) {
        return 0.0
    }
    val days = java.time.temporal.ChronoUnit.DAYS.between(date, today).coerceAtLeast(0L)
    return 1.0 / (1.0 + days / MEMORY_FRESHNESS_HALF_LIFE_DAYS)
}

/** Ce qui décide de la place d'un souvenir dans l'invite : son poids, tempéré par sa fraîcheur. */
internal fun memoryPromptScore(category: String, updated: String, today: LocalDate): Double =
    memoryImportance(category) * (0.5 + 0.5 * memoryFreshness(updated, today))
