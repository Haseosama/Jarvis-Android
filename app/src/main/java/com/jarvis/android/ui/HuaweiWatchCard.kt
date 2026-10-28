package com.jarvis.android.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.jarvis.android.JarvisApp
import com.jarvis.android.i18n.tr
import com.jarvis.android.i18n.trf
import com.jarvis.android.memory.ConfigStore
import com.jarvis.android.wearable.HuaweiWatch
import com.jarvis.android.wearable.phoneSigningSha256
import kotlinx.coroutines.launch

/** The Huawei watch (Watch GT 4…): on or off, the watch app's name and fingerprint, and what to put in the watch app. */
@Composable
internal fun HuaweiWatchCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val config = remember { (context.applicationContext as JarvisApp).container.configStore }
    val enabled by config.huaweiWatchEnabled.collectAsState(initial = false)
    val savedPackage by config.huaweiWatchPackage.collectAsState(initial = ConfigStore.DEFAULT_HUAWEI_WATCH_PACKAGE)
    val savedFingerprint by config.huaweiWatchFingerprint.collectAsState(initial = "")
    val status by HuaweiWatch.status.collectAsState()
    var pkg by remember(savedPackage) { mutableStateOf(savedPackage) }
    var fingerprint by remember(savedFingerprint) { mutableStateOf(savedFingerprint) }
    val mySha = remember { phoneSigningSha256(context).orEmpty() }
    SettingsCard(tr("Montre Huawei"), Icons.Filled.Watch, initiallyExpanded = false) {
        Text(
            tr("La Watch GT 4 n’installe pas d’appli Android, mais la petite appli Jarvis pour la montre (dossier watch du projet) parle à Jarvis sur ce téléphone par Huawei Santé. Touchez « Ma journée », « Météo », « Prochain rappel »… sur la montre : Jarvis répond avec tous ses outils et la réponse s’affiche au poignet. La montre n’a pas de micro pour les applis : on demande par boutons."),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 6.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Text(tr("Relier la montre"), modifier = Modifier.weight(1f))
            Switch(checked = enabled, onCheckedChange = { on ->
                scope.launch {
                    config.setHuaweiWatchEnabled(on)
                    if (on) HuaweiWatch.start(context) else HuaweiWatch.stop(context)
                }
            })
        }
        if (enabled) Text(status, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
        OutlinedButton(onClick = { HuaweiWatch.requestPermission(context) }, modifier = Modifier.padding(top = 4.dp)) {
            Text(tr("Autoriser dans Huawei Santé"))
        }
        OutlinedTextField(
            value = pkg,
            onValueChange = { pkg = it.trim().take(120) },
            label = { Text(tr("Nom de l’appli montre")) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        OutlinedTextField(
            value = fingerprint,
            onValueChange = { fingerprint = it.trim().take(500) },
            label = { Text(tr("Empreinte de l’appli montre")) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
        OutlinedButton(
            onClick = {
                scope.launch {
                    config.setHuaweiWatchPackage(pkg)
                    config.setHuaweiWatchFingerprint(fingerprint)
                    HuaweiWatch.stop(context)
                    if (enabled) HuaweiWatch.start(context)
                }
            },
            enabled = pkg != savedPackage || fingerprint != savedFingerprint,
            modifier = Modifier.padding(top = 4.dp),
        ) { Text(tr("Enregistrer")) }
        Text(
            trf("À mettre dans l’appli montre (fichier jarvis-config.js) : nom du téléphone « {0} », empreinte « {1} ».", context.packageName, mySha.ifEmpty { "?" }),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 8.dp),
        )
        if (mySha.isNotEmpty()) TextButton(onClick = { clipboard.setText(AnnotatedString(mySha)) }) { Text(tr("Copier l’empreinte du téléphone")) }
        Text(
            tr("À faire une fois : compte développeur Huawei, demande du service Wear Engine pour Jarvis dans AppGallery Connect (avec le nom et l’empreinte du téléphone ci-dessus), puis l’appli montre installée depuis DevEco Studio. Le guide pas à pas est dans watch/README.md."),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
