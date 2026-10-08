package com.jarvis.android.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.jarvis.android.JarvisApp
import com.jarvis.android.i18n.tr
import com.jarvis.android.images.ImagePcTool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Images IA: pictures made by the generator on the PC (the image_pc tool), typed here as well as asked by voice, the adult
 * switch, and the installation of the generator on the PC when it has none.
 */
@Composable
internal fun ImagesCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val container = remember { (context.applicationContext as JarvisApp).container }
    val adult by container.configStore.imagesAdult.collectAsState(initial = false)
    var prompt by remember { mutableStateOf("") }
    var format by remember { mutableStateOf("portrait") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<ImageBitmap?>(null) }
    var last by remember { mutableStateOf<ImagePcTool.SavedPicture?>(null) }

    fun askPc(action: String) {
        busy = true
        status = null
        scope.launch {
            try {
                status = container.pcRemote.image(buildJsonObject { put("action", action) }).text
            } finally {
                busy = false
            }
        }
    }

    SettingsCard(tr("Images IA"), Icons.Filled.Brush, initiallyExpanded = false) {
        Text(
            tr("Jarvis crée des images à partir d’une description avec le générateur du PC appairé (Fooocus, ComfyUI ou Forge, via Jarvis 2.0) : dites par exemple « crée une image d’un phare dans la tempête ». Sans générateur sur le PC, Jarvis 2.0 installe ComfyUI lui-même (environ 9 Go, carte graphique NVIDIA conseillée). Seul du texte est envoyé, jamais une photo."),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 6.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 10.dp)) {
            Text(
                tr("Autoriser le contenu adulte. Les images adultes restent dans le dossier privé de Jarvis, hors de la galerie. Jamais de personne réelle ni de mineur, réglage ou non."),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = adult, onCheckedChange = { scope.launch { container.configStore.setImagesAdult(it) } })
        }
        OutlinedTextField(
            value = prompt,
            onValueChange = { prompt = it.take(1500) },
            label = { Text(tr("Description de l’image")) },
            minLines = 2,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        Row(modifier = Modifier.padding(top = 4.dp)) {
            listOf("portrait" to tr("Portrait"), "carre" to tr("Carré"), "paysage" to tr("Paysage")).forEach { (key, label) ->
                FilterChip(selected = format == key, onClick = { format = key }, label = { Text(label) })
                Spacer(Modifier.width(6.dp))
            }
        }
        Row(modifier = Modifier.padding(top = 4.dp)) {
            Button(
                onClick = {
                    busy = true
                    status = tr("Création sur le PC… (le premier démarrage du générateur peut prendre une minute)")
                    scope.launch {
                        try {
                            val made = ImagePcTool.create(container, prompt, format, "", -1, adult)
                            status = made.text
                            made.png?.let { png ->
                                preview = withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(png, 0, png.size)?.asImageBitmap() }
                            }
                            last = made.picture
                        } finally {
                            busy = false
                        }
                    }
                },
                enabled = prompt.isNotBlank() && !busy,
            ) { Text(tr("Créer")) }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { askPc("status") }, enabled = !busy) { Text(tr("État du PC")) }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { askPc("install") }, enabled = !busy) { Text(tr("Installer sur le PC")) }
        }
        status?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp)) }
        preview?.let { bitmap ->
            Image(
                bitmap = bitmap,
                contentDescription = tr("Image créée"),
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).padding(top = 8.dp)
                    .clickable { last?.let { ImagePcTool.open(context, it) } },
            )
        }
    }
}
