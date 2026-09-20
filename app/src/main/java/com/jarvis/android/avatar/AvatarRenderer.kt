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

// Key light high on the left, and the half vector towards a viewer straight ahead (for the hair's sheen).
private const val LX = -0.55f
private const val LY = 0.50f
private const val LZ = 0.52f
private const val HX = -0.30f
private const val HY = 0.27f
private const val HZ = 0.92f

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

private fun withAlpha(col: Int, a: Float): Int = (col and 0x00FFFFFF) or (a.coerceIn(0f, 255f).toInt() shl 24)

private fun mixColors(base: Int, other: Int, f: Float): Int = blend(base, other, f * 255f)

/**
 * Draws the head. The surface is shaded smoothly (a colour per vertex, from normals recomputed on the posed geometry so the
 * lips and jaw relight as they move), sorted far to near; then structure lines, the hair strands, the eyes, brows and
 * mouth drawn from the real landmark rings. Ported from Mark-LIV's renderer (CC BY-NC 4.0, see assets/avatar/NOTICE.txt) to
 * Android's canvas, with additions: smooth shading over a subdivided mesh, hair, a rim light in the accent colour, faint
 * scan lines, and lids that really close when the assistant sleeps.
 */
internal class AvatarRenderer(private val mesh: HeadMesh) {
    /** Set from the settings: when false the hair shell and strands are skipped. */
    @Volatile var showHair = true

    /** The chosen look (realistic or holographic, skin, hair and eye colours). */
    @Volatile var look = AvatarLook()

    private val realistic = RealisticPainter(mesh)
    private val cyber = CyberPainter(mesh)
    private val network = NetworkPainter(mesh)

    private val nV = mesh.vertexCount
    private val nF = mesh.faceCount
    private val nSurface = mesh.strandBase // vertices that belong to a surface (the strand points do not)
    private val xs = FloatArray(nV)
    private val ys = FloatArray(nV)
    private val vnx = FloatArray(nV)
    private val vny = FloatArray(nV)
    private val vnz = FloatArray(nV)
    private val vertexColor = IntArray(nV)
    private val keys = LongArray(nF)
    private val triPos = FloatArray(nF * 6)
    private val triCol = IntArray(nF * 3)
    private val faceFront = BooleanArray(nF)
    private val structure = StructureEdges.build(mesh)
    private val edgeBuckets = Array(BUCKETS) { FloatArray(structure.count * 4) }
    private val edgeCounts = IntArray(BUCKETS)
    private val strandSegments = mesh.strandCount * (mesh.strandLength - 1)
    private val strandBuckets = Array(2) { FloatArray(strandSegments * 4) }
    private val strandCounts = IntArray(2)
    private val lut = IntArray(LUT_N)
    private val hairLut = IntArray(LUT_N)
    private var lutKey = 0L
    private val surfacePaint = Paint().apply { isAntiAlias = false; style = Paint.Style.FILL }
    private val trianglePath = android.graphics.Path()
    private val linePaint = Paint().apply { isAntiAlias = true; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val lipUp = mesh.landmarks.getValue("lips_in").let { ring -> ring.copyOfRange(10, ring.size) + ring[0] }

    /** Height of the energy sweep, set by the caller each frame. */
    var scanY: Float = 0f

    /** Draws the head centred on ([cx], [cy]); [r] is its half-height in pixels. Colours are ARGB ints. */
    fun draw(scope: DrawScope, avatar: HoloAvatar, cx: Float, cy: Float, r: Float, primary: Int, accent: Int, bg: Int, strokePx: Float) {
        avatar.pose()
        val amp = avatar.glow
        val v = avatar.pv
        val n = avatar.pn
        val look = look
        val real = look.style == AvatarStyle.REALISTIC
        val cyb = look.style == AvatarStyle.CYBER
        val net = look.style == AvatarStyle.NETWORK
        realistic.prepare(look, bg)
        if (cyb) cyber.drawBackdrop(scope, scope.size.minDimension, avatar.time, amp)

        // aura: a soft halo behind the head (fainter for the realistic look)
        val ar = r * 1.95f
        val haze = if (real) 0.4f else if (cyb) 0.7f else if (net) 0.5f else 1f
        scope.drawCircle(
            brush = Brush.radialGradient(
                0f to Color(withAlpha(primary, (34f + 66f * amp) * haze)),
                0.38f to Color(withAlpha(primary, (20f + 40f * amp) * haze)),
                1f to Color(withAlpha(primary, 0f)),
                center = Offset(cx, cy), radius = ar,
            ),
            radius = ar, center = Offset(cx, cy),
        )

        // project
        for (i in 0 until nV) {
            val w = max(CAM_D - v[3 * i + 2], 0.35f)
            val k = CAM_D / w * r
            xs[i] = cx + v[3 * i] * k
            ys[i] = cy - v[3 * i + 1] * k
        }

        buildLuts(bg, primary, accent)
        val visible = shadeSurface(v, n, amp, accent)
        scope.drawIntoCanvas { canvas ->
            val nc = canvas.nativeCanvas
            drawSurface(nc, visible)
            if (net) {
                network.draw(nc, structure, faceFront, xs, ys, v, n, avatar.time, amp, strokePx)
            } else if (cyb) {
                cyber.drawCircuits(nc, scope, xs, ys, n, avatar.time, amp, strokePx)
            } else if (real) {
                if (showHair) realistic.drawStrands(nc, xs, ys, n, strokePx)
            } else {
                drawWire(nc, v, amp, primary, bg, strokePx)
                if (showHair) drawStrands(nc, n, amp, accent, bg, strokePx)
            }
        }
        if (net) {
            network.drawFeatures(scope, avatar, xs, ys, r, amp, strokePx)
        } else if (cyb) {
            cyber.drawNeckAndCollar(scope, cx, cy, r, strokePx, avatar.time)
            cyber.drawFeatures(scope, avatar, xs, ys, r, amp, strokePx)
        } else if (real) {
            realistic.drawShirt(scope, cx, cy, r)
            realistic.drawFeatures(scope, avatar, xs, ys, r, look, amp, strokePx)
        } else {
            drawScanLines(scope, cx, cy, r, primary)
            drawFeatures(scope, avatar, r, primary, accent, bg, amp, strokePx)
        }
    }

    private fun buildLuts(bg: Int, primary: Int, accent: Int) {
        val key = (bg.toLong() shl 40) xor (primary.toLong() shl 16) xor accent.toLong()
        if (key == lutKey) return
        lutKey = key
        val hairColor = mixColors(primary, accent, 0.30f)
        for (i in 0 until LUT_N) {
            lut[i] = blend(bg, primary, 255f * (i + 0.5f) / LUT_N)
            hairLut[i] = blend(bg, hairColor, 255f * (i + 0.5f) / LUT_N)
        }
    }

    /**
     * Shades every vertex and returns how many triangles face the camera; their depth keys are left in [keys].
     * Vertex normals are rebuilt each frame from the posed triangles (area weighted), so the mouth and jaw relight as they move.
     */
    private fun shadeSurface(v: FloatArray, nrm: FloatArray, amp: Float, accent: Int): Int {
        val f = mesh.faces
        vnx.fill(0f, 0, nSurface); vny.fill(0f, 0, nSurface); vnz.fill(0f, 0, nSurface)
        val hairOn = (showHair && look.style != AvatarStyle.NETWORK) || look.style == AvatarStyle.CYBER // in the cyber style the shell is the cranial glass: always there
        var count = 0
        for (t in 0 until nF) {
            val hair = mesh.isHairFace(t)
            if (hair && !hairOn) { faceFront[t] = false; continue }
            val a = f[3 * t]; val b = f[3 * t + 1]; val c = f[3 * t + 2]
            val abx = v[3 * b] - v[3 * a]; val aby = v[3 * b + 1] - v[3 * a + 1]; val abz = v[3 * b + 2] - v[3 * a + 2]
            val acx = v[3 * c] - v[3 * a]; val acy = v[3 * c + 1] - v[3 * a + 1]; val acz = v[3 * c + 2] - v[3 * a + 2]
            var nx = aby * acz - abz * acy
            var ny = abz * acx - abx * acz
            var nz = abx * acy - aby * acx
            // Point them outwards by agreeing with the vertex normals (flipping on the sign of nz alone would scramble the light).
            val rx = nrm[3 * a] + nrm[3 * b] + nrm[3 * c]
            val ry = nrm[3 * a + 1] + nrm[3 * b + 1] + nrm[3 * c + 1]
            val rz = nrm[3 * a + 2] + nrm[3 * b + 2] + nrm[3 * c + 2]
            if (nx * rx + ny * ry + nz * rz < 0f) { nx = -nx; ny = -ny; nz = -nz }
            // The cross product's length carries the area: adding it unnormalised weights the vertex normal by area.
            vnx[a] += nx; vny[a] += ny; vnz[a] += nz
            vnx[b] += nx; vny[b] += ny; vnz[b] += nz
            vnx[c] += nx; vny[c] += ny; vnz[c] += nz
            val len = max(sqrt(nx * nx + ny * ny + nz * nz), 1e-9f)
            val facing = nz / len > 0.01f
            faceFront[t] = facing
            if (!facing) continue
            val area = abs((xs[b] - xs[a]) * (ys[c] - ys[a]) - (xs[c] - xs[a]) * (ys[b] - ys[a]))
            if (area <= 0.25f) continue
            val z = (v[3 * a + 2] + v[3 * b + 2] + v[3 * c + 2]) / 3f + when {
                mesh.faceGroup[t] < 0.5f -> -1000f // the neck is drawn first: it interpenetrates the head and would tear the seam
                hair -> 0.03f                      // the hair sits just outside the skull: a small bias keeps it on top
                else -> 0f
            }
            val bits = java.lang.Float.floatToIntBits(z)
            val mapped = if (bits >= 0) bits else bits xor 0x7fffffff
            keys[count++] = (mapped.toLong() shl 32) or t.toLong()
        }

        for (i in 0 until nSurface) {
            val len = max(sqrt(vnx[i] * vnx[i] + vny[i] * vny[i] + vnz[i] * vnz[i]), 1e-9f)
            val nx = vnx[i] / len; val ny = vny[i] / len; val nz = vnz[i] / len
            val fres = Math.pow((1f - nz).coerceIn(0f, 2f).toDouble(), 1.7).toFloat()
            val lam = (nx * LX + ny * LY + nz * LZ).coerceIn(0f, 1f)
            if (look.style == AvatarStyle.NETWORK) {
                vertexColor[i] = network.surfaceVertex(nz, mesh.fade[i])
            } else if (look.style == AvatarStyle.CYBER) {
                vertexColor[i] = if (mesh.isHairVertex(i)) cyber.shellVertex(i, nx, ny, nz, amp) else cyber.skinVertex(i, nx, ny, nz, amp)
            } else if (look.style == AvatarStyle.REALISTIC) {
                vertexColor[i] = if (mesh.isHairVertex(i)) realistic.hairVertex(i, nx, ny, nz, amp) else realistic.skinVertex(i, nx, ny, nz, amp)
            } else if (mesh.isHairVertex(i)) {
                // Hair: a darker body with a soft sheen band where the light glances off it.
                val sheen = Math.pow((nx * HX + ny * HY + nz * HZ).coerceIn(0f, 1f).toDouble(), 14.0).toFloat()
                val bright = (0.20f + 0.42f * lam + 0.14f * fres) * mesh.fade[i] * (0.90f + 0.20f * amp)
                var col = hairLut[(bright * LUT_N).toInt().coerceIn(0, LUT_N - 1)]
                if (sheen > 0.02f) col = mixColors(col, 0xFFFFFF, (0.42f * sheen).coerceAtMost(0.5f))
                vertexColor[i] = col
            } else {
                var bright = 0.26f + 0.20f * fres + 0.66f * Math.pow(lam.toDouble(), 1.05).toFloat()
                bright *= mesh.fade[i]
                bright *= 0.88f + 0.24f * amp
                var col = lut[(bright * LUT_N).toInt().coerceIn(0, LUT_N - 1)]
                // Rim light: surface turning away from the viewer catches the accent colour.
                val rim = (fres * fres * 0.30f).coerceIn(0f, 0.30f)
                if (rim > 0.01f) col = mixColors(col, accent, rim)
                vertexColor[i] = col
            }
        }
        java.util.Arrays.sort(keys, 0, count)
        return count
    }

    private fun drawSurface(nc: Canvas, count: Int) {
        if (count == 0) return
        val f = mesh.faces
        var p = 0
        var c = 0
        for (k in 0 until count) {
            val t = (keys[k] and 0x7fffffffL).toInt()
            for (corner in 0..2) {
                val vi = f[3 * t + corner]
                triPos[p++] = xs[vi]
                triPos[p++] = ys[vi]
                triCol[c++] = vertexColor[vi]
            }
        }
        if (Build.VERSION.SDK_INT >= 29) {
            nc.drawVertices(Canvas.VertexMode.TRIANGLES, count * 6, triPos, 0, null, 0, triCol, 0, null, 0, 0, surfacePaint)
        } else {
            // Older phones: one filled path per triangle, in the average colour of its corners (slower, close to the same picture).
            var q = 0
            for (k in 0 until count) {
                trianglePath.reset()
                trianglePath.moveTo(triPos[q], triPos[q + 1])
                trianglePath.lineTo(triPos[q + 2], triPos[q + 3])
                trianglePath.lineTo(triPos[q + 4], triPos[q + 5])
                trianglePath.close()
                surfacePaint.color = mixColors(triCol[3 * k], triCol[3 * k + 1], 0.5f)
                nc.drawPath(trianglePath, surfacePaint)
                q += 6
            }
        }
    }

    private fun drawWire(nc: Canvas, v: FloatArray, amp: Float, primary: Int, bg: Int, strokePx: Float) {
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
            // The strip between the face mask and the skull is nearly edge-on: its facing flips from triangle to triangle and would draw noise.
            if (silhouette && (mesh.isSweepFace(f0) || (f1 >= 0 && mesh.isSweepFace(f1)))) continue
            val hair = mesh.isHairFace(f0)
            if (hair && !(showHair || look.style == AvatarStyle.CYBER)) continue
            // The top of the neck tube is inside the head: its lines would show through the jaw as a box.
            if (mesh.faceGroup[f0] < 0.5f && v[3 * i0 + 1] > -1.02f && v[3 * i1 + 1] > -1.02f) continue
            val fadeE = 0.5f * (mesh.fade[i0] + mesh.fade[i1])
            var alpha = when {
                silhouette -> if (hair) 0.42f else 0.62f
                st.crease[k] > 0f -> 0.14f + 0.40f * st.crease[k]
                else -> 0f
            }
            // The scanner: the whole lattice lights up for a moment as the sweep passes.
            if (mesh.faceGroup[f0] in 0.9f..1.1f) { // the scanner lights the regular face mask only, not the thin strip round it
                val ym = 0.5f * (v[3 * i0 + 1] + v[3 * i1 + 1])
                val d = (ym - scan) / 0.13f
                alpha += 0.26f * exp(-d * d) * (if (st.crease[k] > 0f || silhouette) 1f else 0.16f)
            }
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

    /** Long flowing lines over the hair shell, from the crown towards the hairline. They only show on the side facing the viewer. */
    private fun drawStrands(nc: Canvas, nrm: FloatArray, amp: Float, accent: Int, bg: Int, strokePx: Float) {
        strandCounts.fill(0)
        val len = mesh.strandLength
        for (s in 0 until mesh.strandCount) {
            val base = mesh.strandBase + s * len
            for (k in 0 until len - 1) {
                val i0 = base + k; val i1 = i0 + 1
                val facing = min(nrm[3 * i0 + 2], nrm[3 * i1 + 2])
                if (facing < 0.08f) continue
                val alpha = ((0.30f + 0.35f * facing) * (0.85f + 0.3f * amp)).coerceIn(0f, 1f)
                val bucket = if (alpha > 0.5f) 1 else 0
                val arr = strandBuckets[bucket]
                val o = strandCounts[bucket]
                arr[o] = xs[i0]; arr[o + 1] = ys[i0]; arr[o + 2] = xs[i1]; arr[o + 3] = ys[i1]
                strandCounts[bucket] = o + 4
            }
        }
        linePaint.strokeWidth = strokePx * 0.85f
        val light = mixColors(accent, 0xFFFFFF, 0.35f)
        for (b in 0..1) {
            if (strandCounts[b] == 0) continue
            linePaint.color = blend(bg, light, if (b == 1) 150f else 82f)
            nc.drawLines(strandBuckets[b], 0, strandCounts[b], linePaint)
        }
    }

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
        val vis = ((1f - avatar.blink) * avatar.lids.coerceIn(0f, 1f)).coerceIn(0.04f, 1f)

        // eyes
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
            scope.drawPath(path, Color(blend(bg, primary, 22f)))
            scope.drawPath(path, Color(withAlpha(primary, 210f * face)), style = Stroke(width = strokePx * 1.1f))
            if (vis > 0.35f) {
                val w = maxX - minX
                val h = maxY - minY
                val gx = (minX + maxX) / 2f + avatar.gaze[0] * w * 0.16f
                val gy = (minY + maxY) / 2f + avatar.gaze[1] * h * 0.20f
                val rad = min(h * 0.62f, w * 0.20f)
                scope.drawOval(Color(withAlpha(accent, (70f + 60f * amp) * face * vis)), Offset(gx - rad, gy - rad * vis), Size(rad * 2f, rad * 2f * vis))
                val pr = rad * 0.42f
                scope.drawOval(Color(withAlpha(accent, 245f * face * vis)), Offset(gx - pr, gy - pr * vis), Size(pr * 2f, pr * 2f * vis))
            }
        }

        // brows
        for (key in listOf("brow_l", "brow_r")) {
            scope.drawPath(ring(lm.getValue(key)), Color(withAlpha(primary, 150f * face)), style = Stroke(width = strokePx * 1.4f))
        }

        // mouth
        val inner = lm.getValue("lips_in")
        val innerPath = closedRing(inner)
        var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (i in inner) { minY = min(minY, ys[i]); maxY = max(maxY, ys[i]) }
        val openH = maxY - minY
        val mouth = avatar.mouth
        if (mouth > 0.02f) {
            // The cavity is dark but never pure black, and tinted by the theme so it stays part of the hologram.
            scope.drawPath(innerPath, Color(blend(bg, primary, 16f + 26f * mouth)))
            // Upper teeth: the cheapest thing that makes an open mouth read as speech.
            val teeth = Path()
            for ((k, i) in lipUp.withIndex()) if (k == 0) teeth.moveTo(xs[i], ys[i]) else teeth.lineTo(xs[i], ys[i])
            for (i in lipUp.reversed()) teeth.lineTo(xs[i], ys[i] + openH * 0.30f)
            teeth.close()
            scope.drawPath(teeth, Color(blend(bg, primary, 150f + 60f * mouth)))
            scope.drawPath(innerPath, Color(withAlpha(accent, 40f * mouth * face)))
        }
        scope.drawPath(innerPath, Color(withAlpha(primary, (150f + 70f * mouth) * face)), style = Stroke(width = strokePx * 1.3f))
        scope.drawPath(closedRing(lm.getValue("lips_out")), Color(withAlpha(primary, 110f * face)), style = Stroke(width = strokePx * 1.1f))
    }
}

/**
 * The lines that carry the form: every edge of the mesh with the two triangles that share it, and how sharply the surface
 * folds there (0 = flat). Creases outline the eyes, nose, lips and jaw; the silhouette is found each frame from which of the
 * two triangles faces the camera. Mark-LIV drew an arbitrary third of all edges instead, which read as scribbles.
 * Hair edges get no crease (the strands carry the hair), only a silhouette.
 */
internal class StructureEdges private constructor(
    val a: IntArray, val b: IntArray, val face0: IntArray, val face1: IntArray, val crease: FloatArray,
) {
    val count: Int get() = a.size

    companion object {
        private const val CREASE_MIN_COS = 0.965f // on the smoothed mesh, folds sharper than about 15 degrees count as structure

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
                val key = lo.toLong() * 1_000_000L + hi
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
                crease[e] = when {
                    mesh.isHairFace(t0) -> 0f
                    mesh.isSweepFace(t0) || (t1 >= 0 && mesh.isSweepFace(t1)) -> 0f // the cranium sweep has no anatomy to outline
                    t1 < 0 -> 1f
                    else -> {
                        val dot = fn[3 * t0] * fn[3 * t1] + fn[3 * t0 + 1] * fn[3 * t1 + 1] + fn[3 * t0 + 2] * fn[3 * t1 + 2]
                        if (dot < CREASE_MIN_COS) ((CREASE_MIN_COS - dot) / 0.4f).coerceIn(0.05f, 1f) else 0f
                    }
                }
            }
            return StructureEdges(ea.toIntArray(), eb.toIntArray(), e0.toIntArray(), e1.toIntArray(), crease)
        }
    }
}
