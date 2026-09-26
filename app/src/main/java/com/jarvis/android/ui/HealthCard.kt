package com.jarvis.android.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import com.jarvis.android.health.HEALTH_PERMISSIONS
import com.jarvis.android.health.HealthStatus
import com.jarvis.android.health.grantedHealth
import com.jarvis.android.health.healthStatus
import com.jarvis.android.i18n.tr
import com.jarvis.android.i18n.trf

/** Health Connect: whether it is there, and the read permissions (asked on Health Connect's own screen). */
@Composable
internal fun HealthCard() {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    var granted by remember { mutableStateOf<Set<String>>(emptySet()) }
    val status = remember(tick) { healthStatus(context) }
    LaunchedEffect(tick) { granted = grantedHealth(context) }
    val ask = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { tick++ }
    SettingsCard(tr("Santé"), Icons.Filled.Favorite, initiallyExpanded = false) {
        Text(
            tr("« Combien de pas aujourd’hui ? », « comment j’ai dormi ? », « mon rythme cardiaque » : Jarvis lit ces chiffres dans Health Connect (montre, compteur de pas, application de sport) et les ajoute au briefing du matin. Il n’écrit rien et ne garde rien."),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 6.dp),
        )
        when (status) {
            HealthStatus.UNAVAILABLE -> Text(tr("Health Connect n’est pas disponible sur ce téléphone."), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
            HealthStatus.NEEDS_UPDATE -> {
                Text(tr("Health Connect doit être installé ou mis à jour."), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
                OutlinedButton(onClick = {
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.apps.healthdata")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (_: Exception) {
                    }
                }, modifier = Modifier.padding(top = 4.dp)) { Text(tr("Ouvrir le Play Store")) }
            }
            HealthStatus.AVAILABLE -> {
                Text(
                    when {
                        granted.containsAll(HEALTH_PERMISSIONS) -> tr("Accès à Health Connect : accordé ✓")
                        granted.isEmpty() -> tr("Accès à Health Connect : non accordé.")
                        else -> trf("Accès à Health Connect : partiel ({0} sur {1}).", granted.intersect(HEALTH_PERMISSIONS).size, HEALTH_PERMISSIONS.size)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
                if (!granted.containsAll(HEALTH_PERMISSIONS)) {
                    OutlinedButton(onClick = { try { ask.launch(HEALTH_PERMISSIONS) } catch (_: Exception) { } }, modifier = Modifier.padding(top = 4.dp)) {
                        Text(tr("Autoriser la lecture dans Health Connect"))
                    }
                }
            }
        }
    }
}
