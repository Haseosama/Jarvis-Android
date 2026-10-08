package com.jarvis.android.ui

import com.jarvis.android.text.normalize

/*
 * The settings page has some forty cards. It opens on a menu of categories (and a search field); a category shows only
 * its cards. The cards are not moved in the code: each card finds its category from its (French) title here, and hides
 * itself when it does not match. SettingsFilterTest checks that every card has a category.
 */

internal enum class SettingsGroup(val french: String, val frenchHint: String) {
    JARVIS("Jarvis", "Nom, apparence, mémoire"),
    VOICE("Voix et écoute", "Voix, mot d’activation, audio"),
    AI("IA et clés API", "Clés Gemini, Perplexity, IA locale"),
    PHONE("Téléphone", "Contrôle, contacts, messages"),
    ORGANISE("Organisation", "Agenda, rappels, listes, dépenses"),
    BRIEFINGS("Briefings et alertes", "Briefings, surveillances, électricité"),
    HEALTH("Santé et sécurité", "Santé, médicaments, urgence"),
    CAR("Voiture et trajets", "Voiture garée, conduite, transports"),
    FILES("Fichiers et notes", "Dossier de travail, photos, Obsidian"),
    SERVICES("Services connectés", "Google, maison, PC, images, Instagram, connecteurs"),
}

/** The cards of each category, by French title, in the order the menu lists them. */
internal val GROUP_CARDS: Map<SettingsGroup, List<String>> = mapOf(
    SettingsGroup.JARVIS to listOf("Identité", "Apparence", "Historique des sessions", "Sauvegarde de la mémoire"),
    SettingsGroup.VOICE to listOf("Voix", "Mot d’activation (« Hey Jarvis »)", "Apprendre mon mot d’activation", "Périphériques audio"),
    SettingsGroup.AI to listOf("Clés API et modèles", "Recherche Perplexity", "IA locale (hors ligne)"),
    SettingsGroup.PHONE to listOf("Contrôle du téléphone", "Contacts (appels et SMS)", "Notifications", "Envoi de messages", "Ne pas déranger",
        "Accès rapide", "Position (météo)", "Mise à jour"),
    SettingsGroup.ORGANISE to listOf("Agenda", "Rappels", "Listes", "Dépenses", "Rappels selon le lieu", "Notes de réunion"),
    SettingsGroup.BRIEFINGS to listOf("Briefing du matin", "Briefing au réveil", "Vérifications en arrière-plan", "Surveillances",
        "Électricité (Tempo, EcoWatt)"),
    SettingsGroup.HEALTH to listOf("Santé", "Médicaments et habitudes", "Urgence / SOS"),
    SettingsGroup.CAR to listOf("Voiture garée", "Mode conduite", "Transports"),
    SettingsGroup.FILES to listOf("Dossier de travail (fichiers)", "Photos", "Notes Obsidian"),
    SettingsGroup.SERVICES to listOf("Google (Gmail, Drive)", "Colis", "Maison connectée", "Jarvis PC", "Images IA", "Plugins", "Connecteurs", "Instagram"),
)

/** The category of each card, by its French title. */
internal val CARD_GROUP: Map<String, SettingsGroup> = GROUP_CARDS.flatMap { (g, titles) -> titles.map { it to g } }.toMap()

/** Words a card is also found by, beyond its title ("clé" finds the API keys, "batterie" the wake word…). */
private val KEYWORDS: Map<String, String> = mapOf(
    "Clés API et modèles" to "gemini cle key modele api",
    "Mot d’activation (« Hey Jarvis »)" to "hey jarvis ecoute micro batterie economie wake",
    "Contrôle du téléphone" to "accessibilite confirmation ecran",
    "Envoi de messages" to "sms whatsapp messenger envoyer",
    "Contacts (appels et SMS)" to "appels journal telephone",
    "Colis" to "la poste suivi cle",
    "Transports" to "train sncf bus tram metro cle navitia departs horaires transitous",
    "Maison connectée" to "home assistant domotique lumiere",
    "Connecteurs" to "mcp serveur notion github zapier n8n outils jeton oauth connexion",
    "Instagram" to "urbex photos publier poster reseaux sociaux facebook page jeton meta",
    "Jarvis PC" to "ordinateur pc controle distance qr appairer",
    "Images IA" to "image dessin generer creer fooocus comfyui forge stable diffusion adulte",
    "Médicaments et habitudes" to "medicament pilule",
    "Urgence / SOS" to "secours alerte",
    "Santé" to "pas sommeil coeur health connect",
    "Ne pas déranger" to "reunion silence dnd",
    "Briefing au réveil" to "alarme reveil matin",
    "Dépenses" to "argent budget ticket",
    "Voix" to "langue parole edge elevenlabs piper hors ligne microsoft",
    "Apparence" to "visage avatar haseo theme couleur langue interface",
    "Recherche Perplexity" to "cle key api web internet sources recherche approfondie",
    "IA locale (hors ligne)" to "gemma qwen modele telecharger offline",
    "Électricité (Tempo, EcoWatt)" to "edf tarif jour rouge",
)

internal data class SettingsFilter(val query: String = "", val group: SettingsGroup? = null) {
    /** False on the menu of categories, where no card is shown. */
    val active: Boolean get() = query.isNotBlank() || group != null

    /** Whether the card titled [french] (shown as [shown]) matches the category and the search. */
    fun accepts(french: String, shown: String): Boolean {
        if (group != null && CARD_GROUP[french] != group) return false
        val q = normalize(query)
        if (q.isEmpty()) return true
        val haystack = normalize("$french $shown ${KEYWORDS[french].orEmpty()}")
        return q.split(' ').all { haystack.contains(it) }
    }

    /** Whether the card is on screen: never on the menu, otherwise when it matches. */
    fun shows(french: String, shown: String): Boolean = active && accepts(french, shown)

    /** The cards a search finds, by French title; [shownOf] gives the title in the interface language. */
    fun matches(shownOf: (String) -> String): List<String> = CARD_GROUP.keys.filter { shows(it, shownOf(it)) }
}
