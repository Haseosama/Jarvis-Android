package com.jarvis.android.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.jarvis.android.JarvisApp
import com.jarvis.android.i18n.tr
import com.jarvis.android.rest.OTHER_MODEL_ALWAYS
import com.jarvis.android.rest.OTHER_MODEL_FALLBACK
import com.jarvis.android.rest.OTHER_MODEL_OFF
import com.jarvis.android.rest.OTHER_MODEL_PRESETS
import com.jarvis.android.rest.OtherModelPreset
import com.jarvis.android.rest.isPrivateEndpoint
import com.jarvis.android.rest.normalizeBaseUrl
import kotlinx.coroutines.launch

/** The address a preset fills in, with the paired Jarvis PC's host (its Tailscale address) in place of the placeholder. */
internal fun presetAddress(preset: OtherModelPreset, pcHost: String?): String =
    if (pcHost.isNullOrBlank()) preset.baseUrl else preset.baseUrl.replace("ADRESSE-DU-PC", pcHost)

/**
 * Settings > IA et clés API > Autre modèle (rest/OpenAiCompat.kt): the address, the model and the key of an OpenAI-compatible
 * service, when to use it, the voice too or not, and a TEST button that says in a sentence what works or what is wrong.
 */
@Composable
internal fun OtherModelCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val container = remember { (context.applicationContext as JarvisApp).container }
    val config = container.configStore
    val savedUrl by config.otherModelUrl.collectAsState(initial = "")
    val savedName by config.otherModelName.collectAsState(initial = "")
    val use by config.otherModelUse.collectAsState(initial = OTHER_MODEL_FALLBACK)
    val voice by config.otherModelVoice.collectAsState(initial = false)
    var url by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    LaunchedEffect(savedUrl) { url = savedUrl }
    LaunchedEffect(savedName) { name = savedName }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    val pcHost = remember {
        com.jarvis.android.pc.PcPairing.fromJson(config.getPcPairing())?.baseUrl?.let { runCatching { java.net.URI(it).host }.getOrNull() }
    }

    fun save(then: suspend () -> Unit = {}) = scope.launch {
        config.setOtherModelUrl(normalizeBaseUrl(url))
        config.setOtherModelName(name)
        then()
    }

    SettingsCard(tr("Autre modèle (Ollama, Groq…)"), Icons.Filled.Hub, initiallyExpanded = false) {
        Text(
            tr("Un autre « cerveau » que Gemini : Ollama ou LM Studio sur votre PC (joignable par Tailscale), Ollama sur le téléphone avec Termux, ou un service en ligne compatible OpenAI (Groq, OpenRouter, Mistral, DeepSeek, OpenAI, xAI). Il prend le chat, les outils et les demandes internes de Jarvis (mémoire, documents, agent). La recherche Google, les PDF et les voix Gemini restent chez Gemini."),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 6.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState())) {
            OTHER_MODEL_PRESETS.forEach { p ->
                FilterChip(selected = false, onClick = {
                    url = presetAddress(p, pcHost)
                    if (p.model.isNotBlank()) name = p.model
                    status = listOfNotNull(
                        if (url.contains("ADRESSE-DU-PC")) tr("Remplacez ADRESSE-DU-PC par l’adresse Tailscale du PC (100.x.y.z).") else null,
                        // both listen only to the PC itself until told otherwise
                        if (p.label == "Ollama (PC)") tr("Sur le PC, Ollama doit écouter le réseau : variable d’environnement OLLAMA_HOST=0.0.0.0, puis relancez Ollama.") else null,
                        if (p.label == "LM Studio (PC)") tr("Dans LM Studio, onglet Developer : démarrez le serveur et cochez « Serve on Local Network ».") else null,
                    ).joinToString("\n").ifEmpty { null }
                }, label = { Text(tr(p.label)) })
            }
        }
        OutlinedTextField(
            value = url,
            onValueChange = { url = it.trim().take(300) },
            label = { Text(tr("Adresse (…/v1)")) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.trim().take(120) },
                label = { Text(tr("Nom du modèle")) },
                singleLine = true,
                modifier = Modifier.weight(1f).padding(top = 4.dp),
            )
            DropdownMenu(expanded = models.isNotEmpty(), onDismissRequest = { models = emptyList() }) {
                models.take(60).forEach { m -> DropdownMenuItem(text = { Text(m) }, onClick = { name = m; models = emptyList() }) }
            }
        }
        if (url.isNotBlank() && isPrivateEndpoint(url)) {
            Text(
                tr("Modèle chez vous : Jarvis ne lui donne que les outils utiles à chaque demande, avec des descriptions courtes, pour qu’un petit modèle ne s’y perde pas."),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            OutlinedButton(enabled = !busy && url.isNotBlank() && name.isNotBlank(), onClick = {
                busy = true
                status = tr("Test en cours…")
                save { status = container.restChat.otherModel.test(); busy = false }
            }) { Text(tr("Enregistrer et tester")) }
            OutlinedButton(enabled = !busy && url.isNotBlank(), onClick = {
                busy = true
                save {
                    container.restChat.otherModel.models().fold(
                        onSuccess = { list -> models = list; status = if (list.isEmpty()) tr("Aucun modèle annoncé à cette adresse.") else null },
                        onFailure = { status = it.message },
                    )
                    busy = false
                }
            }) { Text(tr("Lister les modèles")) }
        }
        status?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)) }
        ServiceKey(
            tr("Clé de l’autre modèle"),
            tr("Pour un service en ligne seulement (Ollama et LM Studio chez vous n’en demandent pas) : créez une clé API dans sa console et collez-la ici. Elle est chiffrée sur le téléphone."),
            "https://console.groq.com/keys",
            { config.getOtherModelKey() }, { config.saveOtherModelKey(it) }, { config.deleteOtherModelKey() },
        )
        Text(tr("Quand l’utiliser"), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp).horizontalScroll(rememberScrollState())) {
            listOf(OTHER_MODEL_OFF to "Jamais", OTHER_MODEL_FALLBACK to "En secours de Gemini", OTHER_MODEL_ALWAYS to "À la place de Gemini").forEach { (v, label) ->
                FilterChip(selected = use == v, onClick = { scope.launch { config.setOtherModelUse(v) } }, label = { Text(tr(label)) })
            }
        }
        Text(
            tr(
                when (use) {
                    OTHER_MODEL_OFF -> "Jarvis n’utilise que Gemini."
                    OTHER_MODEL_ALWAYS -> "Tout ce qu’il peut faire passe par lui ; Gemini prend le relais s’il ne répond pas (PC éteint)."
                    else -> "Gemini d’abord ; l’autre modèle répond quand Gemini n’a plus de quota, ne répond pas, ou qu’aucune clé n’est enregistrée."
                }
            ),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 10.dp)) {
            Text(
                tr("La voix aussi : quand la voix Gemini n’a plus de quota (ou toujours, si l’autre modèle est réglé « à la place »), Jarvis vous écoute avec la reconnaissance du téléphone et répond avec la voix du téléphone choisie dans Voix. Plus lent que Gemini Live, et sans pouvoir l’interrompre."),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = voice, onCheckedChange = { scope.launch { config.setOtherModelVoice(it) } })
        }
    }
}
