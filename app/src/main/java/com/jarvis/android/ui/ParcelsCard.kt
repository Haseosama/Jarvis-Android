package com.jarvis.android.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Train
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.jarvis.android.JarvisApp
import com.jarvis.android.i18n.tr
import com.jarvis.android.i18n.trf
import com.jarvis.android.instagram.InstagramTool
import com.jarvis.android.instagram.encodeAccount
import com.jarvis.android.transport.TRANSITOUS_SOURCES
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Parcels: the La Poste key for automatic tracking, and the parcels being followed. */
@Composable
internal fun ParcelsCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val container = remember { (context.applicationContext as JarvisApp).container }
    var hasKey by remember { mutableStateOf(!container.configStore.getLaPosteKey().isNullOrBlank()) }
    var keyField by remember { mutableStateOf("") }
    var parcels by remember { mutableStateOf(container.parcelStore.all()) }
    SettingsCard(tr("Colis"), Icons.Filled.LocalShipping, initiallyExpanded = false) {
        Text(
            tr("« Suis mon colis 6A12345678901 », « où en est mon colis ? ». La Poste, Colissimo et Chronopost sont suivis automatiquement toutes les trois heures, avec une notification à chaque étape, grâce à une clé gratuite de La Poste. Pour les autres transporteurs, Jarvis ouvre leur page de suivi. Un numéro de suivi vu dans un message est proposé en un geste."),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 6.dp),
        )
        if (hasKey) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text(tr("Clé La Poste enregistrée ✓"), modifier = Modifier.weight(1f))
                TextButton(onClick = { scope.launch { container.configStore.deleteLaPosteKey(); hasKey = false } }) { Text(tr("Supprimer")) }
            }
        } else {
            Text(
                tr("Pour le suivi automatique : créez un compte gratuit sur developer.laposte.fr, abonnez-vous à l’API « Suivi v2 » et collez ici la clé (X-Okapi-Key). Elle est chiffrée sur le téléphone."),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )
            OutlinedButton(onClick = {
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://developer.laposte.fr/products/suivi/2")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: Exception) {
                }
            }, modifier = Modifier.padding(top = 4.dp)) { Text(tr("Ouvrir developer.laposte.fr")) }
            OutlinedTextField(
                value = keyField,
                onValueChange = { keyField = it.trim().take(200) },
                label = { Text(tr("Clé La Poste (Okapi)")) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            Button(
                onClick = { scope.launch { if (container.configStore.saveLaPosteKey(keyField)) { hasKey = true; keyField = "" } } },
                enabled = keyField.length >= 10,
                modifier = Modifier.padding(top = 4.dp),
            ) { Text(tr("Enregistrer la clé")) }
        }
        Text(tr("Colis suivis"), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp))
        if (parcels.isEmpty()) Text(tr("Aucun pour l’instant."), style = MaterialTheme.typography.bodyMedium)
        for (p in parcels) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "${p.label.ifBlank { p.number }} · ${p.carrier.label}" + if (p.status.isNotBlank()) "\n${p.status}" else "",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { parcels = container.parcelStore.update { list -> list.filterNot { it.number == p.number } } }) { Text(tr("Retirer")) }
            }
        }
    }
}

/** One service key: saved (with Supprimer) or a field to paste it, and where to get it. */
@Composable
private fun ServiceKey(label: String, how: String, link: String, get: () -> String?, save: suspend (String) -> Boolean, delete: suspend () -> Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var has by remember { mutableStateOf(!get().isNullOrBlank()) }
    var field by remember { mutableStateOf("") }
    if (has) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Text(trf("{0} enregistrée ✓", label), modifier = Modifier.weight(1f))
            TextButton(onClick = { scope.launch { delete(); has = false } }) { Text(tr("Supprimer")) }
        }
        return
    }
    Text(how, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 10.dp))
    OutlinedButton(onClick = {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
        }
    }, modifier = Modifier.padding(top = 4.dp)) { Text(trf("Ouvrir {0}", Uri.parse(link).host.orEmpty())) }
    OutlinedTextField(
        value = field,
        onValueChange = { field = it.trim().take(200) },
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
    )
    Button(onClick = { scope.launch { if (save(field)) { has = true; field = "" } } }, enabled = field.length >= 10, modifier = Modifier.padding(top = 4.dp)) {
        Text(tr("Enregistrer la clé"))
    }
}

/** Public transport: the SNCF key (trains) and the navitia.io key (local buses and trams); departures work without either (Transitous). */
@Composable
internal fun TransportCard() {
    val context = LocalContext.current
    val config = remember { (context.applicationContext as JarvisApp).container.configStore }
    SettingsCard(tr("Transports"), Icons.Filled.Train, initiallyExpanded = false) {
        Text(
            tr("« Quel est le prochain train pour Rennes ? », « les départs de la gare de Brest », « mon bus passe quand ? » : horaires en temps réel, avec les retards. Les prochains départs marchent sans clé, partout en France, grâce à Transitous ; les trajets d’une gare à l’autre demandent la clé SNCF gratuite. Les clés sont chiffrées sur le téléphone."),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 6.dp),
        )
        // Transitous asks for a visible link to the sources of its timetables.
        TextButton(onClick = {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(TRANSITOUS_SOURCES)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }) { Text(tr("Sources des horaires sans clé (Transitous)")) }
        ServiceKey(
            tr("Clé SNCF"),
            tr("Trains : demandez une clé gratuite sur numerique.sncf.com (« API SNCF », jeton développeur, reçu par mail) et collez-la ici."),
            "https://numerique.sncf.com/startup/api/token-developpeur/",
            { config.getSncfKey() }, { config.saveSncfKey(it) }, { config.deleteSncfKey() },
        )
        ServiceKey(
            tr("Clé navitia.io"),
            tr("Bus, trams et métros de votre ville (facultatif) : créez un compte gratuit sur navitia.io et collez la clé ici."),
            "https://navitia.io/inscription/",
            { config.getNavitiaKey() }, { config.saveNavitiaKey(it) }, { config.deleteNavitiaKey() },
        )
    }
}

/** Electricity: the RTE key for EcoWatt (Tempo needs none). */
@Composable
internal fun EnergyCard() {
    val context = LocalContext.current
    val config = remember { (context.applicationContext as JarvisApp).container.configStore }
    SettingsCard(tr("Électricité (Tempo, EcoWatt)"), Icons.Filled.Bolt, initiallyExpanded = false) {
        Text(
            tr("« Demain, c’est un jour rouge ? », « je peux lancer la machine ? » : la couleur Tempo marche sans rien faire. Pour EcoWatt (la tension du réseau, heure par heure), il faut une clé gratuite de RTE ; elle est chiffrée sur le téléphone."),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 6.dp),
        )
        ServiceKey(
            tr("Clé RTE (EcoWatt)"),
            tr("Créez un compte gratuit sur data.rte-france.com, abonnez une application à l’API « Ecowatt », puis copiez son « ID client encodé en base 64 » et collez-le ici."),
            "https://data.rte-france.com/catalog/-/api/consumption/Ecowatt/v5.0",
            { config.getRteKey() }, { config.saveRteKey(it) }, { config.deleteRteKey() },
        )
    }
}

/** Perplexity: the key for sourced web answers (the perplexity_search tool). */
@Composable
internal fun PerplexityCard() {
    val context = LocalContext.current
    val config = remember { (context.applicationContext as JarvisApp).container.configStore }
    SettingsCard(tr("Recherche Perplexity"), Icons.Filled.TravelExplore, initiallyExpanded = false) {
        Text(
            tr("« Fais le point sur… », « compare… », « cherche en détail… » : avec une clé Perplexity, Jarvis peut demander à Perplexity une réponse qui croise plusieurs pages du Web, avec ses sources. Sans clé, la recherche web habituelle reste disponible. L’API est payante à l’usage ; la clé est chiffrée sur le téléphone."),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 6.dp),
        )
        ServiceKey(
            tr("Clé Perplexity"),
            tr("Créez une clé API dans la console Perplexity (onglet « API Keys »), ajoutez du crédit, puis collez-la ici. Si la clé a été montrée à quelqu’un, révoquez-la dans la console et créez-en une autre."),
            "https://console.perplexity.ai",
            { config.getPerplexityKey() }, { config.savePerplexityKey(it) }, { config.deletePerplexityKey() },
        )
    }
}

/** Instagram: the token to post the urbex photos (the instagram_publier tool), the album they are in, and which account it reaches. */
@Composable
internal fun InstagramCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val container = remember { (context.applicationContext as JarvisApp).container }
    val config = container.configStore
    var hasToken by remember { mutableStateOf(!config.getInstagramToken().isNullOrBlank()) }
    var field by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    val album by config.instagramAlbum.collectAsState(initial = "Urbex")
    var albumField by remember(album) { mutableStateOf(album) }
    SettingsCard(tr("Instagram"), Icons.Filled.PhotoCamera, initiallyExpanded = false) {
        Text(
            tr("« Publie mes photos d’urbex sur Instagram » : Jarvis prend la dernière sortie de l’album choisi (les photos pas encore publiées, 10 au plus en carrousel), écrit la légende d’après les photos et publie. La position GPS et toutes les métadonnées sont retirées des photos avant l’envoi."),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 6.dp),
        )
        OutlinedTextField(
            value = albumField,
            onValueChange = { albumField = it.take(60) },
            label = { Text(tr("Album des photos d’urbex")) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        if (albumField.trim() != album) {
            TextButton(onClick = { scope.launch { config.setInstagramAlbum(albumField) } }) { Text(tr("Enregistrer l’album")) }
        }
        if (hasToken) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text(tr("Jeton Instagram enregistré ✓"), modifier = Modifier.weight(1f))
                TextButton(onClick = { scope.launch { config.deleteInstagramToken(); hasToken = false; status = null } }) { Text(tr("Supprimer")) }
            }
            OutlinedButton(onClick = {
                status = tr("Vérification…")
                scope.launch {
                    status = withContext(Dispatchers.IO) {
                        val graph = InstagramTool.Graph(container.http)
                        when (val r = InstagramTool.resolveAccount(graph, config.getInstagramToken().orEmpty(), null)) {
                            is InstagramTool.Resolved.Ok -> {
                                config.saveInstagramAccount(encodeAccount(r.account))
                                trf("Relié à @{0} par la Page « {1} ».", r.account.username, r.account.pageName)
                            }
                            is InstagramTool.Resolved.Failed -> r.message
                        }
                    }
                }
            }, modifier = Modifier.padding(top = 4.dp)) { Text(tr("Vérifier le compte")) }
            status?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)) }
            return@SettingsCard
        }
        Text(
            tr("À faire une fois : 1) dans l’application Instagram, passez le compte en compte professionnel (Créateur ou Entreprise) ; 2) liez-le à une Page Facebook (créez-en une si besoin, elle peut rester vide) ; 3) sur developers.facebook.com, créez une application (cas d’usage « Gérer les messages et le contenu sur Instagram ») ; 4) dans l’explorateur de l’API Graph, choisissez cette application et générez un jeton utilisateur avec les autorisations pages_show_list, pages_read_engagement, pages_manage_posts, instagram_basic, instagram_content_publish et business_management, en choisissant votre Page ; 5) dans le débogueur de jeton, « Prolonger le jeton d’accès », puis collez le jeton prolongé ici. Jarvis en tire un jeton de Page qui n’expire pas ; tout reste chiffré sur le téléphone."),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 10.dp),
        )
        OutlinedButton(onClick = {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://developers.facebook.com/tools/explorer/")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {
            }
        }, modifier = Modifier.padding(top = 4.dp)) { Text(tr("Ouvrir l’explorateur de l’API Graph")) }
        OutlinedTextField(
            value = field,
            onValueChange = { field = it.trim().take(1_000) },
            label = { Text(tr("Jeton Instagram")) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
        Button(onClick = { scope.launch { if (config.saveInstagramToken(field)) { hasToken = true; field = "" } } }, enabled = field.length >= 20, modifier = Modifier.padding(top = 4.dp)) {
            Text(tr("Enregistrer le jeton"))
        }
    }
}
