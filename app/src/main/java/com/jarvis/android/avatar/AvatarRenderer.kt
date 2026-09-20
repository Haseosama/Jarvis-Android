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
private const val BROW_HAIRS = 160
private const val LID_COLUMNS = 9
private const val HAIR_STRANDS = 3000
private const val SIDE_STRANDS = 2600
private const val HAIR_LOCKS = 22
private const val LOCK_SAMPLES = 9

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
    private val hairPaint = Paint().apply { isAntiAlias = true; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
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
            drawHairStrands(nc, v, n, r, strokePx, amp)
            drawLocks(nc, v, n, r, strokePx)
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
            if (skin == 0 && mesh.faceGroup[t] > 1.5f) continue // the hair belongs to the skin looks
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
            // the hair is drawn over the skin it lies on: a small bias in its depth, so an ear under a mound of hair does not show through
            val z = (v[3 * a + 2] + v[3 * b + 2] + v[3 * c + 2]) / 3f + mesh.faceGroup[t].let { g -> if (g > 1.5f) 0.07f else if (g > 0.5f) 0f else -1000f }
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
            val cover = ((pnt ushr 24) and 0xFF) / 255f
            if (cover < 0.999f && skin > 0) {
                // hair that thins out over the skin: the paint's colour mixed with the skin's
                val skinRgb = 0xFF000000.toInt() or SKIN_TONES[skin - 1]
                val ks = (0.30f + 0.85f * vlam + 0.10f * vz.coerceIn(0f, 1f)).coerceIn(0.15f, 1.15f) * (0.94f + 0.12f * amp)
                val hairLit = lit(0xFF000000.toInt() or (pnt and 0x00FFFFFF), 0.62f + 0.42f * vlam)
                val fvv = (mesh.fade[vi] * mesh.fade[vi]).coerceIn(0f, 1f)
                return mix(bgColor, mix(lit(skinRgb, ks), hairLit, cover), fvv)
            }
            // the mouth's inside, the teeth and the eyeballs: their own colours, lit a little; on the web they take a cool tint
            val base = if (skin > 0) pnt else mix(pnt, primaryColor, 0.22f)
            val shaded = lit(base, 0.62f + 0.42f * vlam)
            val dark = ((pnt shr 16) and 0xFF) + ((pnt shr 8) and 0xFF) + (pnt and 0xFF) < 240
            if (!dark) return shaded
            // dark paint (the hair) gets a soft sheen where the surface faces the light
            val sheen = (Math.pow(vlam.toDouble(), 5.0).toFloat() * 38f).toInt()
            return argb(255, (((shaded shr 16) and 0xFF) + sheen).coerceAtMost(255), (((shaded shr 8) and 0xFF) + sheen).coerceAtMost(255), ((shaded and 0xFF) + (sheen * 1.1f).toInt()).coerceAtMost(255))
        }
        val lipW = mesh.lipMask[vi]
        if (skin == 0) return if (lipW > 0.02f && lips > 0) mix(flat, lit(0xFF000000.toInt() or LIP_TONES[lips - 1], 0.75f + 0.3f * vlam), lipW) else flat
        val k = (0.30f + 0.85f * vlam + 0.10f * vz.coerceIn(0f, 1f)).coerceIn(0.15f, 1.15f) * (0.94f + 0.12f * amp)
        val skinRgb = 0xFF000000.toInt() or SKIN_TONES[skin - 1]
        var c = lit(skinRgb, k)
        if (lipW > 0.02f) {
            val lipRgb = if (lips > 0) 0xFF000000.toInt() or LIP_TONES[lips - 1] else mix(skinRgb, 0xFFB04A5A.toInt(), 0.7f)
            // the lips are lit less unevenly than the skin: the upper one faces the light and would otherwise come out pale
            c = mix(c, lit(lipRgb, 0.80f + 0.30f * vlam), lipW)
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


    // ── the hair's strands ───────────────────────────────────────────────────

    private class Strand(val a: Int, val b: Int, val c: Int, val u: Float, val v: Float, val al: Float, val be: Float, val len: Float, val lift: Float, val bend: Float, val tone: Int)

    /**
     * Hundreds of individual strands over the hair mesh, anchored to its triangles by barycentric weights. Each one leaves the surface
     * along the flow of the cut (swept forward and to the left on top, falling on the sides), lifts and bends a little, and ends beyond
     * the volume: that is what makes the edge of the hair look like hair. Their direction is a combination of the posed triangle's
     * edges, so they turn with the head.
     */
    private val strands: List<Strand> = run {
        val rnd = kotlin.random.Random(41)
        val f = mesh.faces
        val v = mesh.verts
        val hairFaces = (0 until nF).filter { mesh.faceGroup[it] > 1.5f }
        if (hairFaces.isEmpty()) return@run emptyList()
        // faces are picked in proportion to their area, so the strands are spread evenly over the hair
        val cum = DoubleArray(hairFaces.size)
        var acc = 0.0
        for ((k, tf) in hairFaces.withIndex()) {
            val a0 = f[3 * tf]; val b0 = f[3 * tf + 1]; val c0 = f[3 * tf + 2]
            val ux = v[3 * b0] - v[3 * a0]; val uy = v[3 * b0 + 1] - v[3 * a0 + 1]; val uz = v[3 * b0 + 2] - v[3 * a0 + 2]
            val wx = v[3 * c0] - v[3 * a0]; val wy = v[3 * c0 + 1] - v[3 * a0 + 1]; val wz = v[3 * c0 + 2] - v[3 * a0 + 2]
            val cx = uy * wz - uz * wy; val cy = uz * wx - ux * wz; val cz = ux * wy - uy * wx
            acc += 0.5 * sqrt((cx * cx + cy * cy + cz * cz).toDouble())
            cum[k] = acc
        }
        val list = ArrayList<Strand>()
        var topN = 0
        var sideN = 0
        var tries = 0
        while ((topN < HAIR_STRANDS || sideN < SIDE_STRANDS) && tries++ < (HAIR_STRANDS + SIDE_STRANDS) * 14) {
            val pick = rnd.nextDouble() * acc
            var lo = 0; var hi = hairFaces.size - 1
            while (lo < hi) { val mid = (lo + hi) ushr 1; if (cum[mid] < pick) lo = mid + 1 else hi = mid }
            val t = hairFaces[lo]
            val a = f[3 * t]; val b = f[3 * t + 1]; val c = f[3 * t + 2]
            var u = rnd.nextFloat(); var w = rnd.nextFloat()
            if (u + w > 1f) { u = 1f - u; w = 1f - w }
            val s0 = 1f - u - w
            val cover = (((mesh.paint[a] ushr 24) and 0xFF) * s0 + ((mesh.paint[b] ushr 24) and 0xFF) * u + ((mesh.paint[c] ushr 24) and 0xFF) * w) / 255f
            if (cover < 0.30f) continue                       // no strands on the faded, shaved part
            val px = s0 * v[3 * a] + u * v[3 * b] + w * v[3 * c]
            val py = s0 * v[3 * a + 1] + u * v[3 * b + 1] + w * v[3 * c + 1]
            val pz = s0 * v[3 * a + 2] + u * v[3 * b + 2] + w * v[3 * c + 2]
            val nx = s0 * mesh.normals[3 * a] + u * mesh.normals[3 * b] + w * mesh.normals[3 * c]
            val ny = s0 * mesh.normals[3 * a + 1] + u * mesh.normals[3 * b + 1] + w * mesh.normals[3 * c + 1]
            val nz = s0 * mesh.normals[3 * a + 2] + u * mesh.normals[3 * b + 2] + w * mesh.normals[3 * c + 2]
            val nl = max(sqrt(nx * nx + ny * ny + nz * nz), 1e-6f)
            // the flow: swept forward and to the left on top, falling on the sides and the back
            val fringe = pz > 0.26f && py < 0.66f && py > 0.22f     // the front edge: the fringe that falls over the forehead
            val onTop = pz > 0.10f && py > 0.30f
            val isTop = onTop || fringe
            if (isTop && topN >= HAIR_STRANDS) continue
            if (!isTop && sideN >= SIDE_STRANDS) continue
            val jx = (rnd.nextFloat() - 0.5f) * 0.7f
            val jz = (rnd.nextFloat() - 0.5f) * 0.5f
            var fx = if (fringe) -0.30f + jx * 0.8f else if (onTop) -0.55f + jx else 0.15f * jx
            var fy = if (fringe) -0.95f else if (onTop) 0.10f + 0.3f * (rnd.nextFloat() - 0.5f) else -1.0f
            var fz = if (fringe) 0.35f + jz * 0.6f else if (onTop) 0.70f + jz else -0.10f + jz * 0.3f
            // remove the part along the normal: the strand runs along the surface
            val d = (fx * nx + fy * ny + fz * nz) / nl / nl
            fx -= d * nx; fy -= d * ny; fz -= d * nz
            val fl = max(sqrt(fx * fx + fy * fy + fz * fz), 1e-6f)
            fx /= fl; fy /= fl; fz /= fl
            // its coordinates in the triangle's edges (least squares in the plane of the triangle)
            val e1x = v[3 * b] - v[3 * a]; val e1y = v[3 * b + 1] - v[3 * a + 1]; val e1z = v[3 * b + 2] - v[3 * a + 2]
            val e2x = v[3 * c] - v[3 * a]; val e2y = v[3 * c + 1] - v[3 * a + 1]; val e2z = v[3 * c + 2] - v[3 * a + 2]
            val g11 = e1x * e1x + e1y * e1y + e1z * e1z
            val g12 = e1x * e2x + e1y * e2y + e1z * e2z
            val g22 = e2x * e2x + e2y * e2y + e2z * e2z
            val r1 = fx * e1x + fy * e1y + fz * e1z
            val r2 = fx * e2x + fy * e2y + fz * e2z
            val det = g11 * g22 - g12 * g12
            if (kotlin.math.abs(det) < 1e-9f) continue
            val al = (r1 * g22 - r2 * g12) / det
            val be = (r2 * g11 - r1 * g12) / det
            val tone = if (rnd.nextFloat() < 0.06f) 2 else if (rnd.nextFloat() < 0.38f) 1 else 0
            if (isTop) topN++ else sideN++
            list += Strand(a, b, c, u, w, al, be, (if (fringe) 0.085f + 0.095f * rnd.nextFloat() else if (onTop) 0.055f + 0.085f * rnd.nextFloat() else 0.040f + 0.040f * rnd.nextFloat()), 0.30f + 0.55f * rnd.nextFloat(), (rnd.nextFloat() - 0.5f) * 1.5f, tone)
        }
        list
    }
    private val strandLines = Array(3) { FloatArray((HAIR_STRANDS + SIDE_STRANDS) * 8) }
    private val strandCounts = IntArray(3)
    private val strandPaint = Paint().apply { isAntiAlias = true; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }

    private fun drawHairStrands(nc: Canvas, v: FloatArray, nrm: FloatArray, r: Float, strokePx: Float, amp: Float) {
        if (skin == 0 || strands.isEmpty()) return
        strandCounts.fill(0)
        for (s in strands) {
            val s0 = 1f - s.u - s.v
            val a = s.a; val b = s.b; val c = s.c
            val nzr = s0 * nrm[3 * a + 2] + s.u * nrm[3 * b + 2] + s.v * nrm[3 * c + 2]
            if (nzr < -0.05f) continue                       // on the far side of the head
            val rx = s0 * v[3 * a] + s.u * v[3 * b] + s.v * v[3 * c]
            val ry = s0 * v[3 * a + 1] + s.u * v[3 * b + 1] + s.v * v[3 * c + 1]
            val rz = s0 * v[3 * a + 2] + s.u * v[3 * b + 2] + s.v * v[3 * c + 2]
            val nx = s0 * nrm[3 * a] + s.u * nrm[3 * b] + s.v * nrm[3 * c]
            val ny = s0 * nrm[3 * a + 1] + s.u * nrm[3 * b + 1] + s.v * nrm[3 * c + 1]
            // the posed direction along the surface
            val tx = s.al * (v[3 * b] - v[3 * a]) + s.be * (v[3 * c] - v[3 * a])
            val ty = s.al * (v[3 * b + 1] - v[3 * a + 1]) + s.be * (v[3 * c + 1] - v[3 * a + 1])
            val k = CAM_D / max(CAM_D - rz, 0.35f) * r
            val x0 = xs[a] * s0 + xs[b] * s.u + xs[c] * s.v
            val y0 = ys[a] * s0 + ys[b] * s.u + ys[c] * s.v
            val len = s.len
            // the strand lifts off the surface, bends, and its tip flicks away from the head
            val mx = x0 + (tx * len * 0.5f + nx * len * 0.35f * s.lift) * k
            val my = y0 - (ty * len * 0.5f + ny * len * 0.35f * s.lift) * k
            val ex = x0 + (tx * len + nx * len * 0.25f * s.lift + s.bend * len * 0.5f * ty) * k
            val ey = y0 - (ty * len + ny * len * 0.25f * s.lift - s.bend * len * 0.5f * tx) * k
            val arr = strandLines[s.tone]
            val o = strandCounts[s.tone]
            arr[o] = x0; arr[o + 1] = y0; arr[o + 2] = mx; arr[o + 3] = my
            arr[o + 4] = mx; arr[o + 5] = my; arr[o + 6] = ex; arr[o + 7] = ey
            strandCounts[s.tone] = o + 8
        }
        val colours = intArrayOf(0xFF150E0B.toInt(), 0xFF2A1D16.toInt(), 0xFF5E4F44.toInt())
        val alphas = floatArrayOf(235f, 215f, 170f)
        strandPaint.strokeWidth = max(0.9f, strokePx * 0.7f)
        for (i in 0 until 3) {
            if (strandCounts[i] == 0) continue
            strandPaint.color = withAlpha(colours[i], alphas[i])
            nc.drawLines(strandLines[i], 0, strandCounts[i], strandPaint)
        }
    }


    // ── the fringe: locks ────────────────────────────────────────────────────

    private class Lock(val a: Int, val b: Int, val c: Int, val u: Float, val v: Float, val al: Float, val be: Float, val len: Float, val bend: Float, val width: Float)

    /**
     * The fringe is a set of locks, each a curved, tapering ribbon of hair (thick at the root, pointed at the tip) with a highlight along
     * it and a soft shadow on the forehead. They start a little above the hairline, run diagonally over the forehead from the upper
     * right to the lower left and overlap one another. Anchored like the strands, so they turn with the head.
     */
    private val locks: List<Lock> = run {
        val rnd = kotlin.random.Random(59)
        val f = mesh.faces
        val v = mesh.verts
        val faces = (0 until nF).filter { mesh.faceGroup[it] > 1.5f }
        if (faces.isEmpty()) return@run emptyList()
        fun smooth(e0: Float, e1: Float, x: Float): Float { val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f); return t * t * (3f - 2f * t) }
        val list = ArrayList<Lock>()
        var tries = 0
        while (list.size < HAIR_LOCKS && tries++ < 20000) {
            val t = faces[rnd.nextInt(faces.size)]
            val a = f[3 * t]; val b = f[3 * t + 1]; val c = f[3 * t + 2]
            var u = rnd.nextFloat(); var w = rnd.nextFloat()
            if (u + w > 1f) { u = 1f - u; w = 1f - w }
            val s0 = 1f - u - w
            val px = s0 * v[3 * a] + u * v[3 * b] + w * v[3 * c]
            val py = s0 * v[3 * a + 1] + u * v[3 * b + 1] + w * v[3 * c + 1]
            val pz = s0 * v[3 * a + 2] + u * v[3 * b + 2] + w * v[3 * c + 2]
            val hx = px + 0.035f
            val hairline = 0.31f + 0.27f * smooth(-0.42f, 0.42f, hx) + 0.10f * smooth(-0.40f, -0.58f, hx)
            if (pz < 0.28f || py < hairline + 0.02f || py > hairline + 0.13f) continue   // a root a little above the hairline
            val nx = s0 * mesh.normals[3 * a] + u * mesh.normals[3 * b] + w * mesh.normals[3 * c]
            val ny = s0 * mesh.normals[3 * a + 1] + u * mesh.normals[3 * b + 1] + w * mesh.normals[3 * c + 1]
            val nz = s0 * mesh.normals[3 * a + 2] + u * mesh.normals[3 * b + 2] + w * mesh.normals[3 * c + 2]
            val nl = max(sqrt(nx * nx + ny * ny + nz * nz), 1e-6f)
            // the lock runs down and to the left, over the forehead
            var fx = -0.42f + (rnd.nextFloat() - 0.5f) * 0.4f
            var fy = -0.88f
            var fz = 0.28f + (rnd.nextFloat() - 0.5f) * 0.2f
            val d = (fx * nx + fy * ny + fz * nz) / nl / nl
            fx -= d * nx; fy -= d * ny; fz -= d * nz
            val fl = max(sqrt(fx * fx + fy * fy + fz * fz), 1e-6f)
            fx /= fl; fy /= fl; fz /= fl
            val e1x = v[3 * b] - v[3 * a]; val e1y = v[3 * b + 1] - v[3 * a + 1]; val e1z = v[3 * b + 2] - v[3 * a + 2]
            val e2x = v[3 * c] - v[3 * a]; val e2y = v[3 * c + 1] - v[3 * a + 1]; val e2z = v[3 * c + 2] - v[3 * a + 2]
            val g11 = e1x * e1x + e1y * e1y + e1z * e1z
            val g12 = e1x * e2x + e1y * e2y + e1z * e2z
            val g22 = e2x * e2x + e2y * e2y + e2z * e2z
            val r1 = fx * e1x + fy * e1y + fz * e1z
            val r2 = fx * e2x + fy * e2y + fz * e2z
            val det = g11 * g22 - g12 * g12
            if (kotlin.math.abs(det) < 1e-9f) continue
            val al = (r1 * g22 - r2 * g12) / det
            val be = (r2 * g11 - r1 * g12) / det
            // the locks on the left (where the hairline is lowest) are the longest
            val longer = 1f - smooth(-0.45f, 0.30f, hx)
            list += Lock(a, b, c, u, w, al, be, 0.15f + 0.10f * longer + 0.05f * rnd.nextFloat(), (rnd.nextFloat() - 0.4f) * 0.9f, 0.85f + 0.5f * rnd.nextFloat())
        }
        list
    }
    private val lockPath = android.graphics.Path()
    private val lockPaint = Paint().apply { isAntiAlias = true; style = Paint.Style.FILL }
    private val lockPts = FloatArray(2 * (LOCK_SAMPLES + 1))
    private val lockLeft = FloatArray(2 * (LOCK_SAMPLES + 1))
    private val lockRight = FloatArray(2 * (LOCK_SAMPLES + 1))

    private fun drawLocks(nc: Canvas, v: FloatArray, nrm: FloatArray, r: Float, strokePx: Float) {
        if (skin == 0 || locks.isEmpty()) return
        for (pass in 0 until 2) {                // first the soft shadows on the forehead, then the locks
            for (lk in locks) {
                val s0 = 1f - lk.u - lk.v
                val a = lk.a; val b = lk.b; val c = lk.c
                val nzr = s0 * nrm[3 * a + 2] + lk.u * nrm[3 * b + 2] + lk.v * nrm[3 * c + 2]
                if (nzr < 0.05f) continue
                val rz = s0 * v[3 * a + 2] + lk.u * v[3 * b + 2] + lk.v * v[3 * c + 2]
                val nx = s0 * nrm[3 * a] + lk.u * nrm[3 * b] + lk.v * nrm[3 * c]
                val ny = s0 * nrm[3 * a + 1] + lk.u * nrm[3 * b + 1] + lk.v * nrm[3 * c + 1]
                val tx = lk.al * (v[3 * b] - v[3 * a]) + lk.be * (v[3 * c] - v[3 * a])
                val ty = lk.al * (v[3 * b + 1] - v[3 * a + 1]) + lk.be * (v[3 * c + 1] - v[3 * a + 1])
                val k = CAM_D / max(CAM_D - rz, 0.35f) * r
                val x0 = xs[a] * s0 + xs[b] * lk.u + xs[c] * lk.v
                val y0 = ys[a] * s0 + ys[b] * lk.u + ys[c] * lk.v
                val len = lk.len
                // a quadratic curve: the root, a control point that lifts and bends, the tip
                val cx = x0 + (tx * len * 0.5f + nx * len * 0.20f) * k + lk.bend * len * 0.5f * ty * k
                val cy = y0 - (ty * len * 0.5f + ny * len * 0.20f) * k - lk.bend * len * 0.5f * tx * k
                val ex = x0 + (tx * len + nx * len * 0.05f) * k
                val ey = y0 - (ty * len + ny * len * 0.05f) * k
                val shadow = pass == 0
                val offY = if (shadow) r * 0.018f else 0f
                for (i in 0..LOCK_SAMPLES) {
                    val t = i / LOCK_SAMPLES.toFloat()
                    val q = 1f - t
                    lockPts[2 * i] = q * q * x0 + 2f * q * t * cx + t * t * ex
                    lockPts[2 * i + 1] = q * q * y0 + 2f * q * t * cy + t * t * ey + offY
                }
                // a tapering ribbon: wide at the root, a point at the tip, a little fuller in the middle
                for (i in 0..LOCK_SAMPLES) {
                    val t = i / LOCK_SAMPLES.toFloat()
                    val i0 = max(i - 1, 0); val i1 = min(i + 1, LOCK_SAMPLES)
                    var dx = lockPts[2 * i1] - lockPts[2 * i0]; var dy = lockPts[2 * i1 + 1] - lockPts[2 * i0 + 1]
                    val dl = max(kotlin.math.hypot(dx, dy), 1e-3f)
                    dx /= dl; dy /= dl
                    val half = r * 0.022f * lk.width * (1f - t) * (0.75f + 0.5f * kotlin.math.sin(Math.PI.toFloat() * min(t * 1.6f, 1f)))
                    lockLeft[2 * i] = lockPts[2 * i] - dy * half; lockLeft[2 * i + 1] = lockPts[2 * i + 1] + dx * half
                    lockRight[2 * i] = lockPts[2 * i] + dy * half; lockRight[2 * i + 1] = lockPts[2 * i + 1] - dx * half
                }
                lockPath.reset()
                lockPath.moveTo(lockLeft[0], lockLeft[1])
                for (i in 1..LOCK_SAMPLES) lockPath.lineTo(lockLeft[2 * i], lockLeft[2 * i + 1])
                for (i in LOCK_SAMPLES downTo 0) lockPath.lineTo(lockRight[2 * i], lockRight[2 * i + 1])
                lockPath.close()
                lockPaint.style = Paint.Style.FILL
                if (shadow) {
                    lockPaint.color = withAlpha(0xFF1A0F0A.toInt(), 48f)
                    nc.drawPath(lockPath, lockPaint)
                } else {
                    lockPaint.color = 0xFF1B120D.toInt()
                    nc.drawPath(lockPath, lockPaint)
                    // the sheen: two thin highlights along the lock, and its lit edge
                    lockPaint.style = Paint.Style.STROKE
                    lockPaint.strokeCap = Paint.Cap.ROUND
                    lockPaint.strokeWidth = max(1f, strokePx * 0.8f)
                    lockPaint.color = withAlpha(0xFF7A6455.toInt(), 150f)
                    for (side in 0..1) {
                        val mixA = if (side == 0) 0.55f else 0.20f
                        for (i in 1 until LOCK_SAMPLES - 1) {
                            val ax = lockPts[2 * i] + (lockLeft[2 * i] - lockPts[2 * i]) * mixA
                            val ay = lockPts[2 * i + 1] + (lockLeft[2 * i + 1] - lockPts[2 * i + 1]) * mixA
                            val bx = lockPts[2 * i + 2] + (lockLeft[2 * i + 2] - lockPts[2 * i + 2]) * mixA
                            val by = lockPts[2 * i + 3] + (lockLeft[2 * i + 3] - lockPts[2 * i + 3]) * mixA
                            if (side == 1 && i % 2 == 0) continue
                            nc.drawLine(ax, ay, bx, by, lockPaint)
                        }
                    }
                    lockPaint.strokeWidth = max(0.8f, strokePx * 0.6f)
                    lockPaint.color = withAlpha(0xFF0C0705.toInt(), 200f)
                    for (i in 0 until LOCK_SAMPLES) nc.drawLine(lockRight[2 * i], lockRight[2 * i + 1], lockRight[2 * i + 2], lockRight[2 * i + 3], lockPaint)
                    lockPaint.style = Paint.Style.FILL
                }
            }
        }
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

    private class Hair(val u: Float, val off: Float, val len: Float, val jitter: Float)

    private val browHairs: List<Hair> = run {
        val rnd = kotlin.random.Random(11)
        List(BROW_HAIRS) { Hair(rnd.nextFloat(), rnd.nextFloat() * 2f - 1f, 0.7f + 0.6f * rnd.nextFloat(), (rnd.nextFloat() - 0.5f) * 0.4f) }
    }
    private val hairLines = FloatArray(BROW_HAIRS * 4)

    /** The lid rims of each eye, as columns along the eye: the jagged edge of the opening becomes a smooth curve of column averages. */
    private class LidCurve(val verts: IntArray, val column: IntArray)
    private val lidCurves: Array<Array<LidCurve>> = run {
        val eyes = mesh.eyeFirst.size
        val rim = mesh.eyelidRim
        Array(eyes) { e ->
            Array(2) { flag ->
                val set = LinkedHashSet<Int>()
                for (k in 0 until rim.size / 3) {
                    if (rim[3 * k + 2] != flag) continue
                    for (v in intArrayOf(rim[3 * k], rim[3 * k + 1])) {
                        val x = mesh.verts[3 * v]
                        val nearest = (0 until eyes).minByOrNull { kotlin.math.abs(mesh.eyeCentre[3 * it] - x) }
                        if (nearest == e) set += v
                    }
                }
                val verts = set.toIntArray()
                val lo = verts.minOfOrNull { mesh.verts[3 * it] } ?: 0f
                val hi = verts.maxOfOrNull { mesh.verts[3 * it] } ?: 1f
                LidCurve(verts, IntArray(verts.size) { (((mesh.verts[3 * verts[it]] - lo) / max(hi - lo, 1e-4f)) * (LID_COLUMNS - 1)).toInt().coerceIn(0, LID_COLUMNS - 1) })
            }
        }
    }
    private val curveX = FloatArray(LID_COLUMNS)
    private val curveY = FloatArray(LID_COLUMNS)
    private val curveN = FloatArray(LID_COLUMNS)
    private val lashRnd = FloatArray(40) { kotlin.random.Random(23 + it).nextFloat() }
    private val rimLines = Array(2) { FloatArray(mesh.eyelidRim.size / 3 * 4) }

    /** The smooth curve of one lid rim as points (screen space): the column averages of its vertices, lightly smoothed. Returns the count. */
    private fun lidPoints(curve: LidCurve): Int {
        java.util.Arrays.fill(curveN, 0f); java.util.Arrays.fill(curveX, 0f); java.util.Arrays.fill(curveY, 0f)
        for ((k, v) in curve.verts.withIndex()) {
            val c = curve.column[k]
            curveX[c] += xs[v]; curveY[c] += ys[v]; curveN[c] += 1f
        }
        var n = 0
        for (c in 0 until LID_COLUMNS) if (curveN[c] > 0f) { curveX[n] = curveX[c] / curveN[c]; curveY[n] = curveY[c] / curveN[c]; n++ }
        for (pass in 0 until 2) for (i in 1 until n - 1) curveY[i] = 0.25f * curveY[i - 1] + 0.5f * curveY[i] + 0.25f * curveY[i + 1]
        return n
    }

    /**
     * The eyes' finishing: a smooth lash line along each lid (thicker towards the outer corner), a soft fold above the upper lid, real
     * lashes (long and curved at the outer half, short and fine below) that lie down when the eye shuts, and a catchlight in the
     * iris. Everything is anchored to the lid vertices and the eyeball, so it follows the blinks and the gaze.
     */
    private fun drawEyes(scope: DrawScope, avatar: HoloAvatar, r: Float, primary: Int, strokePx: Float, face: Float, midX: Float) {
        val open = ((1f - avatar.blink) * avatar.lids.coerceIn(0f, 1f)).coerceIn(0f, 1f)
        val lash = if (skin > 0) 0xFF1E120E.toInt() else primary
        val fold = if (skin > 0) 0xFF6B4636.toInt() else primary
        scope.drawIntoCanvas { canvas ->
            val nc = canvas.nativeCanvas
            for (e in lidCurves.indices) {
                val up = lidCurves[e][1]; val low = lidCurves[e][0]
                if (up.verts.isEmpty()) continue
                val n = lidPoints(up)
                if (n < 2) continue
                val ux = curveX.copyOf(n); val uy = curveY.copyOf(n)
                // index 0 is made the inner end: the one nearer the face's middle
                if (kotlin.math.abs(ux[0] - midX) > kotlin.math.abs(ux[n - 1] - midX)) { ux.reverse(); uy.reverse() }
                hairPaint.color = withAlpha(fold, 70f * face * (0.4f + 0.6f * open)); hairPaint.strokeWidth = strokePx * 1.4f
                for (i in 0 until n - 1) nc.drawLine(ux[i], uy[i] - r * 0.016f, ux[i + 1], uy[i + 1] - r * 0.016f, hairPaint)
                hairPaint.color = withAlpha(lash, 235f * face)
                for (i in 0 until n - 1) {
                    hairPaint.strokeWidth = strokePx * (0.8f + 1.5f * (i + 0.5f) / (n - 1))
                    nc.drawLine(ux[i], uy[i], ux[i + 1], uy[i + 1], hairPaint)
                }
                // the lashes curl up and outwards when the eye is open, and lie down when it shuts
                val outward = if (ux[n - 1] < ux[0]) -1f else 1f
                val lashes = 15
                hairPaint.color = withAlpha(lash, 215f * face)
                hairPaint.strokeWidth = max(1f, strokePx * 0.65f)
                for (k in 0 until lashes) {
                    val t = (k + 0.5f) / lashes
                    val pos = t * (n - 1)
                    val i = pos.toInt().coerceIn(0, n - 2)
                    val f = pos - i
                    val bx = ux[i] + (ux[i + 1] - ux[i]) * f
                    val by = uy[i] + (uy[i + 1] - uy[i]) * f
                    val len = r * 0.030f * (0.45f + 0.75f * t) * (0.8f + 0.4f * lashRnd[k])
                    val lift = 2f * open - 1f                      // +1 open (up), -1 shut (down)
                    val dx = outward * (0.30f + 0.55f * t) * kotlin.math.abs(lift).coerceAtLeast(0.5f)
                    val dy = -lift * (1.0f - 0.35f * t)
                    val dl = max(kotlin.math.hypot(dx, dy), 1e-3f)
                    val midx = bx + dx / dl * len * 0.55f; val midy = by + dy / dl * len * 0.55f
                    val tipX = bx + dx / dl * len; val tipY = by + dy / dl * len
                    nc.drawLine(bx, by, midx, midy, hairPaint)
                    nc.drawLine(midx, midy, tipX + outward * len * 0.10f, tipY - lift * len * 0.05f, hairPaint)
                }
                // the lower lid: a fine line and a few short lashes
                val m = lidPoints(low)
                if (m >= 2) {
                    hairPaint.color = withAlpha(lash, 120f * face); hairPaint.strokeWidth = strokePx * 0.65f
                    for (i in 0 until m - 1) nc.drawLine(curveX[i], curveY[i], curveX[i + 1], curveY[i + 1], hairPaint)
                    hairPaint.color = withAlpha(lash, 105f * face); hairPaint.strokeWidth = max(1f, strokePx * 0.5f)
                    for (k in 0 until 7) {
                        val pos = (k + 0.5f) / 7f * (m - 1)
                        val i = pos.toInt().coerceIn(0, m - 2)
                        val f = pos - i
                        val bx = curveX[i] + (curveX[i + 1] - curveX[i]) * f
                        val by = curveY[i] + (curveY[i + 1] - curveY[i]) * f
                        nc.drawLine(bx, by, bx + outward * r * 0.004f, by + r * 0.010f * (0.7f + 0.6f * lashRnd[20 + k]) * (0.3f + 0.7f * open), hairPaint)
                    }
                }
                // the catchlight, on the eyeball's front pole (17 vertices after the eye's first: past the backing disc)
                if (open > 0.4f && mesh.eyeFirst.size > e) {
                    val pole = mesh.eyeFirst[e] + 17
                    if (pole < nV) {
                        hairPaint.style = Paint.Style.FILL
                        hairPaint.color = withAlpha(0xFFFFFFFF.toInt(), 235f * face * open)
                        nc.drawCircle(xs[pole] - r * 0.010f, ys[pole] - r * 0.010f, max(1.2f, r * 0.0075f), hairPaint)
                        hairPaint.color = withAlpha(0xFFFFFFFF.toInt(), 110f * face * open)
                        nc.drawCircle(xs[pole] + r * 0.008f, ys[pole] + r * 0.009f, max(0.8f, r * 0.0038f), hairPaint)
                        hairPaint.style = Paint.Style.STROKE
                    }
                }
            }
        }
    }

    /**
     * The details of the face that are lines rather than surface: the brows (with a skin, a few hundred hairs following the brow's
     * curve, thick and upright at the inner end, thinner and flatter at the outer end), the rims of the eyelids and the two lip edges
     * along the mouth line. They are anchored to mesh vertices, so they follow the head, the lids and the jaw.
     */
    private fun drawFeatures(scope: DrawScope, avatar: HoloAvatar, r: Float, primary: Int, accent: Int, bg: Int, amp: Float, strokePx: Float) {
        val face = max(0f, cos(avatar.yaw) * cos(avatar.pitch)).let { it * it }
        if (face < 0.02f) return
        val lm = mesh.landmarks
        val roundCap = androidx.compose.ui.graphics.StrokeCap.Round
        val roundJoin = androidx.compose.ui.graphics.StrokeJoin.Round
        val midX = lm.getValue("lips_out").let { ring -> ring.sumOf { xs[it].toDouble() }.toFloat() / ring.size }
        val hairColour = 0xFF34241C.toInt()

        // brows
        for (key in listOf("brow_l", "brow_r")) {
            val idx = lm.getValue(key)
            val n = idx.size
            val inner0 = kotlin.math.abs(xs[idx[0]] - midX) < kotlin.math.abs(xs[idx[n - 1]] - midX)
            val px = FloatArray(n) { xs[idx[if (inner0) it else n - 1 - it]] }
            val py = FloatArray(n) { ys[idx[if (inner0) it else n - 1 - it]] }
            if (skin == 0) {
                val brow = ring(idx)
                scope.drawPath(brow, Color(withAlpha(primary, 60f * face)), style = Stroke(width = strokePx * 4.5f, cap = roundCap))
                scope.drawPath(brow, Color(withAlpha(primary, 230f * face)), style = Stroke(width = strokePx * 1.8f, cap = roundCap))
                continue
            }
            val cum = FloatArray(n)
            for (i in 1 until n) cum[i] = cum[i - 1] + kotlin.math.hypot(px[i] - px[i - 1], py[i] - py[i - 1])
            val total = max(cum[n - 1], 1f)
            var count = 0
            val underlay = Path()
            for (step in 0..24) {
                val u = step / 24f
                val target = u * total
                var seg = 1
                while (seg < n - 1 && cum[seg] < target) seg++
                val segLen = max(cum[seg] - cum[seg - 1], 1e-3f)
                val f = ((target - cum[seg - 1]) / segLen).coerceIn(0f, 1f)
                val cx = px[seg - 1] + (px[seg] - px[seg - 1]) * f
                val cy = py[seg - 1] + (py[seg] - py[seg - 1]) * f
                if (step == 0) underlay.moveTo(cx, cy) else underlay.lineTo(cx, cy)
            }
            scope.drawPath(underlay, Color(withAlpha(hairColour, 90f * face)), style = Stroke(width = r * 0.030f, cap = roundCap, join = roundJoin))
            for (h in browHairs) {
                val target = h.u * total
                var seg = 1
                while (seg < n - 1 && cum[seg] < target) seg++
                val segLen = max(cum[seg] - cum[seg - 1], 1e-3f)
                val f = ((target - cum[seg - 1]) / segLen).coerceIn(0f, 1f)
                var tx = (px[seg] - px[seg - 1]) / segLen
                var ty = (py[seg] - py[seg - 1]) / segLen
                var nx = -ty; var ny = tx
                if (ny > 0f) { nx = -nx; ny = -ny }              // the normal points up the screen
                val width = r * (0.050f - 0.026f * h.u)         // the brow is thicker at its inner end
                val rx = px[seg - 1] + (px[seg] - px[seg - 1]) * f + nx * h.off * width * 0.5f
                val ry = py[seg - 1] + (py[seg] - py[seg - 1]) * f + ny * h.off * width * 0.5f
                val angle = (0.95f - 1.1f * h.u) + h.jitter        // upright at the inner end, flat (and a little down) at the outer end
                val ca = cos(angle); val sa = kotlin.math.sin(angle)
                val length = r * 0.048f * h.len * (1.0f - 0.35f * h.u)
                hairLines[count++] = rx; hairLines[count++] = ry
                hairLines[count++] = rx + (tx * ca + nx * sa) * length; hairLines[count++] = ry + (ty * ca + ny * sa) * length
            }
            scope.drawIntoCanvas { canvas ->
                hairPaint.strokeWidth = max(1f, strokePx * 0.85f)
                hairPaint.color = withAlpha(hairColour, 215f * face)
                canvas.nativeCanvas.drawLines(hairLines, 0, count, hairPaint)
            }
        }

        drawEyes(scope, avatar, r, primary, strokePx, face, midX)

        // the two lip edges along the mouth line: one line when the mouth is shut, two when it opens
        val lipLine = if (skin > 0) 0xFF6E2A38.toInt() else primary
        for ((chain, alpha) in listOf(mesh.mouthUpper to 200f, mesh.mouthLower to 170f)) {
            val path = Path()
            for ((k, i) in chain.withIndex()) if (k == 0) path.moveTo(xs[i], ys[i]) else path.lineTo(xs[i], ys[i])
            scope.drawPath(path, Color(withAlpha(lipLine, alpha * face)), style = Stroke(width = strokePx * 1.2f, cap = roundCap, join = roundJoin))
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
