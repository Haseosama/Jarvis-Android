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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import kotlinx.coroutines.launch

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
