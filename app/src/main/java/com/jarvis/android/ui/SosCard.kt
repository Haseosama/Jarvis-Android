package com.jarvis.android.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.jarvis.android.JarvisApp
import com.jarvis.android.i18n.tr
import com.jarvis.android.i18n.trf
import com.jarvis.android.sos.SOS_MAX_CONTACTS
import com.jarvis.android.sos.SosContact
import com.jarvis.android.sos.withContact

/** Reads the name and number of the phone row the contact picker returned (readable without the contacts permission). */
private fun pickedContact(context: Context, uri: Uri): SosContact? = try {
    context.contentResolver.query(
        uri,
        arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
        null, null, null,
    )?.use { c -> if (c.moveToFirst()) SosContact(c.getString(0).orEmpty(), c.getString(1).orEmpty()) else null }
} catch (_: Exception) {
    null
}

private fun granted(context: Context, permission: String) =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

/** The SOS alert: up to three trusted contacts, and whether the first is also called. Nothing is sent while the list is empty. */
@Composable
internal fun SosCard() {
    val context = LocalContext.current
    val store = remember { (context.applicationContext as JarvisApp).container.sosStore }
    var data by remember { mutableStateOf(store.load()) }
    var smsGranted by remember { mutableStateOf(granted(context, Manifest.permission.SEND_SMS)) }
    var callGranted by remember { mutableStateOf(granted(context, Manifest.permission.CALL_PHONE)) }
    val askSms = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { smsGranted = it }
    val askCall = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        callGranted = ok
        if (ok) data = store.update { it.copy(callFirst = true) }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val contact = result.data?.data?.let { pickedContact(context, it) } ?: return@rememberLauncherForActivityResult
        data = store.update { withContact(it, contact) }
        if (!smsGranted) askSms.launch(Manifest.permission.SEND_SMS)
    }
    SettingsCard(tr("Urgence / SOS"), Icons.Filled.Warning, initiallyExpanded = false) {
        Text(
            tr("Dites « au secours » ou « SOS » : après 10 secondes pendant lesquelles vous pouvez annuler (« annule » ou le bouton de la notification), Jarvis envoie un SMS avec votre position à vos contacts d’urgence. Il n’appelle jamais les secours : en cas de danger, appelez le 112."),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 6.dp),
        )
        if (data.contacts.isEmpty()) {
            Text(tr("Aucun contact d’urgence : l’alerte est désactivée."), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
        }
        for (c in data.contacts) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Text("${c.name} · ${c.number}", modifier = Modifier.weight(1f))
                TextButton(onClick = { data = store.update { d -> d.copy(contacts = d.contacts - c) } }) { Text(tr("Retirer")) }
            }
        }
        if (data.contacts.size < SOS_MAX_CONTACTS) {
            OutlinedButton(
                onClick = {
                    try {
                        pick.launch(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI))
                    } catch (_: Exception) {
                    }
                },
                modifier = Modifier.padding(top = 8.dp),
            ) { Text(trf("Ajouter un contact d’urgence ({0} au plus)", SOS_MAX_CONTACTS)) }
        }
        if (data.contacts.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text(tr("Appeler aussi le premier contact après le SMS"), modifier = Modifier.weight(1f))
                Switch(checked = data.callFirst && callGranted, onCheckedChange = { on ->
                    if (on && !callGranted) askCall.launch(Manifest.permission.CALL_PHONE)
                    else data = store.update { it.copy(callFirst = on) }
                })
            }
            if (!smsGranted) {
                Text(tr("L’autorisation d’envoyer des SMS manque : sans elle, l’alerte ne peut pas partir."), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                OutlinedButton(onClick = { askSms.launch(Manifest.permission.SEND_SMS) }, modifier = Modifier.padding(top = 4.dp)) { Text(tr("Autoriser l’envoi de SMS")) }
            }
        }
    }
}

/** Photo search: the gallery permission, and the one that lets Jarvis read where a photo was taken (for "mes photos à Brest"). */
@Composable
internal fun PhotosCard() {
    val context = LocalContext.current
    var photos by remember { mutableStateOf(com.jarvis.android.photos.hasPhotoPermission(context)) }
    var places by remember { mutableStateOf(com.jarvis.android.photos.hasPhotoLocationPermission(context)) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        photos = com.jarvis.android.photos.hasPhotoPermission(context)
        places = com.jarvis.android.photos.hasPhotoLocationPermission(context)
    }
    SettingsCard(tr("Photos"), Icons.Filled.Photo, initiallyExpanded = false) {
        Text(
            tr("« Mes photos d’août », « les photos de samedi », « mes photos à Brest » : Jarvis compte les photos de ces jours-là et ouvre la plus récente dans la galerie. Il ne voit pas leur contenu et aucune photo ne quitte le téléphone ; seul le nom du lieu demandé est cherché en ligne."),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            when {
                photos && places -> tr("Accès aux photos et à leur position : accordé ✓")
                photos -> tr("Accès aux photos accordé ✓, mais pas à leur position : la recherche par lieu ne marchera pas.")
                else -> tr("Accès aux photos : non accordé.")
            },
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp),
        )
        if (!photos || !places) {
            OutlinedButton(
                onClick = {
                    val wanted = buildList {
                        add(com.jarvis.android.photos.photoPermission())
                        if (android.os.Build.VERSION.SDK_INT >= 29) add(Manifest.permission.ACCESS_MEDIA_LOCATION)
                    }
                    ask.launch(wanted.toTypedArray())
                },
                modifier = Modifier.padding(top = 8.dp),
            ) { Text(tr("Autoriser l’accès aux photos")) }
        }
    }
}
