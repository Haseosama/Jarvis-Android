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
import kotlin.math.sqrt

/**
 * The realistic look: skin lit with a key light, a fill light, a soft red glow where light scatters through the skin,
 * a sheen and the baked ambient occlusion; hair as a dark body with hundreds of individual strands in three tones; real
 * eyes (sclera, iris, pupil, catchlight, lid shadow), brows, coloured lips, teeth and tongue; and a shirt so the neck ends
 * somewhere. Everything is drawn from the same mesh and landmark rings as the holographic style.
 */
internal class RealisticPainter(private val mesh: HeadMesh) {
    // key light high on the left, a cool fill from the right, and half vectors for the two sheens
    private val kx = -0.45f; private val ky = 0.55f; private val kz = 0.70f
    private val fx = 0.62f; private val fy = 0.05f; private val fz = 0.60f
    private val hx = -0.22f; private val hy = 0.28f; private val hz = 0.93f

    private var sr = 0f; private var sg = 0f; private var sb = 0f
    private var hr = 0f; private var hg = 0f; private var hb = 0f
    private var br = 0f; private var bgc = 0f; private var bb = 0f

    private val strandTone = IntArray(mesh.strandCount) { s ->
        val h = ((s * 2654435761L) ushr 7).toInt() and 0xFF
        when {
            h < 140 -> 1 // base
            h < 205 -> 0 // dark
            else -> 2    // light
        }
    }
    private val strandBuckets = Array(6) { FloatArray(mesh.strandCount * (mesh.strandLength - 1) * 4) }
    private val strandCounts = IntArray(6)
    private val fringeLines = FloatArray(max(1, mesh.fringeCount) * (mesh.strandLength - 1) * 4)
    private var fringeCount = 0
    private val strandPaint = Paint().apply { isAntiAlias = true; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }

    /** Reads the palette for this frame. Colours are converted once here, not per vertex. */
    fun prepare(look: AvatarLook, bg: Int) {
        sr = ((look.skinColor shr 16) and 0xFF) / 255f; sg = ((look.skinColor shr 8) and 0xFF) / 255f; sb = (look.skinColor and 0xFF) / 255f
        hr = ((look.hairColor shr 16) and 0xFF) / 255f; hg = ((look.hairColor shr 8) and 0xFF) / 255f; hb = (look.hairColor and 0xFF) / 255f
        br = ((bg shr 16) and 0xFF) / 255f; bgc = ((bg shr 8) and 0xFF) / 255f; bb = (bg and 0xFF) / 255f
    }

    private fun pack(r: Float, g: Float, b: Float): Int =
        (0xFF shl 24) or ((r.coerceIn(0f, 1f) * 255f).toInt() shl 16) or ((g.coerceIn(0f, 1f) * 255f).toInt() shl 8) or (b.coerceIn(0f, 1f) * 255f).toInt()

    private fun pow(x: Float, e: Float) = Math.pow(x.toDouble(), e.toDouble()).toFloat()

    /** Colour of skin vertex [i] with unit normal (nx, ny, nz). */
    fun skinVertex(i: Int, nx: Float, ny: Float, nz: Float, amp: Float): Int {
        val ao = mesh.ao[i]
        val diff = ((nx * kx + ny * ky + nz * kz + 0.30f) / 1.30f).coerceIn(0f, 1f)
        val fill = (nx * fx + ny * fy + nz * fz).coerceIn(0f, 1f) * 0.20f
        val light = (0.34f + 0.82f * diff + fill + 0.04f * amp) * ao
        val shade = 1f - diff
        val sss = shade * shade * 0.18f * (0.4f + 0.6f * ao) // light scattering through skin reddens the terminator
        val spec = pow((nx * hx + ny * hy + nz * hz).coerceIn(0f, 1f), 34f) * 0.13f * ao
        val rim = pow((1f - nz).coerceIn(0f, 1f), 3f) * 0.06f
        var r = sr * light + sr * sss * 0.65f + spec + rim * 0.35f
        var g = sg * light + sg * sss * 0.16f + spec * 0.97f + rim * 0.50f
        var b = sb * light + sb * sss * 0.10f + spec * 0.93f + rim * 0.70f
        val f = mesh.fade[i]
        if (f < 1f) { // the neck fades into the background
            r = br + (r - br) * f; g = bgc + (g - bgc) * f; b = bb + (b - bb) * f
        }
        return pack(r, g, b)
    }

    /** Colour of hair-shell vertex [i]: a dark body, darker at the roots, with a sheen where the light glances off. */
    fun hairVertex(i: Int, nx: Float, ny: Float, nz: Float, amp: Float): Int {
        val diff = ((nx * kx + ny * ky + nz * kz + 0.15f) / 1.15f).coerceIn(0f, 1f)
        val root = mesh.fade[i]
        val body = (0.22f + 0.66f * diff + 0.03f * amp) * (0.55f + 0.45f * root)
        val sheen = pow((nx * hx + ny * hy + nz * hz).coerceIn(0f, 1f), 16f) * 0.30f
        val r = hr * body + (hr * 1.4f + 0.10f) * sheen
        val g = hg * body + (hg * 1.4f + 0.09f) * sheen
        val b = hb * body + (hb * 1.4f + 0.07f) * sheen
        return pack(r, g, b)
    }

    /** Individual strands over the shell, in a dark, a base and a light tone, dimmer in shadow. */
    fun drawStrands(nc: Canvas, xs: FloatArray, ys: FloatArray, nrm: FloatArray, strokePx: Float) {
        strandCounts.fill(0)
        fringeCount = 0
        val len = mesh.strandLength
        val regular = mesh.strandCount - mesh.fringeCount
        for (s in 0 until mesh.strandCount) {
            val base = mesh.strandBase + s * len
            val tone = strandTone[s]
            val fringe = s >= regular
            for (k in 0 until len - 1) {
                val i0 = base + k; val i1 = i0 + 1
                if (fringe) {
                    if (xs[i0] == xs[i1] && ys[i0] == ys[i1]) continue
                    if (nrm[3 * i0 + 2] < 0.05f) continue
                    fringeLines[fringeCount] = xs[i0]; fringeLines[fringeCount + 1] = ys[i0]
                    fringeLines[fringeCount + 2] = xs[i1]; fringeLines[fringeCount + 3] = ys[i1]
                    fringeCount += 4
                    continue
                }
                val nz = min(nrm[3 * i0 + 2], nrm[3 * i1 + 2])
                if (nz < 0.02f) continue
                val lam = (nrm[3 * i0] * kx + nrm[3 * i0 + 1] * ky + nrm[3 * i0 + 2] * kz + 0.2f).coerceIn(0f, 1f)
                val bucket = tone * 2 + (if (lam > 0.55f) 1 else 0)
                val arr = strandBuckets[bucket]
                val o = strandCounts[bucket]
                arr[o] = xs[i0]; arr[o + 1] = ys[i0]; arr[o + 2] = xs[i1]; arr[o + 3] = ys[i1]
                strandCounts[bucket] = o + 4
            }
        }
        strandPaint.strokeWidth = max(1.3f, strokePx * 1.05f)
        for (bucket in 0 until 6) {
            if (strandCounts[bucket] == 0) continue
            val tone = bucket / 2
            val lit = bucket % 2 == 1
            val mul = when (tone) { 0 -> 0.50f; 1 -> 0.95f; else -> 1.45f } * (if (lit) 1.0f else 0.72f)
            val add = if (tone == 2) 0.06f else 0f
            strandPaint.color = (0xB8 shl 24) or pack(hr * mul + add, hg * mul + add, hb * mul + add).and(0x00FFFFFF)
            nc.drawLines(strandBuckets[bucket], 0, strandCounts[bucket], strandPaint)
        }
        if (fringeCount > 0) {
            // the locks over the forehead: thick, then a thin highlight along them
            strandPaint.strokeWidth = max(2.6f, strokePx * 2.6f)
            strandPaint.color = (0xEE shl 24) or pack(hr * 0.92f, hg * 0.92f, hb * 0.92f).and(0x00FFFFFF)
            nc.drawLines(fringeLines, 0, fringeCount, strandPaint)
            strandPaint.strokeWidth = max(1.1f, strokePx * 0.9f)
            strandPaint.color = (0x90 shl 24) or pack(hr * 1.6f + 0.06f, hg * 1.6f + 0.05f, hb * 1.6f + 0.04f).and(0x00FFFFFF)
            nc.drawLines(fringeLines, 0, fringeCount, strandPaint)
        }
    }

    /** A dark crew-neck shirt with sloping shoulders, so the bust does not end in a cut tube. */
    fun drawShirt(scope: DrawScope, cx: Float, cy: Float, r: Float) {
        val neckTop = cy + 1.13f * r
        val neckDip = cy + 1.30f * r
        val shoulderY = cy + 1.72f * r
        val reach = 2.3f * r
        val path = Path().apply {
            moveTo(cx - 0.72f * r, neckTop)
            quadraticBezierTo(cx, neckDip + 0.16f * r, cx + 0.72f * r, neckTop)
            cubicTo(cx + 1.15f * r, neckTop + 0.05f * r, cx + 1.7f * r, shoulderY - 0.15f * r, cx + reach, shoulderY + 0.20f * r)
            lineTo(cx + reach, cy + 4f * r)
            lineTo(cx - reach, cy + 4f * r)
            lineTo(cx - reach, shoulderY + 0.20f * r)
            cubicTo(cx - 1.7f * r, shoulderY - 0.15f * r, cx - 1.15f * r, neckTop + 0.05f * r, cx - 0.72f * r, neckTop)
            close()
        }
        scope.drawPath(path, Brush.verticalGradient(listOf(Color(0xFF39424F), Color(0xFF232A34), Color(0xFF181D25)), startY = neckTop, endY = cy + 2.6f * r))
        val collar = Path().apply {
            moveTo(cx - 0.72f * r, neckTop)
            quadraticBezierTo(cx, neckDip + 0.16f * r, cx + 0.72f * r, neckTop)
        }
        scope.drawPath(collar, Color(0x66000000), style = Stroke(width = r * 0.05f, cap = StrokeCap.Round))
    }

    private fun ring(xs: FloatArray, ys: FloatArray, idx: IntArray): Path {
        val p = Path()
        for ((k, i) in idx.withIndex()) if (k == 0) p.moveTo(xs[i], ys[i]) else p.lineTo(xs[i], ys[i])
        return p
    }

    private fun closed(xs: FloatArray, ys: FloatArray, idx: IntArray): Path = ring(xs, ys, idx).also { it.close() }

    private fun shade(color: Int, f: Float): Color {
        val r = ((color shr 16) and 0xFF) * f
        val g = ((color shr 8) and 0xFF) * f
        val b = (color and 0xFF) * f
        return Color(r.coerceIn(0f, 255f).toInt(), g.coerceIn(0f, 255f).toInt(), b.coerceIn(0f, 255f).toInt())
    }

    /** Brows, eyes, lips, teeth and tongue, from the landmark rings. */
    fun drawFeatures(scope: DrawScope, avatar: HoloAvatar, xs: FloatArray, ys: FloatArray, r: Float, look: AvatarLook, amp: Float, strokePx: Float) {
        val face = max(0f, cos(avatar.yaw) * cos(avatar.pitch)).let { it * it }
        if (face < 0.02f) return
        val lm = mesh.landmarks
        // The mesh's eye slit is narrow: open it up a little so the eyes read as awake (a blink or sleep still closes them).
        val vis = ((1f - avatar.blink) * avatar.lids.coerceIn(0f, 1f) * 1.4f).coerceIn(0.04f, 1.4f)

        // brows: a thick soft stroke in the hair colour, darker than the hair itself
        for (key in listOf("brow_l", "brow_r")) {
            scope.drawPath(
                ring(xs, ys, lm.getValue(key)), shade(look.hairColor, 0.72f).copy(alpha = 0.88f * face),
                style = Stroke(width = r * 0.050f, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }

        // eyes
        val iris = look.eyeColor
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
            scope.clipPath(path) {
                // sclera: never pure white, a little grey at the top where the lid shades it
                drawRect(
                    Brush.verticalGradient(listOf(Color(0xFFBDB6AE), Color(0xFFF1EDE6), Color(0xFFE2DBD2)), startY = minY, endY = maxY),
                    topLeft = Offset(minX, minY), size = Size(w, h),
                )
                val gx = (minX + maxX) / 2f + avatar.gaze[0] * w * 0.15f
                val gy = (minY + maxY) / 2f + avatar.gaze[1] * h * 0.16f
                val rad = min(w * 0.235f, max(h * 1.05f, w * 0.12f))
                drawCircle(
                    Brush.radialGradient(
                        0.0f to shade(iris, 1.25f), 0.55f to shade(iris, 0.95f), 0.86f to shade(iris, 0.55f), 1.0f to Color(0xFF15100C),
                        center = Offset(gx, gy), radius = rad,
                    ),
                    radius = rad, center = Offset(gx, gy),
                )
                drawCircle(Color(0xFF0A0706), radius = rad * (0.40f + 0.07f * amp), center = Offset(gx, gy))
                drawCircle(Color.White.copy(alpha = 0.85f), radius = rad * 0.15f, center = Offset(gx - rad * 0.30f, gy - rad * 0.32f))
                // the upper lid's shadow on the eyeball
                drawRect(
                    Brush.verticalGradient(listOf(Color(0x66000000), Color(0x00000000)), startY = minY, endY = minY + h * 0.50f),
                    topLeft = Offset(minX, minY), size = Size(w, h * 0.55f),
                )
            }
            // lash line
            scope.drawPath(path, Color(0xE01A1210).copy(alpha = 0.90f * face), style = Stroke(width = strokePx * 1.5f, join = StrokeJoin.Round))
        }

        // mouth
        val outer = closed(xs, ys, lm.getValue("lips_out"))
        val innerIdx = lm.getValue("lips_in")
        val inner = closed(xs, ys, innerIdx)
        var lipTop = Float.MAX_VALUE; var lipBottom = -Float.MAX_VALUE
        for (i in lm.getValue("lips_out")) { lipTop = min(lipTop, ys[i]); lipBottom = max(lipBottom, ys[i]) }
        val lipBase = blendRgb(look.skinColor, 0xFFB0424E.toInt(), 0.58f)
        scope.drawPath(outer, Brush.verticalGradient(listOf(shade(lipBase, 1.08f), shade(lipBase, 0.82f)), startY = lipTop, endY = lipBottom))
        val mouth = avatar.mouth
        var openTop = Float.MAX_VALUE; var openBottom = -Float.MAX_VALUE; var openLeft = Float.MAX_VALUE; var openRight = -Float.MAX_VALUE
        for (i in innerIdx) { openTop = min(openTop, ys[i]); openBottom = max(openBottom, ys[i]); openLeft = min(openLeft, xs[i]); openRight = max(openRight, xs[i]) }
        val openH = openBottom - openTop
        if (mouth > 0.03f && openH > 1.2f) {
            scope.drawPath(inner, Color(0xFF2A1114))
            scope.clipPath(inner) {
                val upper = innerIdx.copyOfRange(10, innerIdx.size) + innerIdx[0]
                val teeth = Path()
                for ((k, i) in upper.withIndex()) if (k == 0) teeth.moveTo(xs[i], ys[i]) else teeth.lineTo(xs[i], ys[i])
                for (i in upper.reversed()) teeth.lineTo(xs[i], ys[i] + openH * 0.36f)
                teeth.close()
                drawPath(teeth, Brush.verticalGradient(listOf(Color(0xFFEDE7DA), Color(0xFFD8D0C2)), startY = openTop, endY = openTop + openH * 0.4f))
                if (mouth > 0.45f) {
                    val lower = Path()
                    val low = innerIdx.copyOfRange(0, 10)
                    for ((k, i) in low.withIndex()) if (k == 0) lower.moveTo(xs[i], ys[i]) else lower.lineTo(xs[i], ys[i])
                    for (i in low.reversed()) lower.lineTo(xs[i], ys[i] - openH * 0.20f)
                    lower.close()
                    drawPath(lower, Color(0xFFCFC7B8))
                }
                drawOval(
                    Color(0xFFB0616A), topLeft = Offset((openLeft + openRight) / 2f - (openRight - openLeft) * 0.24f, openBottom - openH * 0.42f),
                    size = Size((openRight - openLeft) * 0.48f, openH * 0.5f),
                )
            }
        }
        // the line where the lips meet, and the soft edge of the lips
        scope.drawPath(inner, Color(0xFF4A1F25).copy(alpha = (0.55f + 0.3f * (1f - mouth)) * face), style = Stroke(width = strokePx * 1.2f, join = StrokeJoin.Round))
    }

    private fun blendRgb(a: Int, b: Int, f: Float): Int {
        fun ch(shift: Int) = (((a shr shift) and 0xFF) * (1 - f) + ((b shr shift) and 0xFF) * f).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    @Suppress("unused")
    private fun len(x: Float, y: Float) = sqrt(x * x + y * y)
}
