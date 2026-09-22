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
private val CAP_COLOURS = intArrayOf(0, 0xFF1C1E24.toInt(), 0xFF23508F.toInt(), 0xFFB3282F.toInt(), 0xFFE9E9EE.toInt(), 0xFF3F5B3B.toInt(), 0xFF3B4744.toInt())
private const val BUCKETS = 4
private const val MIN_ALPHA = 0.05f
private const val LUT_N = 192
private const val BROW_HAIRS = 160
private const val LID_COLUMNS = 9
private const val HAIR_FIBRE_LOCKS = 600
private const val FIBRES_PER_LOCK = 8

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

private val SKIN_TONES = intArrayOf(0xF1C9A8, 0xD9A47C, 0xB07A54, 0x7A4E36, 0x69B4F0)   // the fifth is the light blue of the blue hologram
private const val DEEP_BLUE = 0xFF0C2160.toInt()
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

    /** The hologram over the skin: a skin tone, tinted by the light of the web, with the web drawn over it, and no hair. */
    var holo = false

    /** With the hologram look, the hair: a dark mass under fibres of light (see FiberHair.kt). */
    var holoHair = false

    /** The blue skin: a light blue with deep blue accents (edge, hollows, some of the circuits) and more gold circuits. */
    var blueMix = false

    /** Fine strands drawn over the hair (see drawFibres); off for long hair, which hangs in front of the face. */
    var fibreOverlay = true

    /** The colour of the eyebrows: the hair's. */
    var browColour = 0xFF34241C.toInt()
    /** 0 = natural lips; 1..4 = rose, red, plum, coral. */
    var lips = 0
    private var bgColor = 0
    private val web = NetworkWeb(mesh)
    private val fibres = FloatArray(HAIR_FIBRE_LOCKS * FIBRES_PER_LOCK * 4 * 16)
    private val fibrePaint = Paint().apply { isAntiAlias = true; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
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
            if (holo) drawCircuits(nc, n, amp, primary, bg, strokePx, avatar.time)
            if (holoHair) fibres3.draw(nc, xs, ys, n, primary, 0xFFFFB640.toInt(), avatar.time, strokePx, linePaint, if (cap > 0) capGeo.hiddenLock else null)
            if (cap > 0) drawCap(nc, v, n, cx, cy, r, strokePx)
            if (fibreOverlay) drawFibres(nc, v, n, strokePx, r)
        }
        drawFeatures(scope, avatar, r, primary, accent, bg, amp, strokePx)
    }

    private fun buildLut(bg: Int, primary: Int) {
        val key = (bg.toLong() shl 32) xor primary.toLong()
        if (key == lutKey) return
        lutKey = key
        for (i in 0 until LUT_N) lut[i] = blend(bg, primary, 255f * (i + 0.5f) / LUT_N * 0.36f) // a dark blue glowing volume: the web still is what shines
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
            if (cap > 0 && capGeo.hiddenFace[t]) continue
            if ((skin == 0 || (holo && !holoHair)) && mesh.faceGroup[t] > 1.5f) continue // the hair belongs to the skin looks, not to the web or the hologram
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
            val z = (v[3 * a + 2] + v[3 * b + 2] + v[3 * c + 2]) / 3f + mesh.faceGroup[t].let { g -> if (g > 1.5f) 0.05f else if (g > 0.5f) 0f else -1000f }
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
        if (pnt != 0 && ((pnt ushr 24) and 0xFF) < 255) {
            // hair: the alpha byte says how much of it there is over the skin (254 = all of it)
            val cover = (((pnt ushr 24) and 0xFF) / 254f).coerceIn(0f, 1f)
            val diffuse = 0.42f + 0.80f * vlam
            val hairLit = lit(0xFF000000.toInt() or (pnt and 0x00FFFFFF), diffuse)
            // a sheen where the surface faces the light: the half vector of the key light
            val spec = Math.pow((vx * -0.22f + vy * 0.28f + vz * 0.93f).coerceIn(0f, 1f).toDouble(), 12.0).toFloat()
            val sheen = (spec * 50f).toInt()
            var hair = argb(255, (((hairLit shr 16) and 0xFF) + sheen).coerceAtMost(255), (((hairLit shr 8) and 0xFF) + sheen).coerceAtMost(255), ((hairLit and 0xFF) + (sheen * 0.9f).toInt()).coerceAtMost(255))
            if (holoHair) hair = lit(0xFF0D1B2B.toInt(), 0.55f + 0.9f * vlam)
            if (cover > 0.995f) return hair
            val skinRgb = 0xFF000000.toInt() or SKIN_TONES[skin - 1]
            val ks = (0.30f + 0.85f * vlam + 0.10f * vz.coerceIn(0f, 1f)).coerceIn(0.15f, 1.15f) * (0.94f + 0.12f * amp)
            return mix(lit(skinRgb, ks), hair, cover)
        }
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
        if (!holo) {
            // a faint natural sheen (skin is not matte) and a touch of warmth where the light lands most, like blood under thin skin
            val spec = Math.pow((vx * -0.22f + vy * 0.28f + vz * 0.93f).coerceIn(0f, 1f).toDouble(), 30.0).toFloat()
            val sheen = (spec * 26f).toInt()
            val blush = (vlam * vlam * 9f).toInt()
            c = argb(
                255,
                (((c shr 16) and 0xFF) + sheen + blush).coerceAtMost(255),
                (((c shr 8) and 0xFF) + (sheen * 0.9f).toInt() + (blush * 0.35f).toInt()).coerceAtMost(255),
                (((c and 0xFF) + (sheen * 0.8f).toInt()).coerceAtMost(255)),
            )
        }
        if (lipW > 0.02f) {
            val lipRgb = if (lips > 0) 0xFF000000.toInt() or LIP_TONES[lips - 1] else mix(skinRgb, 0xFFB04A5A.toInt(), 0.7f)
            // the lips are lit less unevenly than the skin: the upper one faces the light and would otherwise come out pale
            c = mix(c, lit(lipRgb, 0.80f + 0.30f * vlam), lipW)
        }
        if (holo) {
            // a solid skin: a little brighter than the plain looks, a faint cool light at the contour only, and no melting into the background
            val edge = Math.pow((1f - vz.coerceIn(0f, 1f)).toDouble(), 2.4).toFloat()          // 0 facing the viewer, 1 at the contour
            c = if (blueMix) {
                // light blue, deeper in the hollows (away from the light) and with a deep blue edge all round
                val hollow = mix(lit(c, 1.16f), DEEP_BLUE, 0.55f * (1f - vlam))
                mix(hollow, DEEP_BLUE, 0.80f * edge)
            } else {
                mix(lit(c, 1.16f), mix(primaryColor, 0xFFFFFFFF.toInt(), 0.45f), 0.62f * edge)   // a soft bright edge instead of dots
            }
            return mix(bgColor, c, (mesh.fade[vi] * 1.9f).coerceIn(0f, 1f))
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
        if (skin > 0) return // a skin hides the web: the hologram looks are solid, with no veil of nodes and lines over the skin
        val w = web
        val gain = (0.85f + 0.5f * amp) * (if (holo) 0.42f else 1f)
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
            if (skin > 0 && !holo && bk < 2) continue
            if (holo && bk < 1) continue          // on a solid skin only the brighter nodes, at the contour, not a dotted veil over the face
            val arr = webNodes[bk]; val o = webNodeCounts[bk]
            arr[o] = wx[i]; arr[o + 1] = wy[i]
            webNodeCounts[bk] = o + 2
        }
        val sizes = floatArrayOf(1.3f, 2.1f, 3.4f)
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


    /**
     * Fine hairs along the locks, lit like real hair (Kajiya-Kay): each lock carries a bundle of strands that wave together (a slow, shared
     * wave with a small difference from one strand to the next), some stopping short of the tip. The strands are dark and light, and where
     * the strand's direction is right for the light there are two bands of sheen, as on real hair: a narrow, whitish one, and a wider,
     * tinted one nearer the root. A bundle is a hair's natural unit, so the locks read as clumps and not as a solid cap.
     */
    private fun drawFibres(nc: Canvas, v: FloatArray, nrm: FloatArray, strokePx: Float, r: Float) {
        if (skin == 0 || holo || mesh.lockCount == 0) return
        val rows = mesh.lockRows
        if (rows < 3) return
        val light = mix(browColour, 0xFF6B4E36.toInt(), 0.60f)
        val tint = mix(browColour, 0xFF6B4E36.toInt(), 0.70f)
        val perLock = FIBRES_PER_LOCK
        val locks = min(mesh.lockCount, HAIR_FIBRE_LOCKS)
        val cap = fibres.size - 4 * rows

        for (pass in 0 until 2) {
            var count = 0
            for (l in 0 until locks) {
                val base = mesh.lockFirst + l * 3 * rows
                if (nrm[3 * (base + 1) + 2] < 0.10f) continue           // the root faces away
                if (cap > 0 && capGeo.hiddenLock[l]) continue
                val lockHash = (l * -1640531535)
                val lockFreq = 1.2f + 1.6f * ((lockHash ushr 8) and 0xFF) / 255f
                val lockPhase = ((lockHash ushr 16) and 0xFF) / 255f * 6.2832f
                for (f in 0 until perLock) {
                    if ((f + l) % 2 != pass) continue
                    val hash = ((l * 31 + f * 1039) * -1640531535)
                    val u = -0.9f + 1.8f * (f + 0.5f + 0.35f * (((hash ushr 8) and 0xFF) / 255f - 0.5f)) / perLock
                    val wave = 0.10f + 0.10f * ((hash ushr 16) and 0xFF) / 255f
                    val stop = rows - (if (((hash ushr 24) and 3) == 0) 2 else 0)      // some strands are shorter
                    var px = 0f; var py = 0f
                    for (sIdx in 0 until stop) {
                        val li = base + 3 * sIdx; val ci = li + 1; val ri = li + 2
                        val half = 0.5f * sqrt((xs[ri] - xs[li]) * (xs[ri] - xs[li]) + (ys[ri] - ys[li]) * (ys[ri] - ys[li]))
                        var x = xs[ci] + (xs[ri] - xs[li]) * 0.5f * u
                        var y = ys[ci] + (ys[ri] - ys[li]) * 0.5f * u
                        // the shared wave, its size following the lock's width
                        val t = sIdx / (rows - 1f)
                        val shift = half * wave * kotlin.math.sin(t * lockFreq * 6.2832f + lockPhase + f * 0.35f)
                        val nx = ys[ri] - ys[li]; val ny = xs[li] - xs[ri]
                        val nl = sqrt(nx * nx + ny * ny).coerceAtLeast(1e-4f)
                        x += nx / nl * shift; y += ny / nl * shift
                        if (sIdx > 0 && count < cap) {
                            fibres[count++] = px; fibres[count++] = py; fibres[count++] = x; fibres[count++] = y
                        }
                        px = x; py = y
                    }
                }
            }
            fibrePaint.strokeWidth = max(0.7f, strokePx * (if (pass == 0) 0.62f else 0.5f))
            fibrePaint.color = if (pass == 0) withAlpha(0xFF120A06.toInt(), 130f) else withAlpha(light, 24f)
            nc.drawLines(fibres, 0, count, fibrePaint)
        }

        // The sheen: Kajiya-Kay, sin(angle between the strand and the half vector) to a power. The primary band is narrow and shifted
        // towards the tip, the secondary one wide and shifted the other way (it is the light that went through the hair).
        val hx = -0.22f; val hy = 0.28f; val hz = 0.93f
        for (l in 0 until locks) {
            val base = mesh.lockFirst + l * 3 * rows
            if (nrm[3 * (base + 1) + 2] < 0.10f) continue
            if (cap > 0 && capGeo.hiddenLock[l]) continue
            val lockHash = (l * -1640531535)
            val strands = 1 + ((lockHash ushr 8) and 1)
            for (sIdx in 1 until rows - 1) {
                val ci = base + 3 * sIdx + 1
                val ip = base + 3 * (sIdx - 1) + 1; val inx = base + 3 * (sIdx + 1) + 1
                var tx = v[3 * inx] - v[3 * ip]; var ty = v[3 * inx + 1] - v[3 * ip + 1]; var tz = v[3 * inx + 2] - v[3 * ip + 2]
                val tl = sqrt(tx * tx + ty * ty + tz * tz).coerceAtLeast(1e-6f)
                tx /= tl; ty /= tl; tz /= tl
                var nx = nrm[3 * ci]; var ny = nrm[3 * ci + 1]; var nz = nrm[3 * ci + 2]
                val nl = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-6f)
                nx /= nl; ny /= nl; nz /= nl
                if (nz < 0.05f) continue
                // the strand's direction tilted along the surface normal, which moves the band along the strand
                val th = tx * hx + ty * hy + tz * hz
                val nh = nx * hx + ny * hy + nz * hz
                val d1 = th + 0.10f * nh
                val d2 = th - 0.18f * nh
                val primary = Math.pow(sqrt((1f - d1 * d1).coerceAtLeast(0f)).toDouble(), 110.0).toFloat()
                val secondary = Math.pow(sqrt((1f - d2 * d2).coerceAtLeast(0f)).toDouble(), 12.0).toFloat()
                val facing = (nx * -0.22f + ny * 0.28f + nz * 0.93f).coerceIn(0f, 1f)   // no sheen where the surface turns away
                val pw = primary * facing; val sw = secondary * facing * 0.6f
                if (pw < 0.50f && sw < 0.50f) continue
                val li = base + 3 * sIdx; val ri = li + 2
                val li2 = base + 3 * (sIdx + 1); val ri2 = li2 + 2
                val ci2 = li2 + 1
                for (k in 0 until strands) {
                    val hash = ((l * 31 + k * 977 + sIdx) * -1640531535)
                    val u = -0.7f + 1.4f * (((hash ushr 8) and 0xFF) / 255f)
                    val x0 = xs[ci] + (xs[ri] - xs[li]) * 0.5f * u; val y0 = ys[ci] + (ys[ri] - ys[li]) * 0.5f * u
                    val x1 = xs[ci2] + (xs[ri2] - xs[li2]) * 0.5f * u; val y1 = ys[ci2] + (ys[ri2] - ys[li2]) * 0.5f * u
                    if (sw >= 0.50f) {
                        fibrePaint.strokeWidth = max(1f, strokePx * 1.5f)
                        fibrePaint.color = withAlpha(tint, 40f * sw.coerceAtMost(1f))
                        nc.drawLine(x0, y0, x1, y1, fibrePaint)
                    }
                    if (pw >= 0.50f) {
                        fibrePaint.strokeWidth = max(0.7f, strokePx * 0.6f)
                        fibrePaint.color = withAlpha(0xFF86653F.toInt(), 80f * pw.coerceAtMost(1f))
                        nc.drawLine(x0, y0, x1, y1, fibrePaint)
                    }
                }
            }
        }
    }

    private val circuits by lazy { CircuitTraces(mesh) }
    private val fibres3 by lazy { FiberHair(mesh) }
    private var cx = FloatArray(0); private var cy = FloatArray(0); private var cz = FloatArray(0)
    private val circuitLines = Array(6) { FloatArray(0) }
    private val circuitLineCounts = IntArray(6)

    /**
     * The circuit tracks of the hologram look: thin lines that follow the relief, round pads at their ends, and a pulse of light that
     * runs along each track. Only where the surface faces the viewer, fading towards the contour.
     */
    private fun drawCircuits(nc: Canvas, nrm: FloatArray, amp: Float, primary: Int, bg: Int, strokePx: Float, t: Float) {
        val c = circuits
        if (c.count == 0) return
        if (cx.size != c.count) {
            cx = FloatArray(c.count); cy = FloatArray(c.count); cz = FloatArray(c.count)
            for (b in 0 until 6) circuitLines[b] = FloatArray(c.segments.size * 2)
        }
        for (i in 0 until c.count) {
            val a = c.triA[i]; val b = c.triB[i]; val d = c.triC[i]
            val u = c.wu[i]; val q = c.wv[i]; val w = 1f - u - q
            cx[i] = w * xs[a] + u * xs[b] + q * xs[d]
            cy[i] = w * ys[a] + u * ys[b] + q * ys[d]
            cz[i] = w * nrm[3 * a + 2] + u * nrm[3 * b + 2] + q * nrm[3 * d + 2]
        }
        circuitLineCounts.fill(0)
        val gain = 0.8f + 0.4f * amp
        for (k in 0 until c.segments.size / 2) {
            val i = c.segments[2 * k]; val j = c.segments[2 * k + 1]
            val face = smooth01(0.10f, 0.45f, min(cz[i], cz[j]))
            if (face <= 0f) continue
            // the pulse: a bright spot that runs along its track, the tracks out of step with each other
            val phase = (t * 0.32f + c.segTrack[k] * 0.137f) % 1.25f
            val d = (c.segAlong[k] - phase) / 0.07f
            val a = (0.42f + 0.58f * kotlin.math.exp(-d * d)) * face * (c.fade[i] + c.fade[j]) * 0.5f * gain
            val level = if (a > 0.75f) 2 else if (a > 0.45f) 1 else 0
            val kind = if (blueMix) c.trackKind[c.segTrack[k]] else 0                  // on the blue skin, some tracks are deep blue
            val bk = kind * 3 + level
            val arr = circuitLines[bk]; val o = circuitLineCounts[bk]
            arr[o] = cx[i]; arr[o + 1] = cy[i]; arr[o + 2] = cx[j]; arr[o + 3] = cy[j]
            circuitLineCounts[bk] = o + 4
        }
        val gold = 0xFFFFB640.toInt()                        // the gold of the circuit in the logo
        val goldHot = mix(gold, 0xFFFFFFFF.toInt(), 0.6f)
        val blueHot = 0xFF8FC1FF.toInt()
        linePaint.strokeCap = Paint.Cap.ROUND
        // the gold ones glow: a wide, faint halo under them
        for (level in 0 until 3) {
            val bk = level
            if (circuitLineCounts[bk] == 0) continue
            linePaint.strokeWidth = max(3.4f, strokePx * (2.6f + 0.9f * level))
            linePaint.color = withAlpha(gold, floatArrayOf(34f, 52f, 78f)[level])
            nc.drawLines(circuitLines[bk], 0, circuitLineCounts[bk], linePaint)
        }
        for (bk in 0 until 6) {
            if (circuitLineCounts[bk] == 0) continue
            val kind = bk / 3; val level = bk % 3
            linePaint.strokeWidth = max(1.3f, strokePx * (0.75f + 0.3f * level))
            val base = if (kind == 1) DEEP_BLUE else gold
            val hot = if (kind == 1) blueHot else goldHot
            linePaint.color = withAlpha(if (level == 2) hot else base, floatArrayOf(190f, 235f, 255f)[level])
            nc.drawLines(circuitLines[bk], 0, circuitLineCounts[bk], linePaint)
        }
        // the pads: a ring, dark inside (deep blue rings on the deep blue tracks)
        for (kind in 0 until 2) {
            var m = 0
            for (idx in c.pads.indices) {
                val p = c.pads[idx]
                if ((if (blueMix) c.trackKind[idx / 2] else 0) != kind) continue
                if (smooth01(0.10f, 0.45f, cz[p]) <= 0f) continue
                if (m + 2 > padBuf.size) break
                padBuf[m++] = cx[p]; padBuf[m++] = cy[p]
            }
            if (m > 0) {
                linePaint.strokeWidth = max(3.6f, strokePx * 2.4f)
                linePaint.color = withAlpha(if (kind == 1) DEEP_BLUE else gold, 245f)
                nc.drawPoints(padBuf, 0, m, linePaint)
                linePaint.strokeWidth = max(1.6f, strokePx * 1.0f)
                linePaint.color = withAlpha(if (kind == 1) 0xFFBFDCFF.toInt() else bg, 255f)
                nc.drawPoints(padBuf, 0, m, linePaint)
            }
        }
        linePaint.strokeCap = Paint.Cap.BUTT
    }

    private val padBuf = FloatArray(12_000)

    private fun smooth01(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    // ── the cap ──────────────────────────────────────────────────────────────────────────────────────────────────────────
    /** 0 = no cap, 1..5 = black, blue, red, white, khaki, 6 = grey-green with an embroidered emblem. */
    var cap = 0

    private val capGeo by lazy { CapGeometry(mesh) }
    private var capX = FloatArray(0); private var capY = FloatArray(0); private var capP = FloatArray(0); private var capN = FloatArray(0)
    private var capCol = IntArray(0)
    private var capSmooth = FloatArray(0)
    private var capTriPos = FloatArray(0); private var capTriCol = IntArray(0)
    private val capPaint = Paint().apply { isAntiAlias = true; style = Paint.Style.FILL }
    private val capLine = Paint().apply { isAntiAlias = true; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val capPath = android.graphics.Path()
    private val capDash = android.graphics.DashPathEffect(floatArrayOf(5f, 4f), 0f)

    private fun capShade(base: Int, k: Float): Int {
        val kk = k.coerceIn(0f, 1.4f)
        return argb(255, (((base shr 16) and 0xFF) * kk).toInt().coerceIn(0, 255), (((base shr 8) and 0xFF) * kk).toInt().coerceIn(0, 255), ((base and 0xFF) * kk).toInt().coerceIn(0, 255))
    }

    /**
     * The cap, built from the posed head: the dome (a grid tied to the skin of the head, see CapGeometry), then its edge, the seams and the
     * button, and the visor. The dome is convex, so only what faces the viewer is drawn and no sorting is needed.
     */
    private fun drawCap(nc: Canvas, v: FloatArray, nrm: FloatArray, cx: Float, cy: Float, r: Float, strokePx: Float) {
        val g = capGeo
        val base = CAP_COLOURS[cap.coerceIn(1, CAP_COLOURS.lastIndex)]
        val n = g.top + 1
        if (capX.size != n) {
            capX = FloatArray(n); capY = FloatArray(n); capP = FloatArray(3 * n); capN = FloatArray(3 * n); capCol = IntArray(n)
            capTriPos = FloatArray(g.tris.size * 2); capTriCol = IntArray(g.tris.size)
        }
        for (q in 0 until n) {
            var px = 0f; var py = 0f; var pz = 0f; var nx = 0f; var ny = 0f; var nz = 0f
            for (k in 0..2) {
                val i = g.bind[3 * q + k]; val w = g.weight[3 * q + k]
                px += w * v[3 * i]; py += w * v[3 * i + 1]; pz += w * v[3 * i + 2]
                nx += w * nrm[3 * i]; ny += w * nrm[3 * i + 1]; nz += w * nrm[3 * i + 2]
            }
            val nl = max(kotlin.math.sqrt(nx * nx + ny * ny + nz * nz), 1e-6f)
            nx /= nl; ny /= nl; nz /= nl
            val d = g.stand[q]
            px += nx * d; py += ny * d; pz += nz * d
            capP[3 * q] = px; capP[3 * q + 1] = py; capP[3 * q + 2] = pz
            capN[3 * q] = nx; capN[3 * q + 1] = ny; capN[3 * q + 2] = nz
            val w = max(CAM_D - pz, 0.35f); val k = CAM_D / w * r
            capX[q] = cx + px * k; capY[q] = cy - py * k
            val lam = (nx * -0.55f + ny * 0.50f + nz * 0.52f).coerceIn(0f, 1f)
            capCol[q] = capShade(base, 0.30f + 0.90f * lam + 0.10f * (1f - nz.coerceIn(0f, 1f)))
        }
        // the two rings at the edge are smoothed round the head (the skin under them is not smooth: the edge would ripple)
        if (capSmooth.size != 6 * g.columns) capSmooth = FloatArray(6 * g.columns)
        for (ring in 0..1) {
            for (col in 0 until g.columns) {
                var sx = 0f; var sy = 0f; var sz = 0f
                for (d in -3..3) {
                    val wgt = (4 - abs(d)).toFloat()
                    val q = ring * g.columns + ((col + d) % g.columns + g.columns) % g.columns
                    sx += wgt * capP[3 * q]; sy += wgt * capP[3 * q + 1]; sz += wgt * capP[3 * q + 2]
                }
                val o = 3 * (ring * g.columns + col) - 0
                val i0 = 3 * (col + ring * 0)
                capSmooth[3 * col + (if (ring == 0) 0 else 3 * g.columns)] = sx / 16f
                capSmooth[3 * col + 1 + (if (ring == 0) 0 else 3 * g.columns)] = sy / 16f
                capSmooth[3 * col + 2 + (if (ring == 0) 0 else 3 * g.columns)] = sz / 16f
            }
        }
        for (ring in 0..1) for (col in 0 until g.columns) {
            val q = ring * g.columns + col
            val o = 3 * col + (if (ring == 0) 0 else 3 * g.columns)
            capP[3 * q] = capSmooth[o]; capP[3 * q + 1] = capSmooth[o + 1]; capP[3 * q + 2] = capSmooth[o + 2]
            val w = max(CAM_D - capP[3 * q + 2], 0.35f); val k = CAM_D / w * r
            capX[q] = cx + capP[3 * q] * k; capY[q] = cy - capP[3 * q + 1] * k
        }
        var tcount = 0; var p = 0; var c = 0
        for (t in 0 until g.tris.size / 3) {
            val a = g.tris[3 * t]; val b = g.tris[3 * t + 1]; val d = g.tris[3 * t + 2]
            if ((capN[3 * a + 2] + capN[3 * b + 2] + capN[3 * d + 2]) / 3f < -0.03f) continue
            capTriPos[p++] = capX[a]; capTriPos[p++] = capY[a]; capTriCol[c++] = capCol[a]
            capTriPos[p++] = capX[b]; capTriPos[p++] = capY[b]; capTriCol[c++] = capCol[b]
            capTriPos[p++] = capX[d]; capTriPos[p++] = capY[d]; capTriCol[c++] = capCol[d]
            tcount++
        }
        if (tcount > 0) fillTriangles(nc, capTriPos, capTriCol, tcount, capPaint)

        // the seams: six lines up the dome, along its columns, to the button
        val cols = g.columns
        capLine.style = Paint.Style.STROKE
        capLine.strokeWidth = max(1f, strokePx * 0.7f)
        capLine.color = withAlpha(capShade(base, 0.42f), 170f)
        for (k in 0 until 6) {
            val col = k * cols / 6
            capPath.reset()
            var pen = false
            for (ring in 0 until g.rings) {
                val q = ring * cols + col
                if (capN[3 * q + 2] < 0.02f) { pen = false; continue }
                if (!pen) { capPath.moveTo(capX[q], capY[q]); pen = true } else capPath.lineTo(capX[q], capY[q])
            }
            if (pen && capN[3 * g.top + 2] > 0.02f) capPath.lineTo(capX[g.top], capY[g.top])
            nc.drawPath(capPath, capLine)
        }
        if (cap == 6) drawEmblem(nc, base, strokePx)

        // the band round the edge (the thickness of the cap, and its sweatband): a strip under the edge, darker, all the way round
        val bandDown = 0.05f
        val bx = FloatArray(cols); val by = FloatArray(cols)
        for (col in 0 until cols) {
            val px = capP[3 * col] + capN[3 * col] * 0.008f
            val py = capP[3 * col + 1] - bandDown + capN[3 * col + 1] * 0.008f
            val pz = capP[3 * col + 2] + capN[3 * col + 2] * 0.008f
            val w = max(CAM_D - pz, 0.35f); val k = CAM_D / w * r
            bx[col] = cx + px * k; by[col] = cy - py * k
        }
        for (col in 0 until cols) {
            val c2 = (col + 1) % cols
            if (capN[3 * col + 2] < -0.02f && capN[3 * c2 + 2] < -0.02f) continue
            val lam = (capN[3 * col] * -0.55f + capN[3 * col + 1] * 0.10f + capN[3 * col + 2] * 0.52f).coerceIn(0f, 1f)
            capPaint.color = capShade(base, 0.40f + 0.50f * lam)
            capPath.reset()
            capPath.moveTo(capX[col], capY[col]); capPath.lineTo(capX[c2], capY[c2]); capPath.lineTo(bx[c2], by[c2]); capPath.lineTo(bx[col], by[col]); capPath.close()
            nc.drawPath(capPath, capPaint)
        }
        // the edge of the cap and the lower edge of the band: two darker lines
        capLine.strokeWidth = max(1.2f, strokePx * 0.9f)
        capLine.color = capShade(base, 0.30f)
        for (pass in 0 until 2) {
            capPath.reset()
            var pen = false
            for (col in 0..cols) {
                val q = col % cols
                if (capN[3 * q + 2] < 0.0f) { pen = false; continue }
                val x = if (pass == 0) capX[q] else bx[q]; val y = if (pass == 0) capY[q] else by[q]
                if (!pen) { capPath.moveTo(x, y); pen = true } else capPath.lineTo(x, y)
            }
            nc.drawPath(capPath, capLine)
        }
        // the button, with a small highlight
        if (capN[3 * g.top + 2] > 0.05f) {
            capPaint.color = capShade(base, 0.55f)
            nc.drawCircle(capX[g.top], capY[g.top], max(2f, r * 0.030f), capPaint)
            capPaint.color = capShade(base, 1.10f)
            nc.drawCircle(capX[g.top] - r * 0.004f, capY[g.top] - r * 0.005f, max(1f, r * 0.012f), capPaint)
        }

        // the visor: from the front of the edge, forward and down, longest in the middle, curved (it droops more at its sides)
        val vs = g.visor
        val m = vs.size
        if (m >= 3) {
            val qx = FloatArray(m); val qy = FloatArray(m); val q3 = FloatArray(3 * m)      // the outer edge
            val tx = FloatArray(m); val ty = FloatArray(m)                                   // its lower edge (the thickness)
            val s1x = FloatArray(m); val s1y = FloatArray(m); val s2x = FloatArray(m); val s2y = FloatArray(m)   // two rows of stitching
            val shx = FloatArray(m); val shy = FloatArray(m)                                 // the shadow on the forehead
            fun project(x: Float, y: Float, z: Float, ox: FloatArray, oy: FloatArray, i: Int) {
                val w = max(CAM_D - z, 0.35f); val k = CAM_D / w * r
                ox[i] = cx + x * k; oy[i] = cy - y * k
            }
            for (i in 0 until m) {
                val col = vs[i]
                val a = (col + 0.5f) / cols * 2f * Math.PI.toFloat() - Math.PI.toFloat()
                val t = (abs(a) / CapGeometry.VISOR_SPAN).coerceIn(0f, 1f)
                val len = CapGeometry.VISOR * Math.pow(max(cos(t * (Math.PI.toFloat() / 2f)), 0f).toDouble(), 0.9).toFloat()
                var dx = capN[3 * col] * 0.5f + kotlin.math.sin(a) * 0.10f
                var dy = -(0.33f + 0.55f * t * t)
                var dz = max(capN[3 * col + 2], 0.35f)
                val dl = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
                dx /= dl; dy /= dl; dz /= dl
                val x0 = capP[3 * col]; val y0 = capP[3 * col + 1] - bandDown * 0.3f; val z0 = capP[3 * col + 2]
                val x = x0 + dx * len; val y = y0 + dy * len; val z = z0 + dz * len
                q3[3 * i] = x; q3[3 * i + 1] = y; q3[3 * i + 2] = z
                project(x, y, z, qx, qy, i)
                project(x, y - 0.020f, z, tx, ty, i)
                project(x0 + dx * len * 0.82f, y0 + dy * len * 0.82f, z0 + dz * len * 0.82f, s1x, s1y, i)
                project(x0 + dx * len * 0.90f, y0 + dy * len * 0.90f, z0 + dz * len * 0.90f, s2x, s2y, i)
                // the shadow it throws on the forehead: below the base of the visor, as long as the visor is
                project(x0, y0 - 0.02f - 0.21f * (len / CapGeometry.VISOR), z0, shx, shy, i)
            }
            // the shadow first: a soft dark band under the visor (over the face)
            capPaint.color = withAlpha(0xFF000000.toInt(), if (cap == 6) 105f else 70f)
            for (i in 0 until m - 1) {
                val ja = vs[i]; val jb = vs[i + 1]
                capPath.reset()
                capPath.moveTo(bx[ja], by[ja]); capPath.lineTo(bx[jb], by[jb]); capPath.lineTo(shx[i + 1], shy[i + 1]); capPath.lineTo(shx[i], shy[i]); capPath.close()
                nc.drawPath(capPath, capPaint)
            }
            // the top of the visor
            for (i in 0 until m - 1) {
                val ja = vs[i]; val jb = vs[i + 1]
                val ax = capP[3 * jb] - capP[3 * ja]; val ay = capP[3 * jb + 1] - capP[3 * ja + 1]; val az = capP[3 * jb + 2] - capP[3 * ja + 2]
                val bx2 = q3[3 * i] - capP[3 * ja]; val by2 = q3[3 * i + 1] - capP[3 * ja + 1]; val bz2 = q3[3 * i + 2] - capP[3 * ja + 2]
                var nx = ay * bz2 - az * by2; var ny = az * bx2 - ax * bz2; var nz = ax * by2 - ay * bx2
                val nl = max(kotlin.math.sqrt(nx * nx + ny * ny + nz * nz), 1e-6f)
                nx /= nl; ny /= nl; nz /= nl
                if (ny < 0f) { nx = -nx; ny = -ny; nz = -nz }
                val lam = (nx * -0.55f + ny * 0.50f + nz * 0.52f).coerceIn(0f, 1f)
                // a little lighter towards the outer edge, as the visor is lit from above
                capPaint.color = capShade(base, 0.60f + 0.75f * lam)
                capPath.reset()
                capPath.moveTo(capX[ja], capY[ja]); capPath.lineTo(capX[jb], capY[jb]); capPath.lineTo(qx[i + 1], qy[i + 1]); capPath.lineTo(qx[i], qy[i]); capPath.close()
                nc.drawPath(capPath, capPaint)
            }
            // the thickness: a strip under the outer edge
            capPaint.color = capShade(base, 0.32f)
            for (i in 0 until m - 1) {
                capPath.reset()
                capPath.moveTo(qx[i], qy[i]); capPath.lineTo(qx[i + 1], qy[i + 1]); capPath.lineTo(tx[i + 1], ty[i + 1]); capPath.lineTo(tx[i], ty[i]); capPath.close()
                nc.drawPath(capPath, capPaint)
            }
            // the stitching: two dashed rows near the outer edge
            capLine.strokeWidth = max(0.8f, strokePx * 0.55f)
            capLine.color = withAlpha(capShade(base, 0.42f), 200f)
            capLine.pathEffect = capDash
            for (row in 0 until 2) {
                val xs2 = if (row == 0) s1x else s2x; val ys2 = if (row == 0) s1y else s2y
                capPath.reset()
                capPath.moveTo(xs2[0], ys2[0])
                for (i in 1 until m) capPath.lineTo(xs2[i], ys2[i])
                nc.drawPath(capPath, capLine)
            }
            capLine.pathEffect = null
            // the outer edge
            capLine.strokeWidth = max(1.2f, strokePx * 0.9f)
            capLine.color = capShade(base, 0.28f)
            capPath.reset()
            capPath.moveTo(qx[0], qy[0])
            for (i in 1 until m) capPath.lineTo(qx[i], qy[i])
            nc.drawPath(capPath, capLine)
        }
    }

    /** A point of the dome at a fractional ring and column (the columns wrap round), in pixels. */
    private fun domeAt(ring: Float, col: Float, out: FloatArray): Boolean {
        val g = capGeo
        val cols = g.columns
        val r0 = ring.toInt().coerceIn(0, g.rings - 2); val fr = (ring - r0).coerceIn(0f, 1f)
        var c0 = Math.floor(col.toDouble()).toInt(); val fc = col - c0
        c0 = ((c0 % cols) + cols) % cols
        val c1 = (c0 + 1) % cols
        fun q(r: Int, c: Int) = r * cols + c
        val a = q(r0, c0); val b = q(r0, c1); val c = q(r0 + 1, c0); val d = q(r0 + 1, c1)
        out[0] = (capX[a] * (1 - fc) + capX[b] * fc) * (1 - fr) + (capX[c] * (1 - fc) + capX[d] * fc) * fr
        out[1] = (capY[a] * (1 - fc) + capY[b] * fc) * (1 - fr) + (capY[c] * (1 - fc) + capY[d] * fc) * fr
        val nz = (capN[3 * a + 2] + capN[3 * b + 2] + capN[3 * c + 2] + capN[3 * d + 2]) / 4f
        return nz > 0.15f
    }

    /**
     * The emblem embroidered on the front panel: an angular W with a bar over it and a small chevron, in stitching a little lighter than the
     * cloth. Its points are given on the dome by (ring, column), so it bends with the dome and turns with the head.
     */
    private fun drawEmblem(nc: Canvas, base: Int, strokePx: Float) {
        val front = capGeo.columns / 2f - 0.5f          // the middle of the front
        val strokes = arrayOf(
            floatArrayOf(-1f, 0.9f, -0.5f, -0.85f, 0f, 0.45f, 0.5f, -0.85f, 1f, 0.9f),          // the W
            floatArrayOf(-0.72f, 0.9f, -0.42f, -0.30f, 0f, 0.62f, 0.42f, -0.30f, 0.72f, 0.9f),  // and its inner line
            floatArrayOf(-0.20f, 0.90f, 0f, 0.20f, 0.20f, 0.90f),                                // the chevron
        )
        val p = FloatArray(2)
        val ringCentre = 6.3f; val ringSpan = 3.0f; val colSpan = 5.4f
        capLine.style = Paint.Style.STROKE
        capLine.strokeWidth = max(1.3f, strokePx * 1.05f)
        capLine.color = withAlpha(capShade(base, 1.55f), 215f)
        for (st in strokes) {
            capPath.reset()
            var pen = false
            var k = 0
            while (k < st.size) {
                val ok = domeAt(ringCentre + st[k + 1] * ringSpan, front + st[k] * colSpan, p)
                if (!ok) { pen = false } else if (!pen) { capPath.moveTo(p[0], p[1]); pen = true } else capPath.lineTo(p[0], p[1])
                k += 2
            }
            nc.drawPath(capPath, capLine)
        }
    }

    private fun fillTriangles(nc: Canvas, pos: FloatArray, col: IntArray, count: Int, paint: Paint) {
        if (Build.VERSION.SDK_INT >= 29) {
            nc.drawVertices(Canvas.VertexMode.TRIANGLES, count * 6, pos, 0, null, 0, col, 0, null, 0, 0, paint)
        } else {
            var q = 0
            for (k in 0 until count) {
                trianglePath.reset()
                trianglePath.moveTo(pos[q], pos[q + 1]); trianglePath.lineTo(pos[q + 2], pos[q + 3]); trianglePath.lineTo(pos[q + 4], pos[q + 5]); trianglePath.close()
                paint.color = col[3 * k]
                nc.drawPath(trianglePath, paint)
                q += 6
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
        val lash = if (skin > 0 && !holo) 0xFF1E120E.toInt() else primary
        val fold = if (skin > 0 && !holo) 0xFF6B4636.toInt() else primary
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
        val hairColour = browColour

        // brows (under a cap they are behind the visor, so they are not drawn)
        for (key in listOf("brow_l", "brow_r")) {
            if (cap > 0) continue
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
        val lipLine = if (skin > 0 && !holo) 0xFF6E2A38.toInt() else primary
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