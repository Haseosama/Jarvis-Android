package com.jarvis.android.avatar

import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Camera distance in head-half-heights: far enough that the nose does not balloon, near enough to keep some depth. */
private const val CAM_D = 4.6f
private const val BUCKETS = 4
private const val MIN_ALPHA = 0.05f
private const val LUT_N = 192

internal fun argb(a: Int, r: Int, g: Int, b: Int): Int = (a shl 24) or (r shl 16) or (g shl 8) or b

/** [col] at alpha [a] (0..255) pre-mixed onto [bg], fully opaque: opaque lines take the fast path in the renderer. */
internal fun blend(bg: Int, col: Int, a: Float): Int {
    val f = (a / 255f).coerceIn(0f, 1f)
    fun ch(shift: Int): Int {
        val b0 = (bg shr shift) and 0xFF
        val c0 = (col shr shift) and 0xFF
        return (b0 + (c0 - b0) * f).toInt().coerceIn(0, 255)
    }
    return argb(255, ch(16), ch(8), ch(0))
}

private val SKIN_TONES = intArrayOf(0xF1C9A8, 0xD9A47C, 0xB07A54, 0x7A4E36)
private val LIP_TONES = intArrayOf(0xD9707F, 0xC02836, 0x8E3A6B, 0xE8735A)

private fun withAlpha(col: Int, a: Float): Int = (col and 0x00FFFFFF) or (a.coerceIn(0f, 255f).toInt() shl 24)

/**
 * Draws the head. The surface is lit per facet (averaged normals would smear the nose, lips and brow into a blank egg),
 * sorted far to near, then a wireframe, the eyes, brows and mouth are drawn from the real landmark rings.
 * Ported from Mark-LIV's renderer (CC BY-NC 4.0, see assets/avatar/NOTICE.txt) to Android's canvas, with a few additions:
 * a rim light in the accent colour, faint scan lines, and lids that really close when the assistant sleeps.
 */
internal class AvatarRenderer(private val mesh: HeadMesh) {
    private val nV = mesh.vertexCount
    private val nF = mesh.faceCount
    private val xs = FloatArray(nV)
    private val ys = FloatArray(nV)
    private val faceColor = IntArray(nF)
    private val faceKey = FloatArray(nF)
    private val keys = LongArray(nF)
    private val triPos = FloatArray(nF * 6)
    private val triCol = IntArray(nF * 3)
    private val cornerCol = IntArray(nF * 3) // the skin, the mouth and the eyeballs are shaded per vertex, so they do not show facets
    private val perVertex = BooleanArray(nF)
    private var primaryColor = 0
    private val faceFront = BooleanArray(nF)
    private val structure = StructureEdges.build(mesh)
    private val edgeBuckets = Array(BUCKETS) { FloatArray(structure.count * 4) }
    private val edgeCounts = IntArray(BUCKETS)
    private val lut = IntArray(LUT_N)
    private var lutKey = 0L
    private val surfacePaint = Paint().apply { isAntiAlias = false; style = Paint.Style.FILL }
    private val trianglePath = android.graphics.Path()
    private val linePaint = Paint().apply { isAntiAlias = true; style = Paint.Style.STROKE }
    /** 0 = the glowing web; 1..4 = a skin tone over the face. */
    var skin = 1
    /** 0 = natural lips; 1..4 = rose, red, plum, coral. */
    var lips = 0
    private var bgColor = 0
    private val web = NetworkWeb(mesh)
    private val wx = FloatArray(web.count)
    private val wy = FloatArray(web.count)
    private val wz = FloatArray(web.count)
    private val webLines = Array(4) { FloatArray(web.edges.size * 2 + 8) }
    private val webLineCounts = IntArray(4)
    private val webNodes = Array(3) { FloatArray(web.count * 2 + 4) }
    private val webNodeCounts = IntArray(3)
    private val lipUp = mesh.landmarks.getValue("lips_in").let { ring -> ring.copyOfRange(10, ring.size) + ring[0] }

    /** Draws the head centred on ([cx], [cy]); [r] is its half-height in pixels. Colours are ARGB ints. */
    fun draw(scope: DrawScope, avatar: HoloAvatar, cx: Float, cy: Float, r: Float, primary: Int, accent: Int, bg: Int, strokePx: Float) {
        avatar.pose()
        val amp = avatar.glow
        val v = avatar.pv
        val n = avatar.pn

        // aura
        val ar = r * 1.95f
        scope.drawCircle(
            brush = Brush.radialGradient(
                0f to Color(withAlpha(primary, 34f + 66f * amp)),
                0.38f to Color(withAlpha(primary, 20f + 40f * amp)),
                1f to Color(withAlpha(primary, 0f)),
                center = Offset(cx, cy), radius = ar,
            ),
            radius = ar, center = Offset(cx, cy),
        )

        // a few drifting points of light in the dark, as in the reference photos
        for (k in 0 until 26) {
            val h = ((k * -1640531535) ushr 8) and 0xFFFF
            val ang = (h % 628) / 100f + 0.05f * avatar.time * (if (k % 2 == 0) 1f else -1f)
            val dist = r * (1.15f + 0.85f * ((h / 7) % 100) / 100f)
            val tw = 0.35f + 0.65f * (0.5f + 0.5f * kotlin.math.sin(avatar.time * (0.8f + (h % 5) * 0.3f) + k))
            scope.drawCircle(Color(withAlpha(primary, 150f * tw)), radius = 1.2f + (h % 3), center = Offset(cx + cos(ang) * dist * 0.8f, cy + kotlin.math.sin(ang) * dist * 1.05f))
        }

        // project
        for (i in 0 until nV) {
            val w = max(CAM_D - v[3 * i + 2], 0.35f)
            val k = CAM_D / w * r
            xs[i] = cx + v[3 * i] * k
            ys[i] = cy - v[3 * i + 1] * k
        }

        bgColor = bg
        primaryColor = primary
        buildLut(bg, primary)
        val visible = shadeFaces(v, n, amp, accent)
        scope.drawIntoCanvas { canvas ->
            val nc = canvas.nativeCanvas
            drawSurface(nc, visible)
            drawWeb(nc, n, amp, primary, strokePx, avatar.time)
        }
        drawFeatures(scope, avatar, r, primary, accent, bg, amp, strokePx)
    }

    private fun buildLut(bg: Int, primary: Int) {
        val key = (bg.toLong() shl 32) xor primary.toLong()
        if (key == lutKey) return
        lutKey = key
        for (i in 0 until LUT_N) lut[i] = blend(bg, primary, 255f * (i + 0.5f) / LUT_N * 0.17f) // a nearly black surface: the web is what shines
    }

    /** Lights every camera-facing triangle and returns how many; their colour and depth key are left in the arrays. */
    private fun shadeFaces(v: FloatArray, nrm: FloatArray, amp: Float, accent: Int): Int {
        var count = 0
        val f = mesh.faces
        val fade = mesh.fade
        for (t in 0 until nF) {
            val a = f[3 * t]; val b = f[3 * t + 1]; val c = f[3 * t + 2]
            val abx = v[3 * b] - v[3 * a]; val aby = v[3 * b + 1] - v[3 * a + 1]; val abz = v[3 * b + 2] - v[3 * a + 2]
            val acx = v[3 * c] - v[3 * a]; val acy = v[3 * c + 1] - v[3 * a + 1]; val acz = v[3 * c + 2] - v[3 * a + 2]
            var nx = aby * acz - abz * acy
            var ny = abz * acx - abx * acz
            var nz = abx * acy - aby * acx
            val len = max(sqrt(nx * nx + ny * ny + nz * nz), 1e-9f)
            nx /= len; ny /= len; nz /= len
            // Point them outwards by agreeing with the vertex normals (flipping on the sign of nz alone would scramble the light).
            val rx = nrm[3 * a] + nrm[3 * b] + nrm[3 * c]
            val ry = nrm[3 * a + 1] + nrm[3 * b + 1] + nrm[3 * c + 1]
            val rz = nrm[3 * a + 2] + nrm[3 * b + 2] + nrm[3 * c + 2]
            if (nx * rx + ny * ry + nz * rz < 0f) { nx = -nx; ny = -ny; nz = -nz }
            faceFront[t] = nz > 0.015f
            if (nz <= 0.015f) continue
            val area = abs((xs[b] - xs[a]) * (ys[c] - ys[a]) - (xs[c] - xs[a]) * (ys[b] - ys[a]))
            if (area <= 3f) continue

            val fres = Math.pow((1f - nz).coerceIn(0f, 2f).toDouble(), 1.7).toFloat()
            val lam = (nx * -0.55f + ny * 0.50f + nz * 0.52f).coerceIn(0f, 1f)
            var bright = 0.26f + 0.20f * fres + 0.66f * Math.pow(lam.toDouble(), 1.05).toFloat()
            val fadeAvg = (fade[a] + fade[b] + fade[c]) / 3f
            val cutoff = if (skin > 0) 0.15f else 0.4f
            if (fadeAvg < cutoff) continue // the far end of the neck is left out: it would end on a ragged cut
            bright *= (fade[a] * fade[a] + fade[b] * fade[b] + fade[c] * fade[c]) / 3f
            bright *= 0.88f + 0.24f * amp
            var col = lut[(bright * LUT_N).toInt().coerceIn(0, LUT_N - 1)]
            val painted = mesh.paint[a] != 0 || mesh.paint[b] != 0 || mesh.paint[c] != 0
            val onLips = lips > 0 && (mesh.lipMask[a] > 0.02f || mesh.lipMask[b] > 0.02f || mesh.lipMask[c] > 0.02f)
            perVertex[t] = skin > 0 || painted || onLips
            if (perVertex[t]) {
                for (corner in 0..2) cornerCol[3 * t + corner] = vertexColour(f[3 * t + corner], nrm, amp, col)
                col = cornerCol[3 * t]
            }
            // Rim light: facets turning away from the viewer catch the accent colour.
            val rim = (fres * fres * 0.14f).coerceIn(0f, 0.14f)
            if (rim > 0.01f && !perVertex[t]) col = mix(col, accent, rim)
            faceColor[t] = col
            val z = (v[3 * a + 2] + v[3 * b + 2] + v[3 * c + 2]) / 3f + mesh.faceGroup[t].let { g -> if (g > 0.5f) 0f else -1000f }
            // The neck (group 0) is drawn first: it interpenetrates the head and a pure depth sort tears the seam.
            val bits = java.lang.Float.floatToIntBits(z)
            val mapped = if (bits >= 0) bits else bits xor 0x7fffffff
            keys[count++] = (mapped.toLong() shl 32) or t.toLong()
        }
        java.util.Arrays.sort(keys, 0, count)
        return count
    }

    /** The colour of one vertex where the surface is not simply the dark web: the skin (with the lips), or what is painted on it. */
    private fun vertexColour(vi: Int, nrm: FloatArray, amp: Float, flat: Int): Int {
        var vx = nrm[3 * vi]; var vy = nrm[3 * vi + 1]; var vz = nrm[3 * vi + 2]
        val vl = max(sqrt(vx * vx + vy * vy + vz * vz), 1e-9f)
        vx /= vl; vy /= vl; vz /= vl
        val vlam = (vx * -0.55f + vy * 0.50f + vz * 0.52f).coerceIn(0f, 1f)
        fun lit(rgb: Int, k: Float): Int = argb(255, (((rgb shr 16) and 0xFF) * k).toInt().coerceIn(0, 255), (((rgb shr 8) and 0xFF) * k).toInt().coerceIn(0, 255), ((rgb and 0xFF) * k).toInt().coerceIn(0, 255))
        val pnt = mesh.paint[vi]
        if (pnt != 0) {
            // the mouth's inside, the teeth and the eyeballs: their own colours, lit a little; on the web they take a cool tint
            val base = if (skin > 0) pnt else mix(pnt, primaryColor, 0.22f)
            return lit(base, 0.62f + 0.42f * vlam)
        }
        val lipW = mesh.lipMask[vi]
        if (skin == 0) return if (lipW > 0.02f && lips > 0) mix(flat, lit(0xFF000000.toInt() or LIP_TONES[lips - 1], 0.75f + 0.3f * vlam), lipW) else flat
        val k = (0.30f + 0.85f * vlam + 0.10f * vz.coerceIn(0f, 1f)).coerceIn(0.15f, 1.15f) * (0.94f + 0.12f * amp)
        val skinRgb = 0xFF000000.toInt() or SKIN_TONES[skin - 1]
        var c = lit(skinRgb, k)
        if (lipW > 0.02f) {
            val lipRgb = if (lips > 0) 0xFF000000.toInt() or LIP_TONES[lips - 1] else mix(skinRgb, 0xFFB05060.toInt(), 0.5f)
            c = mix(c, lit(lipRgb, k), lipW)
        }
        val fv = (mesh.fade[vi] * mesh.fade[vi]).coerceIn(0f, 1f)
        return mix(bgColor, c, fv)
    }

    private fun mix(base: Int, other: Int, f: Float): Int {
        fun ch(shift: Int): Int {
            val b0 = (base shr shift) and 0xFF
            val c0 = (other shr shift) and 0xFF
            return (b0 + (c0 - b0) * f).toInt().coerceIn(0, 255)
        }
        return argb(255, ch(16), ch(8), ch(0))
    }

    private fun drawSurface(nc: Canvas, count: Int) {
        if (count == 0) return
        val f = mesh.faces
        var p = 0
        var c = 0
        for (k in 0 until count) {
            val t = (keys[k] and 0x7fffffffL).toInt()
            val col = faceColor[t]
            for (corner in 0..2) {
                val vi = f[3 * t + corner]
                triPos[p++] = xs[vi]
                triPos[p++] = ys[vi]
                triCol[c++] = if (perVertex[t]) cornerCol[3 * t + corner] else col
            }
        }
        if (Build.VERSION.SDK_INT >= 29) {
            nc.drawVertices(Canvas.VertexMode.TRIANGLES, count * 6, triPos, 0, null, 0, triCol, 0, null, 0, 0, surfacePaint)
        } else {
            // Older phones: one filled path per triangle (slower, same picture).
            var q = 0
            for (k in 0 until count) {
                trianglePath.reset()
                trianglePath.moveTo(triPos[q], triPos[q + 1])
                trianglePath.lineTo(triPos[q + 2], triPos[q + 3])
                trianglePath.lineTo(triPos[q + 4], triPos[q + 5])
                trianglePath.close()
                surfacePaint.color = triCol[3 * k]
                nc.drawPath(trianglePath, surfacePaint)
                q += 6
            }
        }
    }

    private fun drawWire(nc: Canvas, v: FloatArray, nrm: FloatArray, amp: Float, primary: Int, bg: Int, strokePx: Float) {
        val scan = scanY
        edgeCounts.fill(0)
        val st = structure
        val gain = 0.80f + 0.45f * amp
        for (k in 0 until st.count) {
            val i0 = st.a[k]; val i1 = st.b[k]
            val f0 = st.face0[k]; val f1 = st.face1[k]
            val front0 = faceFront[f0]
            val front1 = if (f1 >= 0) faceFront[f1] else front0
            if (!front0 && !front1) continue
            val silhouette = front0 != front1
            val fadeE = 0.5f * (mesh.fade[i0] + mesh.fade[i1])
            var alpha = when {
                silhouette -> 0.60f
                st.crease[k] > 0f -> 0.16f + 0.42f * st.crease[k]
                else -> 0f
            }
            // The scanner: the whole lattice lights up for a moment as the sweep passes.
            val ym = 0.5f * (v[3 * i0 + 1] + v[3 * i1 + 1])
            val d = (ym - scan) / 0.13f
            alpha += 0.34f * exp(-d * d)
            alpha *= fadeE * gain
            if (alpha <= MIN_ALPHA) continue
            val bkt = (alpha * BUCKETS).toInt().coerceIn(0, BUCKETS - 1)
            val arr = edgeBuckets[bkt]
            val o = edgeCounts[bkt]
            arr[o] = xs[i0]; arr[o + 1] = ys[i0]; arr[o + 2] = xs[i1]; arr[o + 3] = ys[i1]
            edgeCounts[bkt] = o + 4
        }
        val skin = blend(bg, primary, 132f)
        linePaint.strokeWidth = strokePx
        for (b in 0 until BUCKETS) {
            if (edgeCounts[b] == 0) continue
            val a = 255f * min(1f, (b + 0.5f) / BUCKETS)
            linePaint.color = blend(skin, primary, a * 0.80f)
            nc.drawLines(edgeBuckets[b], 0, edgeCounts[b], linePaint)
        }
    }

    /** The web: fine lines between neighbouring nodes and bright nodes, brighter towards the contour, with a slow twinkle. */
    private fun drawWeb(nc: Canvas, nrm: FloatArray, amp: Float, primary: Int, strokePx: Float, t: Float) {
        if (skin > 0) return // a skin hides the web
        val w = web
        val gain = (0.85f + 0.5f * amp) * (if (skin > 0) 0.22f else 1f)
        for (i in 0 until w.count) {
            val a = w.triA[i]; val b = w.triB[i]; val c = w.triC[i]
            val u = w.wu[i]; val q = w.wv[i]; val s = 1f - u - q
            wx[i] = s * xs[a] + u * xs[b] + q * xs[c]
            wy[i] = s * ys[a] + u * ys[b] + q * ys[c]
            wz[i] = s * nrm[3 * a + 2] + u * nrm[3 * b + 2] + q * nrm[3 * c + 2]
        }
        webLineCounts.fill(0); webNodeCounts.fill(0)
        val e = w.edges
        for (k in 0 until e.size / 2) {
            val i = e[2 * k]; val j = e[2 * k + 1]
            val nzi = wz[i]; val nzj = wz[j]
            if (nzi < 0.05f && nzj < 0.05f) continue
            val fres = Math.pow((1f - 0.5f * (nzi + nzj)).coerceIn(0f, 1f).toDouble(), 2.4).toFloat()
            var a = (0.30f + 0.55f * fres) * 0.5f * (w.fade[i] * w.fade[i] + w.fade[j] * w.fade[j]) * gain
            if (nzi < 0.15f || nzj < 0.15f) a *= 0.6f
            if (a <= MIN_ALPHA) continue
            val bk = (a * 4f).toInt().coerceIn(0, 3)
            val arr = webLines[bk]; val o = webLineCounts[bk]
            arr[o] = wx[i]; arr[o + 1] = wy[i]; arr[o + 2] = wx[j]; arr[o + 3] = wy[j]
            webLineCounts[bk] = o + 4
        }
        linePaint.strokeWidth = max(0.8f, strokePx * 0.6f)
        for (bk in 0 until 4) {
            if (webLineCounts[bk] == 0) continue
            linePaint.color = withAlpha(primary, (bk + 0.5f) / 4f * 0.95f * 255f)
            nc.drawLines(webLines[bk], 0, webLineCounts[bk], linePaint)
        }
        for (i in 0 until w.count) {
            val nz = wz[i]
            if (nz < 0f || w.fade[i] < 0.25f) continue
            val fres = Math.pow((1f - nz).coerceIn(0f, 1f).toDouble(), 2.4).toFloat()
            val tw = 0.8f + 0.2f * kotlin.math.sin(t * 2.1f + i * 1.7f)
            val br = (0.55f + 0.9f * fres) * tw * w.fade[i] * w.fade[i]
            val hash = ((i * -1640531535) ushr 16) and 0xFF
            val bk = if (br > 0.85f || hash > 236) 2 else if (br > 0.5f) 1 else 0
            if (skin > 0 && bk < 2) continue
            val arr = webNodes[bk]; val o = webNodeCounts[bk]
            arr[o] = wx[i]; arr[o + 1] = wy[i]
            webNodeCounts[bk] = o + 2
        }
        val sizes = floatArrayOf(1.8f, 2.8f, 4.4f)
        val alphas = floatArrayOf(160f, 208f, 255f)
        for (bk in 0 until 3) {
            if (webNodeCounts[bk] == 0) continue
            linePaint.strokeWidth = sizes[bk] * (strokePx / 2.5f).coerceIn(0.8f, 1.6f)
            linePaint.strokeCap = Paint.Cap.ROUND
            linePaint.color = if (bk == 2) mix(withAlpha(primary, alphas[bk]), 0xFFFFFFFF.toInt(), 0.55f) else withAlpha(primary, alphas[bk])
            nc.drawPoints(webNodes[bk], 0, webNodeCounts[bk], linePaint)
        }
        linePaint.strokeCap = Paint.Cap.BUTT
    }

    /** Height of the energy sweep, set by the caller each frame. */
    var scanY: Float = 0f

    private fun drawScanLines(scope: DrawScope, cx: Float, cy: Float, r: Float, primary: Int) {
        val step = max(3f, r / 46f)
        var y = cy - r * 1.05f
        val end = cy + r * 1.25f
        val half = r * 0.72f
        val col = Color(withAlpha(primary, 14f))
        while (y < end) {
            scope.drawLine(col, Offset(cx - half, y), Offset(cx + half, y), strokeWidth = 1f)
            y += step
        }
    }

    private fun ring(idx: IntArray): Path {
        val p = Path()
        for ((k, i) in idx.withIndex()) if (k == 0) p.moveTo(xs[i], ys[i]) else p.lineTo(xs[i], ys[i])
        return p
    }

    private fun closedRing(idx: IntArray): Path = ring(idx).also { it.close() }

    private fun drawFeatures(scope: DrawScope, avatar: HoloAvatar, r: Float, primary: Int, accent: Int, bg: Int, amp: Float, strokePx: Float) {
        val face = max(0f, cos(avatar.yaw) * cos(avatar.pitch)).let { it * it }
        if (face < 0.02f) return
        val lm = mesh.landmarks

        // brows
        for (key in listOf("brow_l", "brow_r")) {
            val brow = ring(lm.getValue(key))
            if (skin > 0) {
                scope.drawPath(brow, Color(withAlpha(0xFF3A2A20.toInt(), 225f * face)), style = Stroke(width = strokePx * 2.6f, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
            } else {
                scope.drawPath(brow, Color(withAlpha(primary, 60f * face)), style = Stroke(width = strokePx * 4.5f, cap = androidx.compose.ui.graphics.StrokeCap.Round))
                scope.drawPath(brow, Color(withAlpha(primary, 230f * face)), style = Stroke(width = strokePx * 1.8f, cap = androidx.compose.ui.graphics.StrokeCap.Round))
            }
        }

    }
}

/**
 * The lines that carry the form: every edge of the mesh with the two triangles that share it, and how sharply the surface
 * folds there (0 = flat). Creases outline the eyes, nose, lips and jaw; the silhouette is found each frame from which of the
 * two triangles faces the camera. Mark-LIV drew an arbitrary third of all edges instead, which read as scribbles.
 */
internal class StructureEdges private constructor(
    val a: IntArray, val b: IntArray, val face0: IntArray, val face1: IntArray, val crease: FloatArray,
) {
    val count: Int get() = a.size

    companion object {
        private const val CREASE_MIN_COS = 0.93f // folds sharper than about 21 degrees count as structure

        fun build(mesh: HeadMesh): StructureEdges {
            val f = mesh.faces
            val v = mesh.verts
            val nrm = mesh.normals
            // outward face normals of the rest pose
            val fn = FloatArray(mesh.faceCount * 3)
            for (t in 0 until mesh.faceCount) {
                val i = f[3 * t]; val j = f[3 * t + 1]; val k = f[3 * t + 2]
                val abx = v[3 * j] - v[3 * i]; val aby = v[3 * j + 1] - v[3 * i + 1]; val abz = v[3 * j + 2] - v[3 * i + 2]
                val acx = v[3 * k] - v[3 * i]; val acy = v[3 * k + 1] - v[3 * i + 1]; val acz = v[3 * k + 2] - v[3 * i + 2]
                var x = aby * acz - abz * acy
                var y = abz * acx - abx * acz
                var z = abx * acy - aby * acx
                val len = max(sqrt(x * x + y * y + z * z), 1e-9f)
                x /= len; y /= len; z /= len
                val rx = nrm[3 * i] + nrm[3 * j] + nrm[3 * k]
                val ry = nrm[3 * i + 1] + nrm[3 * j + 1] + nrm[3 * k + 1]
                val rz = nrm[3 * i + 2] + nrm[3 * j + 2] + nrm[3 * k + 2]
                if (x * rx + y * ry + z * rz < 0f) { x = -x; y = -y; z = -z }
                fn[3 * t] = x; fn[3 * t + 1] = y; fn[3 * t + 2] = z
            }
            val first = HashMap<Long, Int>()
            val ea = ArrayList<Int>(); val eb = ArrayList<Int>(); val e0 = ArrayList<Int>(); val e1 = ArrayList<Int>()
            fun edge(u: Int, w: Int, t: Int) {
                val lo = min(u, w); val hi = max(u, w)
                val key = lo.toLong() * 100_000L + hi
                val idx = first[key]
                if (idx == null) {
                    first[key] = ea.size
                    ea += lo; eb += hi; e0 += t; e1 += -1
                } else {
                    e1[idx] = t
                }
            }
            for (t in 0 until mesh.faceCount) {
                val i = f[3 * t]; val j = f[3 * t + 1]; val k = f[3 * t + 2]
                edge(i, j, t); edge(j, k, t); edge(k, i, t)
            }
            val crease = FloatArray(ea.size)
            for (e in ea.indices) {
                val t0 = e0[e]; val t1 = e1[e]
                crease[e] = if (t1 < 0) 1f else {
                    val dot = fn[3 * t0] * fn[3 * t1] + fn[3 * t0 + 1] * fn[3 * t1 + 1] + fn[3 * t0 + 2] * fn[3 * t1 + 2]
                    if (dot < CREASE_MIN_COS) ((CREASE_MIN_COS - dot) / 0.6f).coerceIn(0.05f, 1f) else 0f
                }
            }
            return StructureEdges(ea.toIntArray(), eb.toIntArray(), e0.toIntArray(), e1.toIntArray(), crease)
        }
    }
}
