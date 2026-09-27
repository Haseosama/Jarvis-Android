package com.jarvis.android.ui

import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.jarvis.android.avatar.CharacterMesh
import com.jarvis.android.avatar.CharacterRenderer
import com.jarvis.android.avatar.importer.CharacterImport
import com.jarvis.android.avatar.importer.ImportSession
import com.jarvis.android.i18n.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Imports a character from a file the user picked (a .glb, or a Sketchfab .zip): it is read, shown from the front, the user drags four
 * markers onto the eyes, the mouth and the chin, names it, and it is built and added to the faces. [onDone] gets its folder, or null.
 */
@Composable
internal fun AvatarImportDialog(uri: Uri, onDone: (String?) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var session by remember { mutableStateOf<ImportSession?>(null) }
    var preview by remember { mutableStateOf<CharacterMesh?>(null) }
    var busy by remember { mutableStateOf(tr("Lecture du modèle…")) }
    var error by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    val markers = remember { mutableStateMapOf<String, FloatArray>().apply { putAll(CharacterImport.defaultMarkers()) } }

    LaunchedEffect(uri) {
        try {
            withContext(Dispatchers.Default) {
                val s = CharacterImport.open(context, uri)
                session = s
                preview = CharacterImport.preview(s)
            }
            busy = ""
        } catch (e: CharacterImport.TooLarge) {
            error = tr("Fichier trop volumineux (plus de 150 Mo).")
        } catch (e: Throwable) {
            error = tr("Ce modèle ne peut pas être importé.") + " (" + (e.message ?: e.javaClass.simpleName) + ")"
        }
    }

    Dialog(onDismissRequest = { if (busy.isEmpty()) onDone(null) }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(tr("Importer un avatar"), style = MaterialTheme.typography.titleMedium)
                val p = preview
                when {
                    error != null -> {
                        Text(error!!, modifier = Modifier.padding(vertical = 16.dp))
                        Button(onClick = { onDone(null) }) { Text(tr("Fermer")) }
                    }
                    busy.isNotEmpty() || p == null -> {
                        CircularProgressIndicator(Modifier.padding(24.dp))
                        Text(busy)
                    }
                    else -> {
                        Text(
                            tr("Faites glisser les repères sur les yeux, la bouche et le menton, puis enregistrez."),
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 6.dp),
                        )
                        val renderer = remember(p) { CharacterRenderer(p) }
                        val pose = remember { CharacterRenderer.Pose() }
                        val primary = MaterialTheme.colorScheme.primary.toArgb()
                        val bg = MaterialTheme.colorScheme.background.toArgb()
                        var dragging by remember { mutableStateOf<String?>(null) }
                        val labels = mapOf("eyeL" to tr("Œil gauche"), "eyeR" to tr("Œil droit"), "mouth" to tr("Bouche"), "chin" to tr("Menton"))
                        Canvas(
                            Modifier.fillMaxWidth().aspectRatio(1f).pointerInput(p) {
                                detectDragGestures(
                                    onDragStart = { at ->
                                        dragging = markers.entries.minByOrNull { (_, m) ->
                                            val sp = renderer.toScreen(m[0], m[1]); (sp - at).getDistance()
                                        }?.takeIf { (_, m) -> (renderer.toScreen(m[0], m[1]) - at).getDistance() < 70.dp.toPx() }?.key
                                    },
                                    onDragEnd = { dragging = null },
                                    onDrag = { change, _ ->
                                        val key = dragging ?: return@detectDragGestures
                                        val q = renderer.fromScreen(change.position)
                                        markers[key] = floatArrayOf(q.x, q.y)
                                    },
                                )
                            },
                        ) {
                            val r = size.minDimension * 0.36f
                            renderer.draw(this, pose, size.width / 2f, size.height * 0.44f, r, primary, bg, aura = false)
                            for ((key, m) in markers) {
                                val c = renderer.toScreen(m[0], m[1])
                                val col = if (key == "chin") Color(0xFF4FC3F7) else if (key == "mouth") Color(0xFFFF8A80) else Color(0xFF69F0AE)
                                drawCircle(Color.Black.copy(alpha = 0.5f), radius = 14.dp.toPx(), center = c, style = Stroke(5.dp.toPx()))
                                drawCircle(col, radius = 14.dp.toPx(), center = c, style = Stroke(2.5.dp.toPx()))
                                drawCircle(col, radius = 2.5.dp.toPx(), center = c)
                                drawContext.canvas.nativeCanvas.drawText(labels[key] ?: key, c.x + 18.dp.toPx(), c.y + 5.dp.toPx(),
                                    android.graphics.Paint().apply { color = col.toArgb(); textSize = 13.dp.toPx(); isAntiAlias = true; setShadowLayer(4f, 0f, 0f, 0xFF000000.toInt()) })
                            }
                        }
                        OutlinedTextField(value = name, onValueChange = { name = it.take(24) }, label = { Text(tr("Nom")) }, singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                            OutlinedButton(onClick = {
                                val s = session ?: return@OutlinedButton
                                busy = tr("Lecture du modèle…")
                                scope.launch {
                                    withContext(Dispatchers.Default) { CharacterImport.turn(s); preview = CharacterImport.preview(s) }
                                    markers.clear(); markers.putAll(CharacterImport.defaultMarkers())
                                    busy = ""
                                }
                            }) { Text(tr("Tourner d'un quart de tour")) }
                            OutlinedButton(onClick = { onDone(null) }) { Text(tr("Annuler")) }
                            Button(onClick = {
                                val s = session ?: return@Button
                                busy = tr("Création de l'avatar…")
                                scope.launch {
                                    try {
                                        val folder = withContext(Dispatchers.Default) { CharacterImport.save(context, s, name, markers.toMap()) }
                                        onDone(folder)
                                    } catch (e: Throwable) {
                                        error = tr("Ce modèle ne peut pas être importé.") + " (" + (e.message ?: e.javaClass.simpleName) + ")"
                                    }
                                }
                            }) { Text(tr("Enregistrer")) }
                        }
                    }
                }
            }
        }
    }
}

