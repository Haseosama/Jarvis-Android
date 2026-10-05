package com.jarvis.android.ui

import android.graphics.Paint
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.jarvis.android.avatar.AvatarController
import com.jarvis.android.avatar.HeadMesh
import com.jarvis.android.avatar.MeshSculpt
import com.jarvis.android.avatar.Sculpt
import com.jarvis.android.avatar.avatarFace
import com.jarvis.android.i18n.tr
import com.jarvis.android.i18n.trf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.hypot

/** The head being edited: its shape before the retouches, its welded topology, its plane of symmetry and its eyes (sorted by x). */
private class EditBase(val mesh: HeadMesh, val topo: MeshSculpt.Topology, val plane: Float, val eyes: List<FloatArray>)

private enum class Tool { SELECT, MOVE, VIEW }

private const val HISTORY_LIMIT = 100

/**
 * The polygon editor, ported from Jarvis 2.0 (src/ui/PolygonEditor.jsx) for a touch screen: points or triangles are picked with a tap
 * (a drag draws a frame), the Move tool drags the selection in the plane of the view with a soft influence along the surface, the View
 * tool turns the head; two fingers zoom and pan whatever the tool. Smoothing, back-to-origin, mirror moves, copying one side of the face
 * on the other, undo and redo. Nothing is kept before « Valider ».
 * [model] is the face, [custom] Haseo's sliders being tried, [initial] the retouches to start from.
 */
@Composable
internal fun PolygonEditorDialog(controller: AvatarController, model: Int, custom: Map<String, Float>, initial: Sculpt, onApply: (Sculpt) -> Unit, onClose: () -> Unit) {
    val face = avatarFace(model)
    val scope = rememberCoroutineScope()
    var base by remember { mutableStateOf<EditBase?>(null) }
    var failed by remember { mutableStateOf(false) }
    var offsets by remember { mutableStateOf(MeshSculpt.normalize(initial)) }
    var live by remember { mutableStateOf<Sculpt?>(null) }
    val undo = remember { ArrayList<Sculpt>() }
    val redo = remember { ArrayList<Sculpt>() }
    var selection by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var tool by remember { mutableStateOf(Tool.SELECT) }
    var byTriangle by remember { mutableStateOf(false) }
    var additive by remember { mutableStateOf(false) }
    var radius by remember { mutableFloatStateOf(0.05f) }
    var mirror by remember { mutableStateOf(false) }
    var step by remember { mutableFloatStateOf(0.004f) }
    var viewMode by remember { mutableIntStateOf(0) }      // 0 solid + wire, 1 solid, 2 wire
    var showPoints by remember { mutableStateOf(true) }
    var symOnly by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var dirty by remember { mutableStateOf(false) }
    var confirmClose by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var cam by remember { mutableStateOf(MeshSculpt.Camera()) }
    var box by remember { mutableStateOf<FloatArray?>(null) }
    // the last scene drawn (what a tap picks in), and the influence of the selection while it is dragged: plain holders, not states
    val sceneRef = remember { arrayOfNulls<MeshSculpt.Scene>(1) }
    val dragRef = remember { arrayOfNulls<Pair<Map<Int, Float>, Map<Int, Float>?>>(1) }

    LaunchedEffect(model, custom) {
        try {
            base = withContext(Dispatchers.Default) {
                val mesh = controller.shapedHead(model, custom, emptyMap())
                val topo = MeshSculpt.topology(mesh)
                val ec = mesh.eyeCentre
                val eyes = (0 until ec.size / 3).map { floatArrayOf(ec[3 * it], ec[3 * it + 1], ec[3 * it + 2]) }.sortedBy { it[0] }
                val mid = if (eyes.size >= 2) 0.5f * (eyes.first()[0] + eyes.last()[0]) else 0f
                EditBase(mesh, topo, MeshSculpt.symmetryPlane(mesh, topo, mid), eyes)
            }
        } catch (_: Exception) {
            failed = true
        }
    }

    fun shown(): Sculpt = live ?: offsets
    fun commit(next: Sculpt, note: String = "") {
        undo += offsets
        if (undo.size > HISTORY_LIMIT) undo.removeAt(0)
        redo.clear()
        offsets = next
        dirty = true
        message = note
    }
    fun weights(b: EditBase): Pair<Map<Int, Float>, Map<Int, Float>?> {
        val verts = MeshSculpt.displayVerts(b.mesh.verts, offsets, b.topo.limit)
        return MeshSculpt.softWeights(b.topo, verts, selection, radius) to
            if (mirror) MeshSculpt.mirrorWeights(b.topo, verts, selection, radius, b.plane) else null
    }
    fun nudge(dir: FloatArray) {
        val b = base ?: return
        if (selection.isEmpty()) { message = tr("Sélectionnez d’abord des points."); return }
        val (w, m) = weights(b)
        commit(MeshSculpt.addDelta(offsets, b.topo, w, FloatArray(3) { dir[it] * step }, m))
    }
    fun select(reps: Collection<Int>) {
        selection = if (additive) selection.toMutableSet().apply { for (r in reps) if (!add(r)) remove(r) } else reps.toSet()
    }

    Dialog(onDismissRequest = { if (dirty) confirmClose = true else onClose() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(8.dp)) {
                Text(trf("Éditeur de polygones — {0}", tr(face.label)), style = MaterialTheme.typography.titleMedium)
                Text(
                    tr("Touchez un point pour le choisir, glissez pour un cadre. Outil Déplacer : glissez les points. Outil Vue : tournez la tête. Deux doigts : zoom et déplacement de la vue."),
                    style = MaterialTheme.typography.bodySmall,
                )
                Box(Modifier.fillMaxWidth().aspectRatio(1f).padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
                    val b = base
                    when {
                        failed -> Text(tr("Impossible de charger le maillage du visage."))
                        b == null -> CircularProgressIndicator()
                        else -> SculptCanvas(
                            b, shown(), selection, cam, if (tool == Tool.MOVE && selection.isNotEmpty() && radius > 0f) radius else 0f,
                            viewMode, showPoints, box,
                            onScene = { sceneRef[0] = it },
                            onGesture = { g ->
                                when (g) {
                                    is SculptGesture.Tap -> sceneRef[0]?.let { s ->
                                        if (byTriangle) {
                                            val t = MeshSculpt.pickTriangle(s, g.x, g.y)
                                            if (t >= 0) select(MeshSculpt.trianglePoints(b.topo, t)) else if (!additive) selection = emptySet()
                                        } else {
                                            val hit = MeshSculpt.pickVertex(s, b.topo, g.x, g.y, 28f)
                                            if (hit >= 0) select(listOf(hit)) else if (!additive) selection = emptySet()
                                        }
                                    }
                                    is SculptGesture.Drag -> when {
                                        tool == Tool.VIEW -> cam = cam.with(yaw = cam.yaw + g.dx * 0.008f, pitch = (cam.pitch + g.dy * 0.008f).coerceIn(-1.4f, 1.4f))
                                        tool == Tool.MOVE && selection.isNotEmpty() -> {
                                            val (w, m) = dragRef[0] ?: weights(b).also { dragRef[0] = it }
                                            live = MeshSculpt.addDelta(live ?: offsets, b.topo, w, cam.screenToWorld(g.dx, g.dy), m)
                                        }
                                        else -> box = floatArrayOf(g.x0, g.y0, g.x, g.y)
                                    }
                                    is SculptGesture.End -> {
                                        dragRef[0] = null
                                        live?.let { commit(it); live = null }
                                        box?.let { fr -> sceneRef[0]?.let { s -> select(MeshSculpt.boxSelect(s, b.topo, fr[0], fr[1], fr[2], fr[3])) } }
                                        box = null
                                    }
                                    is SculptGesture.Zoom -> {
                                        val zoom = (cam.zoom * g.factor).coerceIn(0.4f, 40f)
                                        val k = zoom / cam.zoom
                                        val px = g.cx - cam.width / 2; val py = g.cy - cam.height / 2
                                        cam = cam.with(zoom = zoom, panX = px - (px - cam.panX) * k + g.panX, panY = py - (py - cam.panY) * k + g.panY)
                                    }
                                }
                            },
                        )
                    }
                }
                Text(
                    trf("{0} point(s) · {1} sommet(s) retouché(s)", selection.size, shown().size) + if (message.isNotEmpty()) " — $message" else "",
                    style = MaterialTheme.typography.bodySmall,
                )
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    val b = base
                    ChipRow {
                        FilterChip(tool == Tool.SELECT, { tool = Tool.SELECT }, { Text(tr("Sélectionner")) })
                        FilterChip(tool == Tool.MOVE, { tool = Tool.MOVE }, { Text(tr("Déplacer")) })
                        FilterChip(tool == Tool.VIEW, { tool = Tool.VIEW }, { Text(tr("Vue")) })
                        FilterChip(!byTriangle, { byTriangle = false }, { Text(tr("Sommet")) })
                        FilterChip(byTriangle, { byTriangle = true }, { Text(tr("Polygone")) })
                        FilterChip(additive, { additive = !additive }, { Text(tr("Ajouter à la sélection")) })
                    }
                    ChipRow {
                        OutlinedButton(onClick = { if (b != null) selection = MeshSculpt.grow(b.topo, selection) }) { Text(tr("Agrandir")) }
                        OutlinedButton(onClick = { if (b != null) selection = MeshSculpt.shrink(b.topo, selection) }) { Text(tr("Réduire")) }
                        OutlinedButton(onClick = { selection = emptySet() }) { Text(tr("Aucune")) }
                        OutlinedButton(onClick = { if (b != null) { selection = MeshSculpt.selectEyelids(b.mesh, b.topo, left = true); tool = Tool.MOVE } }) { Text(tr("Paupières gauche")) }
                        OutlinedButton(onClick = { if (b != null) { selection = MeshSculpt.selectEyelids(b.mesh, b.topo, left = false); tool = Tool.MOVE } }) { Text(tr("Paupières droite")) }
                    }
                    Text(
                        (if (radius == 0f) tr("Rayon d’influence : points choisis seulement") else trf("Rayon d’influence : {0}", "%.3f".format(radius))),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Slider(radius, { radius = (it * 1000).toInt() / 1000f }, valueRange = 0f..0.25f)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(mirror, { mirror = it })
                        Text(tr("Symétrie gauche/droite (applique l’inverse de l’autre côté)"), style = MaterialTheme.typography.bodySmall)
                    }
                    Text(trf("Pas : {0}", "%.3f".format(step)), style = MaterialTheme.typography.bodySmall)
                    Slider(step, { step = (it * 1000).toInt().coerceAtLeast(1) / 1000f }, valueRange = 0.001f..0.03f)
                    ChipRow {
                        OutlinedButton(onClick = { nudge(cam.r0.map { -it }.toFloatArray()) }) { Text("←") }
                        OutlinedButton(onClick = { nudge(cam.r0) }) { Text("→") }
                        OutlinedButton(onClick = { nudge(cam.r1) }) { Text("↑") }
                        OutlinedButton(onClick = { nudge(cam.r1.map { -it }.toFloatArray()) }) { Text("↓") }
                        OutlinedButton(onClick = { nudge(cam.r2) }) { Text(tr("Avant")) }
                        OutlinedButton(onClick = { nudge(cam.r2.map { -it }.toFloatArray()) }) { Text(tr("Arrière")) }
                    }
                    ChipRow {
                        OutlinedButton(onClick = {
                            if (b == null || selection.isEmpty()) { message = tr("Sélectionnez d’abord des points à lisser."); return@OutlinedButton }
                            val (w, m) = weights(b)
                            var next = MeshSculpt.smooth(offsets, b.topo, b.mesh.verts, w)
                            if (m != null) next = MeshSculpt.smooth(next, b.topo, b.mesh.verts, m)
                            commit(next, tr("Zone lissée (touchez encore pour lisser davantage)."))
                        }) { Text(tr("Lisser")) }
                        OutlinedButton(onClick = {
                            if (b != null && selection.isNotEmpty()) commit(MeshSculpt.clear(offsets, b.topo, selection), tr("Points remis à leur forme d’origine."))
                        }) { Text(tr("Points d’origine")) }
                    }
                    Text(tr("Symétrie du visage"), style = MaterialTheme.typography.labelLarge)
                    ChipRow {
                        for (fromLeft in listOf(true, false)) OutlinedButton(enabled = b != null && !busy, onClick = {
                            val bb = b ?: return@OutlinedButton
                            if (symOnly && selection.isEmpty()) { message = tr("Sélectionnez d’abord les points à rendre symétriques."); return@OutlinedButton }
                            busy = true
                            message = tr("Calcul de la symétrie…")
                            val only = if (symOnly) selection else null
                            scope.launch {
                                val res = withContext(Dispatchers.Default) { MeshSculpt.symmetrize(bb.mesh, bb.topo, offsets, fromLeft, bb.plane, only) }
                                commit(res.offsets, trf(if (fromLeft) "Côté gauche copié sur le droit : {0} points déplacés, {1} sans équivalent." else "Côté droit copié sur le gauche : {0} points déplacés, {1} sans équivalent.", res.moved, res.skipped))
                                busy = false
                            }
                        }) { Text(if (fromLeft) tr("Copier gauche → droite") else tr("Copier droite → gauche")) }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(symOnly, { symOnly = it })
                        Text(tr("Limiter à la sélection (ex. seulement les paupières)"), style = MaterialTheme.typography.bodySmall)
                    }
                    Text(tr("Vue"), style = MaterialTheme.typography.labelLarge)
                    ChipRow {
                        for ((label, yaw) in listOf("Face" to 0f, "¾ gauche" to -0.6f, "Profil gauche" to -1.5f, "¾ droit" to 0.6f, "Profil droit" to 1.5f)) {
                            OutlinedButton(onClick = { cam = cam.with(yaw = yaw, pitch = 0f) }) { Text(tr(label)) }
                        }
                    }
                    ChipRow {
                        OutlinedButton(onClick = { cam = cam.with(zoom = 1f, panX = 0f, panY = 0f, centre = floatArrayOf(0f, -0.1f, 0f)) }) { Text(tr("Visage entier")) }
                        for (left in listOf(true, false)) OutlinedButton(onClick = {
                            val eye = b?.eyes?.let { if (left) it.firstOrNull() else it.lastOrNull() } ?: return@OutlinedButton
                            cam = cam.with(zoom = 5f, panX = 0f, panY = 0f, centre = eye.copyOf())
                        }) { Text(if (left) tr("Œil gauche") else tr("Œil droit")) }
                    }
                    ChipRow {
                        listOf("Plein + fils", "Plein", "Fils de fer").forEachIndexed { i, label -> FilterChip(viewMode == i, { viewMode = i }, { Text(tr(label)) }) }
                        FilterChip(showPoints, { showPoints = !showPoints }, { Text(tr("Points")) })
                    }
                    Text(tr("« Gauche » et « droite » : côtés tels qu’on les voit à l’écran. Les cheveux ne sont pas modifiables."), style = MaterialTheme.typography.bodySmall)
                }
                ChipRow {
                    OutlinedButton(enabled = undo.isNotEmpty(), onClick = { redo += offsets; offsets = undo.removeAt(undo.lastIndex); message = tr("Annulé.") }) { Text(tr("Annuler")) }
                    OutlinedButton(enabled = redo.isNotEmpty(), onClick = { undo += offsets; offsets = redo.removeAt(redo.lastIndex); message = tr("Rétabli.") }) { Text(tr("Rétablir")) }
                    OutlinedButton(enabled = offsets.isNotEmpty(), onClick = { confirmClear = true }) { Text(tr("Tout effacer")) }
                    TextButton(onClick = { if (dirty) confirmClose = true else onClose() }) { Text(tr("Fermer")) }
                    Button(onClick = { onApply(MeshSculpt.normalize(offsets)) }) { Text(tr("Valider les retouches")) }
                }
            }
        }
    }
    if (confirmClose) AlertDialog(
        onDismissRequest = { confirmClose = false },
        text = { Text(tr("Quitter sans valider vos retouches de polygones ?")) },
        confirmButton = { TextButton(onClick = { confirmClose = false; onClose() }) { Text(tr("Quitter")) } },
        dismissButton = { TextButton(onClick = { confirmClose = false }) { Text(tr("Rester")) } },
    )
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        text = { Text(tr("Effacer toutes les retouches de polygones ?")) },
        confirmButton = { TextButton(onClick = { confirmClear = false; commit(emptyMap(), tr("Toutes les retouches ont été effacées (Annuler pour revenir).")) }) { Text(tr("Effacer")) } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(tr("Garder")) } },
    )
}

@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.horizontalScroll(rememberScrollState())) { content() }
}

private sealed class SculptGesture {
    class Tap(val x: Float, val y: Float) : SculptGesture()
    /** One finger dragging: from ([x0], [y0]) to ([x], [y]), by ([dx], [dy]) since the last event. */
    class Drag(val x0: Float, val y0: Float, val x: Float, val y: Float, val dx: Float, val dy: Float) : SculptGesture()
    class Zoom(val cx: Float, val cy: Float, val factor: Float, val panX: Float, val panY: Float) : SculptGesture()
    object End : SculptGesture()
}

private val SKIN = intArrayOf(128, 176, 200)
private const val BACKGROUND = 0xFF050C18.toInt()
private const val WIRE = 0x6BD2F0FF
private const val POINT = 0xD9A0E6FF.toInt()
private const val SELECTED = 0xFFFFB300.toInt()
private const val SOFT = 0xFF22D3EE.toInt()

@Composable
private fun SculptCanvas(
    base: EditBase, offsets: Sculpt, selection: Set<Int>, cam: MeshSculpt.Camera, softRadius: Float, viewMode: Int, showPoints: Boolean,
    box: FloatArray?, onScene: (MeshSculpt.Scene) -> Unit, onGesture: (SculptGesture) -> Unit,
) {
    val gesture by androidx.compose.runtime.rememberUpdatedState(onGesture)
    val fill = remember { Paint().apply { style = Paint.Style.FILL; isAntiAlias = false } }
    val line = remember { Paint().apply { style = Paint.Style.STROKE; isAntiAlias = true; strokeWidth = 1f } }
    Canvas(
        Modifier.fillMaxSize().pointerInput(base) {
            awaitEachGesture {
                val down = awaitFirstDown()
                val x0 = down.position.x; val y0 = down.position.y
                var last = down.position
                var moved = false
                var multi = false
                while (true) {
                    val event = awaitPointerEvent()
                    val pressed = event.changes.filter { it.pressed }
                    if (pressed.isEmpty()) break
                    if (pressed.size >= 2) {
                        multi = true
                        val a = pressed[0]; val b = pressed[1]
                        val before = hypot(a.previousPosition.x - b.previousPosition.x, a.previousPosition.y - b.previousPosition.y)
                        val now = hypot(a.position.x - b.position.x, a.position.y - b.position.y)
                        val mid = Offset((a.position.x + b.position.x) / 2, (a.position.y + b.position.y) / 2)
                        val prevMid = Offset((a.previousPosition.x + b.previousPosition.x) / 2, (a.previousPosition.y + b.previousPosition.y) / 2)
                        if (before > 1f) gesture(SculptGesture.Zoom(mid.x, mid.y, now / before, mid.x - prevMid.x, mid.y - prevMid.y))
                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                        continue
                    }
                    if (multi) continue
                    val p = pressed[0].position
                    if (!moved && hypot(p.x - x0, p.y - y0) > 12f) moved = true
                    if (moved && p != last) {
                        gesture(SculptGesture.Drag(x0, y0, p.x, p.y, p.x - last.x, p.y - last.y))
                        pressed[0].consume()
                    }
                    last = p
                }
                if (!moved && !multi) gesture(SculptGesture.Tap(x0, y0))
                gesture(SculptGesture.End)
            }
        },
    ) {
        val c = cam.with(width = size.width, height = size.height)
        val verts = MeshSculpt.displayVerts(base.mesh.verts, offsets, base.topo.limit)
        val scene = MeshSculpt.scene(c, verts, base.topo, base.mesh.normals)
        onScene(scene)
        val proj = scene.proj; val tris = scene.tris
        val order = (0 until tris.size / 3).filter { scene.front[it] }.sortedBy { scene.depth[it] }
        fun inSel(t: Int) = MeshSculpt.trianglePoints(base.topo, t).all { it in selection }
        drawIntoCanvas { canvas ->
            val nc = canvas.nativeCanvas
            nc.drawColor(BACKGROUND)
            if (viewMode != 2 && order.isNotEmpty()) {
                val pos = FloatArray(order.size * 6); val col = IntArray(order.size * 3)
                var p = 0; var q = 0
                for (t in order) {
                    val k = 0.28f + 0.72f * scene.shade[t]
                    val rgb = if (selection.isNotEmpty() && inSel(t)) intArrayOf((255 * k).toInt(), (170 * k).toInt(), (40 * k).toInt())
                    else intArrayOf((SKIN[0] * k).toInt(), (SKIN[1] * k).toInt(), (SKIN[2] * k).toInt())
                    val colour = (0xFF shl 24) or (rgb[0].coerceIn(0, 255) shl 16) or (rgb[1].coerceIn(0, 255) shl 8) or rgb[2].coerceIn(0, 255)
                    for (corner in 0..2) {
                        val vi = tris[3 * t + corner]
                        pos[p++] = proj[3 * vi]; pos[p++] = proj[3 * vi + 1]
                        col[q++] = colour
                    }
                }
                if (Build.VERSION.SDK_INT >= 29) {
                    nc.drawVertices(android.graphics.Canvas.VertexMode.TRIANGLES, pos.size, pos, 0, null, 0, col, 0, null, 0, 0, fill)
                } else {
                    val path = android.graphics.Path()
                    for (k in order.indices) {
                        path.reset(); path.moveTo(pos[6 * k], pos[6 * k + 1]); path.lineTo(pos[6 * k + 2], pos[6 * k + 3]); path.lineTo(pos[6 * k + 4], pos[6 * k + 5]); path.close()
                        fill.color = col[3 * k]; nc.drawPath(path, fill)
                    }
                }
            }
            if (viewMode != 1 && order.isNotEmpty()) {
                val pts = FloatArray(order.size * 12)
                var p = 0
                for (t in order) for (e in 0..2) {
                    val a = tris[3 * t + e]; val b = tris[3 * t + (e + 1) % 3]
                    pts[p++] = proj[3 * a]; pts[p++] = proj[3 * a + 1]; pts[p++] = proj[3 * b]; pts[p++] = proj[3 * b + 1]
                }
                line.color = WIRE; line.strokeWidth = 1f
                nc.drawLines(pts, line)
            }
            if (softRadius > 0f && selection.isNotEmpty()) {
                fill.color = SOFT
                for ((r, w) in MeshSculpt.softWeights(base.topo, verts, selection, softRadius)) {
                    if (r in selection || w < 0.05f || !MeshSculpt.visible(scene, r)) continue
                    fill.alpha = ((0.25f + 0.6f * w) * 255).toInt()
                    nc.drawCircle(proj[3 * r], proj[3 * r + 1], 2f + 3f * w, fill)
                }
                fill.alpha = 255
            }
            if (showPoints) {
                fill.color = POINT
                for (r in base.topo.reps) {
                    if (r in selection || !MeshSculpt.visible(scene, r)) continue
                    nc.drawRect(proj[3 * r] - 1.5f, proj[3 * r + 1] - 1.5f, proj[3 * r] + 1.5f, proj[3 * r + 1] + 1.5f, fill)
                }
            }
            fill.color = SELECTED
            for (r in selection) if (MeshSculpt.visible(scene, r)) nc.drawCircle(proj[3 * r], proj[3 * r + 1], 5f, fill)
            if (box != null) {
                fill.color = 0x1FFFB300
                nc.drawRect(minOf(box[0], box[2]), minOf(box[1], box[3]), maxOf(box[0], box[2]), maxOf(box[1], box[3]), fill)
                line.color = SELECTED
                nc.drawRect(minOf(box[0], box[2]), minOf(box[1], box[3]), maxOf(box[0], box[2]), maxOf(box[1], box[3]), line)
            }
        }
    }
}
