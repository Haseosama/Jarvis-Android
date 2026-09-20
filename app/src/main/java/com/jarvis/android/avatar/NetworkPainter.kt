package com.jarvis.android.avatar

import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The network look: a nearly black head made of a web of fine glowing lines and bright nodes, denser and brighter where the
 * surface turns away (the contour, the nose, the lips, the eye sockets), with two glowing eyes. The head is the same mesh, ears
 * and neck included; only the surface is nearly black and its edges and vertices are what you see.
 */
internal class NetworkPainter(private val mesh: HeadMesh) {
    private val lineBuckets = Array(4) { FloatArray(mesh.faceCount * 6 * 4 / 2 + 16) }
    private val lineCounts = IntArray(4)
    private val nodeBuckets = Array(3) { FloatArray(mesh.baseCount * 2 + 4) }
    private val nodeCounts = IntArray(3)
    private val linePaint = Paint().apply { isAntiAlias = true; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val nodePaint = Paint().apply { isAntiAlias = true; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }

    private fun pow(x: Float, e: Float) = Math.pow(x.toDouble(), e.toDouble()).toFloat()

    /** The surface itself: almost black, with a faint blue glow towards the contour. */
    fun surfaceVertex(nz: Float, fadeIn: Float): Int {
        val rim = pow((1f - nz).coerceIn(0f, 1f), 2.4f)
        val r = (0.004f + 0.05f * rim) * fadeIn
        val g = (0.016f + 0.30f * rim) * fadeIn
        val b = (0.05f + 0.50f * rim) * fadeIn
        return (0xFF shl 24) or ((r * 255f).toInt().coerceIn(0, 255) shl 16) or ((g * 255f).toInt().coerceIn(0, 255) shl 8) or (b * 255f).toInt().coerceIn(0, 255)
    }

    private fun hash(k: Int): Int = ((k * -1640531535) ushr 16) and 0xFF

    /** Fine lines (a share of the mesh's edges, all the creases and the silhouette) and glowing nodes at the vertices. */
    fun draw(
        nc: Canvas, st: StructureEdges, faceFront: BooleanArray, xs: FloatArray, ys: FloatArray,
        v: FloatArray, nrm: FloatArray, time: Float, amp: Float, strokePx: Float,
    ) {
        lineCounts.fill(0); nodeCounts.fill(0)
        val gain = 0.85f + 0.5f * amp
        for (k in 0 until st.count) {
            val f0 = st.face0[k]; val f1 = st.face1[k]
            if (mesh.isHairFace(f0)) continue
            val front0 = faceFront[f0]
            val front1 = if (f1 >= 0) faceFront[f1] else front0
            if (!front0 && !front1) continue
            val i0 = st.a[k]; val i1 = st.b[k]
            if (mesh.faceGroup[f0] < 0.5f && v[3 * i0 + 1] > -1.02f && v[3 * i1 + 1] > -1.02f) continue // inside the head
            val silhouette = front0 != front1
            val crease = st.crease[k]
            // a share of the ordinary edges, all of the structure: the web is irregular like in a point cloud
            val keep = silhouette || crease > 0f || hash(k) < 96
            if (!keep) continue
            var a = 0.13f + 0.55f * crease + (if (silhouette) 0.45f else 0f)
            a += 0.10f * (hash(k + 7) / 255f)
            a *= 0.5f * (mesh.fade[i0] + mesh.fade[i1]) * gain
            if (a <= 0.05f) continue
            val b = (a * 4f).toInt().coerceIn(0, 3)
            val arr = lineBuckets[b]
            val o = lineCounts[b]
            if (o + 4 > arr.size) continue
            arr[o] = xs[i0]; arr[o + 1] = ys[i0]; arr[o + 2] = xs[i1]; arr[o + 3] = ys[i1]
            lineCounts[b] = o + 4
        }
        linePaint.strokeWidth = max(0.8f, strokePx * 0.62f)
        for (b in 0 until 4) {
            if (lineCounts[b] == 0) continue
            val alpha = ((b + 0.5f) / 4f * 0.95f * 255f).toInt().coerceIn(20, 255)
            linePaint.color = (alpha shl 24) or 0x3CB4FF
            nc.drawLines(lineBuckets[b], 0, lineCounts[b], linePaint)
        }
        for (i in 0 until mesh.baseCount) {
            val nz = nrm[3 * i + 2]
            if (nz < 0.0f) continue
            if (mesh.fade[i] < 0.25f) continue
            val fres = pow((1f - nz).coerceIn(0f, 1f), 1.3f)
            // nodes are brighter and denser near the contour and on the features, and they twinkle a little
            val tw = 0.78f + 0.22f * sin(time * 2.1f + i * 1.7f)
            val b = (0.30f + 0.85f * fres + 0.6f * mesh.ao.let { 1f - it[i] }) * tw * mesh.fade[i]
            val bucket = if (b > 0.85f) 2 else if (b > 0.45f) 1 else 0
            if (bucket == 0 && hash(i) > 150) continue // thin out the dim ones
            val arr = nodeBuckets[bucket]
            val o = nodeCounts[bucket]
            arr[o] = xs[i]; arr[o + 1] = ys[i]
            nodeCounts[bucket] = o + 2
        }
        val sizes = floatArrayOf(1.6f, 2.6f, 4.2f)
        val alphas = intArrayOf(0x90, 0xC8, 0xFF)
        for (b in 0 until 3) {
            if (nodeCounts[b] == 0) continue
            nodePaint.strokeWidth = sizes[b] * (strokePx / 2.5f).coerceIn(0.8f, 1.6f)
            nodePaint.color = (alphas[b] shl 24) or (if (b == 2) 0xBFEFFF else 0x5CC8FF)
            nc.drawPoints(nodeBuckets[b], 0, nodeCounts[b], nodePaint)
        }
    }

    private fun ring(xs: FloatArray, ys: FloatArray, idx: IntArray): Path {
        val p = Path()
        for ((k, i) in idx.withIndex()) if (k == 0) p.moveTo(xs[i], ys[i]) else p.lineTo(xs[i], ys[i])
        return p
    }

    /** Two glowing eyes, thin bright lips (a glow inside when they part) and faint brows. */
    fun drawFeatures(scope: DrawScope, avatar: HoloAvatar, xs: FloatArray, ys: FloatArray, r: Float, amp: Float, strokePx: Float) {
        val face = max(0f, cos(avatar.yaw) * cos(avatar.pitch)).let { it * it }
        if (face < 0.02f) return
        val lm = mesh.landmarks
        val vis = ((1f - avatar.blink) * avatar.lids.coerceIn(0f, 1f) * 1.3f).coerceIn(0.04f, 1.4f)
        val line = Color(0xFF6CCBFF)

        for (key in listOf("brow_l", "brow_r")) {
            scope.drawPath(ring(xs, ys, lm.getValue(key)), line.copy(alpha = 0.55f * face), style = Stroke(width = strokePx * 1.1f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }

        for (key in listOf("eye_l", "eye_r")) {
            val idx = lm.getValue(key)
            var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var midY = 0f
            for (i in idx) { minX = min(minX, xs[i]); maxX = max(maxX, xs[i]); midY += ys[i] }
            midY /= idx.size
            val path = Path()
            var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
            for ((k, i) in idx.withIndex()) {
                val y = midY + (ys[i] - midY) * vis
                minY = min(minY, y); maxY = max(maxY, y)
                if (k == 0) path.moveTo(xs[i], y) else path.lineTo(xs[i], y)
            }
            path.close()
            val w = maxX - minX
            val h = max(maxY - minY, 0.5f)
            val cx0 = (minX + maxX) / 2f
            val cy0 = (minY + maxY) / 2f
            // a wide soft glow, then the eye itself: a bright, slightly oblique disc
            scope.drawCircle(Brush.radialGradient(listOf(Color(0x8836B8FF), Color(0x0036B8FF)), center = Offset(cx0, cy0), radius = w * 1.35f), radius = w * 1.35f, center = Offset(cx0, cy0))
            scope.clipPath(path) {
                drawRect(Color(0xFF041A3A), topLeft = Offset(minX, minY), size = Size(w, h))
                val gx = cx0 + avatar.gaze[0] * w * 0.10f
                val gy = cy0 + avatar.gaze[1] * h * 0.12f
                val rad = max(min(w * 0.30f, h * 1.5f), w * 0.16f)
                drawCircle(
                    Brush.radialGradient(
                        0.0f to Color(0xFFFFFFFF), 0.35f to Color(0xFF8FE8FF), 0.75f to Color(0xFF1E8CFF), 1.0f to Color(0xFF0A3CC8),
                        center = Offset(gx, gy), radius = rad * (1.0f + 0.15f * amp),
                    ),
                    radius = rad * (1.0f + 0.15f * amp), center = Offset(gx, gy),
                )
            }
            scope.drawPath(path, Color(0xFF8FE8FF).copy(alpha = 0.85f * face), style = Stroke(width = strokePx * 1.3f, join = StrokeJoin.Round))
        }

        val outer = ring(xs, ys, lm.getValue("lips_out")).also { it.close() }
        val innerIdx = lm.getValue("lips_in")
        val inner = ring(xs, ys, innerIdx).also { it.close() }
        val mouth = avatar.mouth
        var oTop = Float.MAX_VALUE; var oBottom = -Float.MAX_VALUE; var oL = Float.MAX_VALUE; var oR = -Float.MAX_VALUE
        for (i in innerIdx) { oTop = min(oTop, ys[i]); oBottom = max(oBottom, ys[i]); oL = min(oL, xs[i]); oR = max(oR, xs[i]) }
        if (mouth > 0.03f && oBottom - oTop > 1.2f) {
            scope.drawPath(inner, Color(0xFF020A1C))
            scope.clipPath(inner) {
                drawRect(
                    Brush.verticalGradient(listOf(Color(0x0036B8FF), Color(0xAA36B8FF)), startY = oTop, endY = oBottom),
                    topLeft = Offset(oL, oTop), size = Size(oR - oL, oBottom - oTop),
                )
            }
        }
        scope.drawPath(outer, Color(0x5536B8FF).copy(alpha = 0.35f * face), style = Stroke(width = strokePx * 3.5f, join = StrokeJoin.Round))
        scope.drawPath(outer, line.copy(alpha = 0.90f * face), style = Stroke(width = strokePx * 1.1f, join = StrokeJoin.Round))
        scope.drawPath(inner, Color(0xFFBFEFFF).copy(alpha = (0.55f + 0.35f * mouth) * face), style = Stroke(width = strokePx * 1.0f, join = StrokeJoin.Round))
    }
}
