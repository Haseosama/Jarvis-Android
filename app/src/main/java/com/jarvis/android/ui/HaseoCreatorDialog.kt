package com.jarvis.android.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.jarvis.android.avatar.AvatarController
import com.jarvis.android.avatar.AvatarView
import com.jarvis.android.avatar.FaceCustomizer
import com.jarvis.android.avatar.HASEO_INDEX
import com.jarvis.android.avatar.Sculpt
import com.jarvis.android.avatar.avatarFace
import com.jarvis.android.core.JarvisState
import com.jarvis.android.i18n.tr
import com.jarvis.android.i18n.trf
import com.jarvis.android.memory.ConfigStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Haseo's character creator, ported from Jarvis 2.0 (src/ui/AvatarCreator.jsx): sliders by part of the face, ready-made faces, a
 * random one, saved looks, and the polygon editor for retouches point by point. The face on screen shows the draft at once; nothing is
 * saved before « Appliquer », which also makes Haseo the face shown.
 */
@Composable
internal fun HaseoCreatorDialog(controller: AvatarController, configStore: ConfigStore, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val haseo = avatarFace(HASEO_INDEX)
    var draft by remember { mutableStateOf(controller.custom) }
    var sculpt by remember { mutableStateOf<Sculpt>(controller.sculptOf(haseo)) }
    var group by remember { mutableStateOf("face") }
    var editor by remember { mutableStateOf(false) }
    var lookName by remember { mutableStateOf("") }
    val looks by configStore.avatarHaseoLooks.collectAsState(initial = emptyList())

    DisposableEffect(Unit) {
        controller.previewModel = HASEO_INDEX
        onDispose {
            controller.previewModel = null
            controller.previewCustom = null
            controller.previewSculpt = null
        }
    }
    // the head is rebuilt once the slider rests a moment, not at every step of it
    LaunchedEffect(draft) { delay(150); controller.previewCustom = draft }
    LaunchedEffect(sculpt) { controller.previewSculpt = sculpt }

    fun setValue(id: String, v: Float) { draft = FaceCustomizer.normalize(draft + (id to v)) }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(8.dp)) {
                Text(tr("Créateur de personnage — Haseo"), style = MaterialTheme.typography.titleMedium)
                Text(tr("Façonnez le visage comme dans un jeu vidéo. Les couleurs, la peau et les circuits ne changent pas."), style = MaterialTheme.typography.bodySmall)
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    AvatarView(controller, JarvisState.CONNECTING, 0f, Modifier.fillMaxWidth(0.62f))
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        for ((id, label, _) in FaceCustomizer.PRESETS) OutlinedButton(onClick = { draft = FaceCustomizer.preset(id) }) { Text(tr(label)) }
                        OutlinedButton(onClick = { draft = FaceCustomizer.random(System.currentTimeMillis()) }) { Text(tr("Aléatoire")) }
                    }
                    Button(onClick = { editor = true }, modifier = Modifier.padding(vertical = 4.dp)) {
                        Text(if (sculpt.isEmpty()) tr("Éditeur de polygones") else trf("Éditeur de polygones ({0} retouches)", sculpt.size))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        for ((id, label) in FaceCustomizer.GROUPS) FilterChip(group == id, { group = id }, { Text(tr(label)) })
                    }
                    for (p in FaceCustomizer.PARAMS.filter { it.group == group }) {
                        val value = draft[p.id] ?: 0f
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                            Text(tr(p.label), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                            Text((if (value > 0) "+" else "") + (value * 100).roundToInt(), style = MaterialTheme.typography.bodySmall)
                            TextButton(enabled = value != 0f, onClick = { setValue(p.id, 0f) }) { Text("↺") }
                        }
                        Slider(value, { setValue(p.id, (it * 100).roundToInt() / 100f) }, valueRange = -1f..1f)
                        Row {
                            Text(tr(p.low), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            Text(tr(p.high), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Text(tr("Mes looks"), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(lookName, { lookName = it.take(30) }, label = { Text(tr("Nom du look")) }, singleLine = true, modifier = Modifier.weight(1f))
                        TextButton(onClick = {
                            val name = lookName.trim().ifEmpty { trf("Look {0}", looks.size + 1) }
                            scope.launch { configStore.setAvatarHaseoLooks(looks.filter { it.first != name } + (name to FaceCustomizer.encode(draft))) }
                            lookName = ""
                        }) { Text(tr("Enregistrer")) }
                    }
                    if (looks.isEmpty()) Text(tr("Aucun look enregistré."), style = MaterialTheme.typography.bodySmall)
                    for ((name, values) in looks) Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(name, modifier = Modifier.weight(1f))
                        TextButton(onClick = { draft = FaceCustomizer.decode(values) }) { Text(tr("Charger")) }
                        TextButton(onClick = { scope.launch { configStore.setAvatarHaseoLooks(looks.filter { it.first != name }) } }) { Text(tr("Supprimer")) }
                    }
                    Text(tr("Les looks ne gardent que les curseurs, pas les retouches de polygones."), style = MaterialTheme.typography.bodySmall)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    OutlinedButton(enabled = draft.isNotEmpty() || sculpt.isNotEmpty(), onClick = { draft = emptyMap(); sculpt = emptyMap() }) { Text(tr("Tout réinitialiser")) }
                    TextButton(onClick = onClose) { Text(tr("Annuler")) }
                    Button(onClick = {
                        controller.custom = draft
                        controller.saveSculpt(haseo, sculpt)
                        scope.launch {
                            configStore.setAvatarHaseoCustom(FaceCustomizer.encode(draft))
                            configStore.setAvatarModel(HASEO_INDEX)
                        }
                        onClose()
                    }) { Text(tr("Appliquer")) }
                }
            }
        }
    }
    if (editor) PolygonEditorDialog(controller, HASEO_INDEX, draft, sculpt, onApply = { sculpt = it; editor = false }, onClose = { editor = false })
}
