package com.jarvis.android.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jarvis.android.i18n.tr
import com.jarvis.android.text.normalize

/*
 * The settings page has some forty cards: a search field and a few themes to narrow them down. The cards are not
 * moved in the code; each card finds its theme from its (French) title here, and hides itself when it does not match.
 */

internal enum class SettingsGroup(val french: String) {
    VOICE("Voix et IA"), PHONE("Téléphone"), DAILY("Vie quotidienne"), CAR("Voiture et sécurité"), SERVICES("Services et données"),
}

/** The theme of each card, by its French title; a card not listed is only found by the search. */
private val GROUPS: Map<String, SettingsGroup> = buildMap {
    fun put(g: SettingsGroup, vararg titles: String) = titles.forEach { put(it, g) }
    put(SettingsGroup.VOICE, "Identité", "Voix", "Périphériques audio", "IA locale (hors ligne)", "Apparence", "Mot d’activation (« Hey Jarvis »)",
        "Apprendre mon mot d’activation", "Clés API et modèles", "Historique des sessions", "Sauvegarde de la mémoire")
    put(SettingsGroup.PHONE, "Contrôle du téléphone", "Contacts (appels et SMS)", "Notifications", "Envoi de messages", "Ne pas déranger",
        "Accès rapide", "Dossier de travail (fichiers)", "Photos", "Mise à jour", "Position (météo)")
    put(SettingsGroup.DAILY, "Agenda", "Rappels", "Listes", "Dépenses", "Médicaments et habitudes", "Rappels selon le lieu", "Briefing du matin",
        "Briefing au réveil", "Vérifications en arrière-plan", "Santé", "Notes de réunion")
    put(SettingsGroup.CAR, "Voiture garée", "Mode conduite", "Urgence / SOS")
    put(SettingsGroup.SERVICES, "Plugins", "Surveillances", "Google (Gmail, Drive)", "Colis", "Transports", "Maison connectée")
}

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
    "Médicaments et habitudes" to "medicament pilule",
    "Urgence / SOS" to "secours alerte",
    "Santé" to "pas sommeil coeur health connect",
    "Ne pas déranger" to "reunion silence dnd",
    "Briefing au réveil" to "alarme reveil matin",
    "Dépenses" to "argent budget ticket",
    "Voix" to "langue parole",
)

internal data class SettingsFilter(val query: String = "", val group: SettingsGroup? = null) {
    val active: Boolean get() = query.isNotBlank() || group != null

    /** Whether the card titled [french] (shown as [shown]) is listed. */
    fun accepts(french: String, shown: String): Boolean {
        if (group != null && GROUPS[french] != group) return false
        val q = normalize(query)
        if (q.isEmpty()) return true
        val haystack = normalize("$french $shown ${KEYWORDS[french].orEmpty()}")
        return q.split(' ').all { haystack.contains(it) }
    }
}

internal val LocalSettingsFilter = staticCompositionLocalOf { SettingsFilter() }

/** The search field and the theme chips at the top of the settings page. */
@Composable
internal fun SettingsFilterBar(filter: SettingsFilter, onChange: (SettingsFilter) -> Unit) {
    OutlinedTextField(
        value = filter.query,
        onValueChange = { onChange(filter.copy(query = it.take(40))) },
        placeholder = { Text(tr("Rechercher un réglage")) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (filter.query.isNotEmpty()) IconButton(onClick = { onChange(filter.copy(query = "")) }) { Icon(Icons.Filled.Clear, tr("Effacer")) }
        },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 6.dp)) {
        FilterChip(selected = filter.group == null, onClick = { onChange(filter.copy(group = null)) }, label = { Text(tr("Tout")) })
        SettingsGroup.entries.forEach { g ->
            FilterChip(selected = filter.group == g, onClick = { onChange(filter.copy(group = if (filter.group == g) null else g)) }, label = { Text(tr(g.french)) })
        }
    }
}
