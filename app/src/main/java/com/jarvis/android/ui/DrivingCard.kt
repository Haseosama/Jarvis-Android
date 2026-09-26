package com.jarvis.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DriveEta
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
import com.jarvis.android.JarvisApp
import com.jarvis.android.driving.DEFAULT_DRIVING_REPLY
import com.jarvis.android.driving.DrivingMode
import com.jarvis.android.driving.DrivingSettings
import com.jarvis.android.i18n.tr
import com.jarvis.android.i18n.trf
import com.jarvis.android.notifications.JarvisNotificationListener

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Driving mode: when it starts, whether messages are read out, and the opt-in automatic answer. */
@Composable
internal fun DrivingCard() {
    val context = LocalContext.current
    val container = remember { (context.applicationContext as JarvisApp).container }
    val store = container.drivingStore
    var s by remember { mutableStateOf(store.load()) }
    var active by remember { mutableStateOf(DrivingMode.active) }
    var replyField by remember { mutableStateOf(s.replyText) }
    fun save(change: (DrivingSettings) -> DrivingSettings) { s = store.update(change) }
    val car = remember { container.parkingStore.load().carBluetoothName }
    SettingsCard(tr("Mode conduite"), Icons.Filled.DriveEta, initiallyExpanded = false) {
        Text(
            tr("« Mode conduite » ou « je prends la route » : Jarvis répond en une ou deux phrases, lit à voix haute les messages qui arrivent et peut répondre « je conduis » à votre place. « Arrête le mode conduite » pour finir."),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            if (active) tr("Actif en ce moment.") else tr("Inactif."),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp),
        )
        OutlinedButton(onClick = {
            if (DrivingMode.active) DrivingMode.stop(context) else DrivingMode.start(context, byCar = false)
            active = DrivingMode.active
        }, modifier = Modifier.padding(top = 4.dp)) { Text(if (active) tr("Arrêter") else tr("Activer maintenant")) }
        SwitchRow(tr("Activer avec le Bluetooth de la voiture"), s.autoStart) { v -> save { it.copy(autoStart = v) } }
        if (s.autoStart) {
            Text(
                if (car.isBlank()) tr("Choisissez d’abord la voiture dans la carte « Voiture garée ».") else trf("Voiture : {0}", car),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        SwitchRow(tr("Lire les messages à voix haute"), s.readMessages) { v -> save { it.copy(readMessages = v) } }
        if (s.readMessages && !JarvisNotificationListener.isEnabled(context)) {
            Text(tr("Il faut l’accès aux notifications pour Jarvis (carte Notifications)."), style = MaterialTheme.typography.bodySmall)
        }
        SwitchRow(tr("Répondre automatiquement « je conduis »"), s.autoReply) { v -> save { it.copy(autoReply = v) } }
        Text(
            tr("Désactivé par défaut. Activé, pendant le mode conduite, Jarvis répond avec le bouton « Répondre » de la notification : une fois par personne toutes les 30 minutes, jamais dans un groupe, 10 réponses par heure au plus, chacune notée dans « Derniers messages envoyés par Jarvis »."),
            style = MaterialTheme.typography.bodySmall,
        )
        if (s.autoReply) {
            OutlinedTextField(
                value = replyField,
                onValueChange = { v -> replyField = v.take(200); save { it.copy(replyText = replyField.ifBlank { DEFAULT_DRIVING_REPLY }) } },
                label = { Text(tr("Texte de la réponse")) },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }
    }
}
