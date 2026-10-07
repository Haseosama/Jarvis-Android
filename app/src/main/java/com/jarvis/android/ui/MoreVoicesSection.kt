package com.jarvis.android.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import com.jarvis.android.i18n.Lang
import com.jarvis.android.i18n.tr
import com.jarvis.android.i18n.trf
import com.jarvis.android.offline.OfflineVoice
import com.jarvis.android.rest.AudioPlayer
import com.jarvis.android.rest.VoicePreview
import com.jarvis.android.voices.EDGE_VOICES
import com.jarvis.android.voices.OnlineVoice
import com.jarvis.android.voices.PhoneVoice
import com.jarvis.android.voices.listPhoneVoices
import com.jarvis.android.voices.phoneChoice
import com.jarvis.android.voices.phoneVoiceLabel
import com.jarvis.android.voices.voiceKind
import com.jarvis.android.voices.voiceNameOf
import com.jarvis.android.voices.VoiceKind
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Locale

/** One row of a voice list: an id to store, what to show, and how to hear it. */
private data class VoiceRow(val id: String, val label: String)

/**
 * The other voices, under Gemini's in the Voix card: another online voice (Microsoft Edge, free; ElevenLabs, with a key) and the
 * phone voice used offline. Each can be heard before it is chosen.
 */
@Composable
internal fun MoreVoicesSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val container = remember { (context.applicationContext as JarvisApp).container }
    val config = container.configStore
    val online by config.onlineVoice.collectAsState(initial = "")
    val offline by config.offlineVoice.collectAsState(initial = "")
    val assistantName by config.assistantName.collectAsState(initial = "JARVIS")
    val language by config.speechLanguage.collectAsState(initial = "")
    val player = remember { AudioPlayer(context.applicationContext) }
    var playing by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var onlineList by remember { mutableStateOf<List<VoiceRow>?>(null) }
    var phoneList by remember { mutableStateOf<List<PhoneVoice>?>(null) }
    var loadingPhone by remember { mutableStateOf(false) }
    var hasElevenKey by remember { mutableStateOf(!config.getElevenLabsKey().isNullOrBlank()) }
    var keyField by remember { mutableStateOf("") }
    val sample = VoicePreview.sample(Lang.isEnglish, assistantName)

    fun stop() {
        job?.cancel(); player.stop(); playing = null
    }
    DisposableEffect(Unit) { onDispose { stop() } }
    fun hear(id: String) {
        stop()
        error = null
        playing = id
        job = scope.launch {
            try {
                val phone = phoneChoice(id)
                if (phone != null) {
                    val voice = OfflineVoice(context.applicationContext)
                    try {
                        if (voice.init(Locale.FRENCH, phone.first, phone.second)) voice.speak(sample) else error = tr("Cette voix du téléphone ne répond pas.")
                    } finally {
                        voice.shutdown()
                    }
                } else {
                    player.play { emit -> container.onlineSpeaker.speak(id, sample, language.ifBlank { null }, emit) }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            } finally {
                if (playing == id) playing = null
            }
        }
    }

    Text(tr("Autre voix en ligne"), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp))
    Text(
        if (voiceKind(online) == VoiceKind.GEMINI) tr("Aucune : la voix Gemini ci-dessus parle.")
        else trf("{0} lit les réponses de Gemini.", voiceNameOf(online)),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = 4.dp),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
        OutlinedButton(onClick = {
            scope.launch {
                val eleven: List<OnlineVoice> = try {
                    container.onlineSpeaker.elevenVoices()
                } catch (e: Exception) {
                    error = e.message; emptyList()
                }
                onlineList = listOf(VoiceRow("", tr("Voix Gemini (aucune autre)"))) +
                    (EDGE_VOICES + eleven).map { VoiceRow(it.id, it.label(Lang.isEnglish) + if (it.id.startsWith("eleven:")) " · ElevenLabs" else " · Edge") }
            }
        }) { Text(tr("Choisir")) }
    }
    Text(
        tr("Les voix Microsoft Edge sont gratuites et sans clé (comme dans Hermes Agent). Gemini continue de mener la conversation ; une autre voix relit ses réponses phrase par phrase, donc elle commence à parler un peu plus tard. Si elle échoue, la voix Gemini reprend."),
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(top = 4.dp),
    )
    if (hasElevenKey) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Text(tr("Clé ElevenLabs enregistrée ✓"), modifier = Modifier.weight(1f))
            TextButton(onClick = { scope.launch { config.deleteElevenLabsKey(); hasElevenKey = false } }) { Text(tr("Supprimer")) }
        }
    } else {
        Text(tr("ElevenLabs (payant, facultatif) : créez une clé API sur elevenlabs.io et collez-la ici pour ajouter vos voix ElevenLabs à la liste."), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
        OutlinedTextField(
            value = keyField,
            onValueChange = { keyField = it.trim().take(200) },
            label = { Text(tr("Clé ElevenLabs")) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { scope.launch { if (config.saveElevenLabsKey(keyField)) { hasElevenKey = true; keyField = "" } } }, enabled = keyField.length >= 10) {
                Text(tr("Enregistrer la clé"))
            }
            TextButton(onClick = {
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://elevenlabs.io/app/settings/api-keys")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: Exception) {
                }
            }) { Text(trf("Ouvrir {0}", "elevenlabs.io")) }
        }
    }

    Text(tr("Voix hors ligne"), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp))
    Text(
        phoneChoice(offline)?.let { (engine, name) -> phoneVoiceLabel(PhoneVoice(engine, engine.substringAfterLast('.'), name, "", true, 0)) }
            ?: tr("Automatique : la meilleure voix du téléphone qui marche sans réseau."),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = 4.dp),
    )
    OutlinedButton(onClick = {
        loadingPhone = true
        scope.launch {
            phoneList = try { listPhoneVoices(context.applicationContext, language.ifBlank { "fr" }.substringBefore('-')) } catch (_: Exception) { emptyList() }
            loadingPhone = false
        }
    }, enabled = !loadingPhone, modifier = Modifier.padding(top = 4.dp)) {
        if (loadingPhone) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text(tr("Choisir"))
    }
    Text(
        tr("Ce sont les voix des moteurs de synthèse installés sur le téléphone. Pour en avoir plus, installez par exemple SherpaTTS (voix Piper, comme dans Hermes Agent) ou RHVoice depuis F-Droid ou le Play Store, téléchargez-y une voix française, puis revenez la choisir ici."),
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(top = 4.dp),
    )
    error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp)) }

    onlineList?.let { rows ->
        VoicePicker(tr("Autre voix en ligne"), rows, online, playing, ::hear, ::stop) { chosen ->
            stop(); onlineList = null
            if (chosen != null) scope.launch { config.setOnlineVoice(chosen) }
        }
    }
    phoneList?.let { voices ->
        val rows = listOf(VoiceRow("", tr("Automatique"))) + voices.map { VoiceRow(it.id, phoneVoiceLabel(it)) }
        VoicePicker(tr("Voix hors ligne"), rows, offline, playing, ::hear, ::stop, empty = if (voices.isEmpty()) tr("Aucune voix hors ligne installée sur le téléphone.") else null) { chosen ->
            stop(); phoneList = null
            if (chosen != null) scope.launch { config.setOfflineVoice(chosen) }
        }
    }
}

@Composable
private fun VoicePicker(
    title: String,
    rows: List<VoiceRow>,
    current: String,
    playing: String?,
    hear: (String) -> Unit,
    stop: () -> Unit,
    empty: String? = null,
    onDone: (String?) -> Unit,
) {
    var chosen by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = { onDone(null) },
        title = { Text(title) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                empty?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                rows.forEach { row ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { chosen = row.id }) {
                        RadioButton(selected = chosen == row.id, onClick = { chosen = row.id })
                        Text(row.label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        if (row.id.isNotEmpty()) {
                            IconButton(onClick = { if (playing == row.id) stop() else hear(row.id) }) {
                                if (playing == row.id) Icon(Icons.Filled.Stop, contentDescription = tr("Arrêter l’échantillon"))
                                else Icon(Icons.Filled.PlayArrow, contentDescription = tr("Écouter cette voix"))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onDone(chosen) }) { Text(tr("Valider")) } },
        dismissButton = { TextButton(onClick = { onDone(null) }) { Text(tr("Annuler")) } },
    )
}
