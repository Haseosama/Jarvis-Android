package com.jarvis.android.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DoNotDisturbOn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.jarvis.android.i18n.tr
import com.jarvis.android.i18n.trf
import com.jarvis.android.quiet.DEFAULT_QUIET_REPLY
import com.jarvis.android.quiet.QuietMode
import com.jarvis.android.quiet.clockWords
import java.time.ZoneId

/** The quiet mode: Do Not Disturb access, what is on now, and the opt-in automatic answer. */
@Composable
internal fun QuietCard() {
    val context = LocalContext.current
    val store = remember { QuietMode.store(context) }
    var data by remember { mutableStateOf(store.load()) }
    var access by remember { mutableStateOf(QuietMode.hasAccess(context)) }
    var replyField by remember { mutableStateOf(data.replyText) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { access = QuietMode.hasAccess(context); data = store.load() }
    SettingsCard(tr("Ne pas déranger"), Icons.Filled.DoNotDisturbOn, initiallyExpanded = false) {
        Text(
            tr("« Je suis en réunion jusqu’à 15 h », « ne me dérange pas pendant une heure » : Jarvis active Ne pas déranger jusqu’à cette heure. Seuls vos contacts favoris (l’étoile dans Contacts), les appels répétés et les alarmes passent. À la fin, vos réglages reviennent et une notification résume ce que vous avez manqué."),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 6.dp),
        )
        val state = data.state?.takeIf { it.untilMs > System.currentTimeMillis() }
        Text(
            if (state != null) trf("Actif jusqu’à {0}.", clockWords(state.untilMs, ZoneId.systemDefault())) else tr("Inactif."),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp),
        )
        if (state != null) {
            OutlinedButton(onClick = { QuietMode.stop(context); data = store.load() }, modifier = Modifier.padding(top = 4.dp)) { Text(tr("Terminer")) }
        }
        if (!access) {
            Text(tr("Android demande d’autoriser Jarvis à gérer Ne pas déranger."), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            OutlinedButton(onClick = {
                try {
                    context.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: Exception) {
                }
            }, modifier = Modifier.padding(top = 4.dp)) { Text(tr("Autoriser l’accès à Ne pas déranger")) }
        } else {
            Text(tr("Accès à Ne pas déranger : accordé ✓"), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Text(tr("Répondre automatiquement pendant ce temps"), modifier = Modifier.weight(1f))
            Switch(checked = data.autoReply, onCheckedChange = { v -> data = store.update { it.copy(autoReply = v) } })
        }
        Text(
            tr("Désactivé par défaut. Activé, chaque personne qui vous écrit reçoit une réponse avec le bouton « Répondre » de la notification : une fois toutes les 30 minutes, jamais dans un groupe, 10 par heure au plus. {heure} est remplacé par l’heure de fin."),
            style = MaterialTheme.typography.bodySmall,
        )
        if (data.autoReply) {
            OutlinedTextField(
                value = replyField,
                onValueChange = { v -> replyField = v.take(200); data = store.update { it.copy(replyText = replyField.ifBlank { DEFAULT_QUIET_REPLY }) } },
                label = { Text(tr("Texte de la réponse")) },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }
    }
}
