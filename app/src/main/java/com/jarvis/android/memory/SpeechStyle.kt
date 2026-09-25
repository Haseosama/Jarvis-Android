package com.jarvis.android.memory

/**
 * Une préférence de style n'est pas une information sur l'utilisateur, c'est une consigne. Rangée avec le reste
 * de ce qu'on sait de lui (« Preferences: - Tutoiement: préfère être tutoyé »), sous un en-tête qui annonce des
 * choses à savoir, elle se lit comme une anecdote : le modèle la suit par politesse statistique, pas par
 * obligation, et elle se dilue au fil d'une longue conversation. Ce fichier repère ces préférences-là pour
 * qu'elles sortent du bloc descriptif et soient posées en directive, comme la règle d'adresse et la règle de
 * langue qui les précèdent dans l'invite.
 *
 * Le repérage se fait d'abord sur la clé. Une clé est courte et curée (`tutoiement`, `longueur_reponses`,
 * `ton_prefere`), on peut y être large sans risque ; la valeur est du texte libre, où « il aime les réponses
 * courtes de son fils » ferait un faux positif. Seuls quelques mots qui ne peuvent rien vouloir dire d'autre
 * sont acceptés dans la valeur.
 *
 * Tout est ici sans état ni entrée-sortie, donc testable seul (voir SpeechStyleTest).
 */

/**
 * La langue est délibérément absente des racines et explicitement rejetée : une règle impérative la fixe déjà
 * plus haut dans l'invite (« always answer in the language of their CURRENT message »). Deux consignes
 * concurrentes sur le même sujet valent moins qu'une seule claire.
 */
private val VETO_ROOTS = listOf("langue", "language", "langage", "idiome", "accent")

/** Racines cherchées dans la clé, regroupées par ce qu'elles règlent. */
private val KEY_ROOTS = listOf(
    // Forme d'adresse.
    "tutoi", "tutoy", "vouvoi", "vouvoy", "formel", "formal", "informel", "informal", "familier", "casual",
    "poli", "polite",
    // Ton et registre.
    "ton", "tone", "style", "registre", "humour", "humor", "blague", "joke", "sarcas", "ironi", "direct",
    "chaleureux", "serieux", "serious",
    // Longueur.
    "court", "bref", "brief", "concis", "concise", "succinct", "long", "detail", "verbeux", "verbose", "resume",
    // Ce que la consigne vise : la parole, la réponse, la voix.
    "parle", "parol", "speak", "talk", "repond", "repons", "reply", "answer", "voix", "voice", "debit",
    "vitesse", "rythme", "pace", "lent", "slow",
    // Mise en forme et niveau d'explication.
    "emoji", "markdown", "liste", "bullet", "puce", "format", "structure", "jargon", "technique", "vulgaris",
    "simple", "clair", "precis",
)

/** Racines cherchées dans la valeur : uniquement des mots sans autre sens possible. */
private val VALUE_ROOTS = listOf("tutoi", "tutoy", "vouvoi", "vouvoy", "emoji", "markdown")

/** Une racine de trois lettres ou moins doit faire le mot entier, sinon « ton » attraperait « tondeuse ». */
private fun hits(tokens: List<String>, roots: List<String>): Boolean =
    tokens.any { token -> roots.any { root -> if (root.length <= 3) token == root else token.startsWith(root) } }

/** Vrai quand cette préférence dit *comment parler* plutôt que *ce que l'utilisateur aime*. */
internal fun isSpeechPreference(key: String, value: String): Boolean {
    val keyTokens = memoryTokens(key)
    if (hits(keyTokens, VETO_ROOTS)) return false
    return hits(keyTokens, KEY_ROOTS) || hits(memoryTokens(value), VALUE_ROOTS)
}

internal const val SPEECH_STYLE_MAX_LINES = 6
internal const val SPEECH_STYLE_MAX_CHARS = 400

internal const val SPEECH_STYLE_HEADER =
    "[HOW THEY WANT YOU TO SPEAK — standing instructions this person gave you, not background facts. " +
        "Follow them in every reply, including long conversations. If one of them contradicts a rule stated " +
        "above, the rule above wins.]"

/**
 * Le bloc de consignes, en-tête compris, ou une liste vide si aucune préférence n'en est une. La plus
 * récemment mise à jour passe en tête : une consigne de style remplace la précédente plus qu'elle ne s'y
 * ajoute. Plafonné en lignes et en caractères pour qu'une mémoire bavarde ne noie pas le reste de l'invite ;
 * une ligne trop longue est sautée sans arrêter la boucle, une plus courte peut encore tenir derrière.
 */
internal fun speechStyleBlock(preferences: Map<String, MemEntry>): List<String> {
    val candidates = preferences.entries
        .filter { it.value.value.isNotBlank() && isSpeechPreference(it.key, it.value.value) }
        .sortedWith(compareByDescending<Map.Entry<String, MemEntry>> { it.value.updated }.thenBy { it.key })
    if (candidates.isEmpty()) return emptyList()

    val lines = mutableListOf<String>()
    var used = 0
    for (entry in candidates) {
        if (lines.size >= SPEECH_STYLE_MAX_LINES) break
        val label = entry.key.replace('_', ' ').trim().replaceFirstChar { it.uppercase() }
        val line = "- $label: ${entry.value.value}"
        if (used + line.length + 1 > SPEECH_STYLE_MAX_CHARS) continue
        lines += line
        used += line.length + 1
    }
    return if (lines.isEmpty()) emptyList() else listOf(SPEECH_STYLE_HEADER) + lines
}
