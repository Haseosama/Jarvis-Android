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
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * The cyber look: an android with glossy blue skin under a translucent violet cranial shell, neon circuit traces over the
 * shell, temples and cheeks, a glowing chip on the forehead, luminous eyes, and a circuit-board backdrop with pulses of
 * light running along the tracks. Drawn from the same mesh and landmark rings as the other styles.
 */
internal class CyberPainter(private val mesh: HeadMesh) {
    // key light high on the left, half vector for the gloss
    private val kx = -0.45f; private val ky = 0.55f; private val kz = 0.70f
    private val hx = -0.22f; private val hy = 0.28f; private val hz = 0.93f

    private val circuitBuckets = Array(3) { FloatArray(mesh.circuitCount * (mesh.circuitLength - 1) * 4) }
    private val circuitCounts = IntArray(3)
    private val glowPaint = Paint().apply { isAntiAlias = true; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }

    private fun pow(x: Float, e: Float) = Math.pow(x.toDouble(), e.toDouble()).toFloat()

    private fun pack(a: Float, r: Float, g: Float, b: Float): Int =
        ((a.coerceIn(0f, 1f) * 255f).toInt() shl 24) or ((r.coerceIn(0f, 1f) * 255f).toInt() shl 16) or
            ((g.coerceIn(0f, 1f) * 255f).toInt() shl 8) or (b.coerceIn(0f, 1f) * 255f).toInt()

    /** Glossy blue skin: violet in the shadows, cyan on the lit side, a sharp specular, cyan rim on the left and magenta on the right. */
    fun skinVertex(i: Int, nx: Float, ny: Float, nz: Float, amp: Float): Int {
        val ao = 0.55f + 0.45f * mesh.ao[i]
        val diff = ((nx * kx + ny * ky + nz * kz + 0.25f) / 1.25f).coerceIn(0f, 1f)
        val t = pow(diff, 0.85f)
        var r = 0.20f + (0.10f - 0.20f) * t
        var g = 0.06f + (0.40f - 0.06f) * t
        var b = 0.48f + (1.00f - 0.48f) * t
        r *= ao; g *= ao; b *= ao
        val spec = pow((nx * hx + ny * hy + nz * hz).coerceIn(0f, 1f), 36f) * (0.55f + 0.2f * amp)
        val rim = pow((1f - nz).coerceIn(0f, 1f), 2.6f) * 0.75f
        val side = (nx * 0.5f + 0.5f).coerceIn(0f, 1f) // 0 on the left, 1 on the right
        r += spec * 0.75f + rim * (0.20f + 0.70f * side)
        g += spec * 0.95f + rim * (0.90f - 0.60f * side)
        b += spec + rim * 1.0f
        val f = mesh.fade[i]
        if (f < 1f) { // the neck sinks into the dark
            r = 0.03f + (r - 0.03f) * f; g = 0.04f + (g - 0.04f) * f; b = 0.12f + (b - 0.12f) * f
        }
        return pack(1f, r, g, b)
    }

    /** The cranial shell: translucent violet glass over the skull, so the head shows through it. */
    fun shellVertex(i: Int, nx: Float, ny: Float, nz: Float, amp: Float): Int {
        val diff = ((nx * kx + ny * ky + nz * kz + 0.20f) / 1.20f).coerceIn(0f, 1f)
        val spec = pow((nx * hx + ny * hy + nz * hz).coerceIn(0f, 1f), 24f) * 0.6f
        val rim = pow((1f - nz).coerceIn(0f, 1f), 2.2f) * 0.8f
        val root = mesh.fade[i]
        val r = (0.26f + 0.42f * diff) * (0.6f + 0.4f * root) + spec * 0.8f + rim * 0.60f
        val g = (0.07f + 0.18f * diff) * (0.6f + 0.4f * root) + spec * 0.85f + rim * 0.35f
        val b = (0.46f + 0.46f * diff) * (0.6f + 0.4f * root) + spec + rim * 0.95f
        return pack(0.80f + 0.1f * rim, r, g, b)
    }

    /** Glowing circuit traces over the shell and face, and the forehead chip; each is a wide faint pass and a thin bright one. */
    fun drawCircuits(nc: Canvas, scope: DrawScope, xs: FloatArray, ys: FloatArray, nrm: FloatArray, time: Float, amp: Float, strokePx: Float) {
        circuitCounts.fill(0)
        val len = mesh.circuitLength
        val regular = mesh.circuitCount - mesh.brightCount
        for (c in 0 until mesh.circuitCount) {
            val base = mesh.circuitBase + c * len
            val bucket = if (c >= regular) 2 else (c % 2)
            for (k in 0 until len - 1) {
                val i0 = base + k; val i1 = i0 + 1
                if (xs[i0] == xs[i1] && ys[i0] == ys[i1]) continue
                if (min(nrm[3 * i0 + 2], nrm[3 * i1 + 2]) < 0.05f) continue
                val arr = circuitBuckets[bucket]
                val o = circuitCounts[bucket]
                arr[o] = xs[i0]; arr[o + 1] = ys[i0]; arr[o + 2] = xs[i1]; arr[o + 3] = ys[i1]
                circuitCounts[bucket] = o + 4
            }
        }
        val pulse = 0.75f + 0.25f * sin(time * 3.2f) + 0.3f * amp
        val colors = intArrayOf(pack(1f, 0.72f, 0.42f, 1.0f), pack(1f, 0.30f, 0.90f, 1.0f), pack(1f, 0.55f, 1.0f, 1.0f))
        for (b in 0 until 3) {
            if (circuitCounts[b] == 0) continue
            val c = colors[b]
            val wide = if (b == 2) 4.6f else 3.0f
            glowPaint.strokeWidth = strokePx * wide
            glowPaint.color = (c and 0x00FFFFFF) or ((if (b == 2) 0x46 else 0x2A) shl 24)
            nc.drawLines(circuitBuckets[b], 0, circuitCounts[b], glowPaint)
            glowPaint.strokeWidth = strokePx * (if (b == 2) 1.7f else 1.0f)
            glowPaint.color = (c and 0x00FFFFFF) or (((0.80f + 0.2f * pulse).coerceIn(0f, 1f) * 255f).toInt() shl 24)
            nc.drawLines(circuitBuckets[b], 0, circuitCounts[b], glowPaint)
        }
        // pulses of light running along a few traces
        for (c in 0 until mesh.circuitCount - mesh.brightCount step 3) {
            val base = mesh.circuitBase + c * len
            val pos = ((time * 0.9f + c * 0.37f) % 1f) * (len - 1)
            val k = pos.toInt().coerceIn(0, len - 2)
            val i0 = base + k
            if (nrm[3 * i0 + 2] < 0.05f) continue
            val f = pos - k
            val x = xs[i0] + (xs[i0 + 1] - xs[i0]) * f
            val y = ys[i0] + (ys[i0 + 1] - ys[i0]) * f
            scope.drawCircle(Color(0x55B8F4FF), radius = strokePx * 3.2f, center = Offset(x, y))
            scope.drawCircle(Color(0xFFE8FBFF), radius = strokePx * 1.1f, center = Offset(x, y))
        }
    }

    private fun ring(xs: FloatArray, ys: FloatArray, idx: IntArray): Path {
        val p = Path()
        for ((k, i) in idx.withIndex()) if (k == 0) p.moveTo(xs[i], ys[i]) else p.lineTo(xs[i], ys[i])
        return p
    }

    private fun closed(xs: FloatArray, ys: FloatArray, idx: IntArray): Path = ring(xs, ys, idx).also { it.close() }

    /** Luminous eyes, thin neon brows and glossy lips whose mouth glows from inside. */
    fun drawFeatures(scope: DrawScope, avatar: HoloAvatar, xs: FloatArray, ys: FloatArray, r: Float, amp: Float, strokePx: Float) {
        val face = max(0f, cos(avatar.yaw) * cos(avatar.pitch)).let { it * it }
        if (face < 0.02f) return
        val lm = mesh.landmarks
        val vis = ((1f - avatar.blink) * avatar.lids.coerceIn(0f, 1f) * 1.35f).coerceIn(0.04f, 1.4f)
        val neon = Color(0xFF7BE8FF)
        val violet = Color(0xFFB482FF)

        for (key in listOf("brow_l", "brow_r")) {
            val path = ring(xs, ys, lm.getValue(key))
            scope.drawPath(path, violet.copy(alpha = 0.30f * face), style = Stroke(width = strokePx * 4f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            scope.drawPath(path, Color(0xFFD9C6FF).copy(alpha = 0.95f * face), style = Stroke(width = strokePx * 1.5f, cap = StrokeCap.Round, join = StrokeJoin.Round))
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
            // bloom around the eye
            scope.drawCircle(Brush.radialGradient(listOf(Color(0x5540E0FF), Color(0x0040E0FF)), center = Offset(cx0, cy0), radius = w * 0.95f), radius = w * 0.95f, center = Offset(cx0, cy0))
            scope.clipPath(path) {
                drawRect(Color(0xFF060A2C), topLeft = Offset(minX, minY), size = Size(w, h))
                val gx = cx0 + avatar.gaze[0] * w * 0.14f
                val gy = cy0 + avatar.gaze[1] * h * 0.16f
                val rad = min(w * 0.26f, max(h * 1.1f, w * 0.13f))
                drawCircle(
                    Brush.radialGradient(
                        0.0f to Color(0xFFF2FFFF), 0.30f to Color(0xFF7FF0FF), 0.65f to Color(0xFF1E90FF), 1.0f to Color(0xFF1E2AB8),
                        center = Offset(gx, gy), radius = rad,
                    ),
                    radius = rad, center = Offset(gx, gy),
                )
                drawCircle(Color(0xFF031028), radius = rad * (0.32f + 0.06f * amp), center = Offset(gx, gy))
                drawCircle(Color.White.copy(alpha = 0.9f), radius = rad * 0.14f, center = Offset(gx - rad * 0.3f, gy - rad * 0.3f))
            }
            scope.drawPath(path, neon.copy(alpha = 0.30f * face), style = Stroke(width = strokePx * 4.2f, join = StrokeJoin.Round))
            scope.drawPath(path, neon.copy(alpha = 0.95f * face), style = Stroke(width = strokePx * 1.5f, join = StrokeJoin.Round))
        }

        // mouth
        val outer = closed(xs, ys, lm.getValue("lips_out"))
        val innerIdx = lm.getValue("lips_in")
        val inner = closed(xs, ys, innerIdx)
        var top = Float.MAX_VALUE; var bottom = -Float.MAX_VALUE
        for (i in lm.getValue("lips_out")) { top = min(top, ys[i]); bottom = max(bottom, ys[i]) }
        scope.drawPath(outer, Brush.verticalGradient(listOf(Color(0xFF5C9CFF), Color(0xFF2C4FD8)), startY = top, endY = bottom))
        val mouth = avatar.mouth
        var oTop = Float.MAX_VALUE; var oBottom = -Float.MAX_VALUE; var oL = Float.MAX_VALUE; var oR = -Float.MAX_VALUE
        for (i in innerIdx) { oTop = min(oTop, ys[i]); oBottom = max(oBottom, ys[i]); oL = min(oL, xs[i]); oR = max(oR, xs[i]) }
        val openH = oBottom - oTop
        if (mouth > 0.03f && openH > 1.2f) {
            scope.drawPath(inner, Color(0xFF030618))
            scope.clipPath(inner) {
                // the inside glows: a cyan light from the back of the throat and a row of small bars that follow the voice
                drawRect(Brush.verticalGradient(listOf(Color(0x0040E0FF), Color(0x9040E0FF)), startY = oTop, endY = oBottom), topLeft = Offset(oL, oTop), size = Size(oR - oL, openH))
                val bars = 7
                val bw = (oR - oL) / (bars * 1.6f)
                for (k in 0 until bars) {
                    val bx = oL + (oR - oL) * (k + 0.5f) / bars
                    val bh = openH * (0.25f + 0.55f * ((sin(avatar.time * 11f + k * 1.7f) * 0.5f + 0.5f) * mouth))
                    drawRect(Color(0xFFBFF7FF).copy(alpha = 0.85f), topLeft = Offset(bx - bw / 2f, oBottom - bh), size = Size(bw, bh))
                }
            }
        }
        scope.drawPath(inner, Color(0xFF06123A).copy(alpha = (0.55f + 0.3f * (1f - mouth)) * face), style = Stroke(width = strokePx * 1.3f, join = StrokeJoin.Round))
        scope.drawPath(outer, Color(0xFF9CC8FF).copy(alpha = 0.55f * face), style = Stroke(width = strokePx * 0.9f, join = StrokeJoin.Round))
    }

    /** Ribbed mechanical neck and a dark armoured collar with neon edges. */
    fun drawNeckAndCollar(scope: DrawScope, cx: Float, cy: Float, r: Float, strokePx: Float, time: Float) {
        val neckTop = cy + 1.13f * r
        val dip = cy + 1.46f * r
        val shoulderY = cy + 1.80f * r
        val reach = 1.85f * r
        // rings around the neck, just above the collar
        for (k in 0..3) {
            val y = cy + (0.98f + 0.075f * k) * r
            val half = (0.40f + 0.05f * k) * r
            val a = 0.16f + 0.05f * k
            scope.drawArc(Color(0xFF0B1240).copy(alpha = 0.55f), 0f, 180f, false, Offset(cx - half, y - 0.06f * r), Size(2f * half, 0.12f * r), style = Stroke(width = r * 0.04f))
            scope.drawArc(Color(0xFF7BE8FF).copy(alpha = a), 0f, 180f, false, Offset(cx - half, y - 0.06f * r), Size(2f * half, 0.12f * r), style = Stroke(width = strokePx))
        }
        val path = Path().apply {
            moveTo(cx - 0.72f * r, neckTop)
            quadraticBezierTo(cx, dip, cx + 0.72f * r, neckTop)
            cubicTo(cx + 1.15f * r, neckTop + 0.05f * r, cx + 1.7f * r, shoulderY - 0.15f * r, cx + reach, shoulderY + 0.20f * r)
            lineTo(cx + reach, cy + 4f * r)
            lineTo(cx - reach, cy + 4f * r)
            lineTo(cx - reach, shoulderY + 0.20f * r)
            cubicTo(cx - 1.7f * r, shoulderY - 0.15f * r, cx - 1.15f * r, neckTop + 0.05f * r, cx - 0.72f * r, neckTop)
            close()
        }
        // the collar fades into the background towards the bottom instead of ending in a hard block
        scope.drawPath(path, Brush.verticalGradient(listOf(Color(0xFF1F2A80), Color(0xEE0B1240), Color(0x0005081F)), startY = neckTop, endY = cy + 2.55f * r))
        val edge = Path().apply {
            moveTo(cx - 0.72f * r, neckTop)
            quadraticBezierTo(cx, dip, cx + 0.72f * r, neckTop)
        }
        scope.drawPath(edge, Color(0x557BE8FF), style = Stroke(width = strokePx * 4f, cap = StrokeCap.Round))
        scope.drawPath(edge, Color(0xFFB6F4FF), style = Stroke(width = strokePx * 1.4f, cap = StrokeCap.Round))
        // a few neon lines across the shoulders
        for (k in 0..2) {
            val y = shoulderY + (0.10f + 0.16f * k) * r
            val line = Path().apply {
                moveTo(cx - (1.45f - 0.15f * k) * r, y + 0.05f * r); lineTo(cx - (0.9f - 0.1f * k) * r, y); lineTo(cx - (0.55f) * r, y + 0.10f * r)
                moveTo(cx + (1.45f - 0.15f * k) * r, y + 0.05f * r); lineTo(cx + (0.9f - 0.1f * k) * r, y); lineTo(cx + (0.55f) * r, y + 0.10f * r)
            }
            scope.drawPath(line, Color(0xFF9F6BFF).copy(alpha = 0.55f), style = Stroke(width = strokePx * 1.1f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }

    // ── backdrop ────────────────────────────────────────────────────────────

    private class Track(val xy: FloatArray, val violet: Boolean)

    private val tracks: List<Track> = buildTracks()

    private fun buildTracks(): List<Track> {
        val rnd = Random(5)
        val out = ArrayList<Track>()
        var attempts = 0
        while (out.size < 36 && attempts++ < 400) {
            val ang = rnd.nextFloat() * 2f * PI.toFloat()
            val rad = 0.50f + 0.16f * rnd.nextFloat()
            var x = cos(ang) * rad
            var y = sin(ang) * rad * 0.9f
            if (kotlin.math.abs(x) < 0.30f) continue // keep the middle free for the head
            // head outwards, snapped to 45 degree steps
            var dir = (Math.round(ang / (PI.toFloat() / 4f)) * (PI.toFloat() / 4f))
            val pts = arrayListOf(x, y)
            for (seg in 0 until 3 + rnd.nextInt(3)) {
                val length = 0.07f + 0.17f * rnd.nextFloat()
                x += cos(dir) * length; y += sin(dir) * length
                pts += x; pts += y
                dir += (if (rnd.nextBoolean()) 1 else -1) * (if (rnd.nextInt(3) == 0) PI.toFloat() / 2f else PI.toFloat() / 4f)
            }
            out += Track(pts.toFloatArray(), rnd.nextInt(3) != 0)
        }
        return out
    }

    /** The circuit-board backdrop, in the coordinates of the square canvas: fading out towards its edges, with pulses running along the tracks. */
    fun drawBackdrop(scope: DrawScope, size: Float, time: Float, amp: Float) {
        val c = size / 2f
        val s = size / 2f
        for ((n, t) in tracks.withIndex()) {
            val pts = t.xy
            val col = if (t.violet) Color(0xFF9A6BFF) else Color(0xFF3AD8FF)
            val mx = pts[pts.size / 2 - (pts.size / 2) % 2]; val my = pts[pts.size / 2 - (pts.size / 2) % 2 + 1]
            val fade = ((1.15f - hypot(mx, my)) / 0.55f).coerceIn(0f, 1f)
            if (fade <= 0.02f) continue
            val path = Path()
            var k = 0
            while (k < pts.size) {
                val px = c + pts[k] * s; val py = c + pts[k + 1] * s
                if (k == 0) path.moveTo(px, py) else path.lineTo(px, py)
                k += 2
            }
            scope.drawPath(path, col.copy(alpha = 0.16f * fade), style = Stroke(width = 5f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            scope.drawPath(path, col.copy(alpha = 0.62f * fade), style = Stroke(width = 1.5f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            val ex = c + pts[pts.size - 2] * s; val ey = c + pts[pts.size - 1] * s
            scope.drawCircle(col.copy(alpha = 0.9f * fade), radius = 3.6f, center = Offset(ex, ey), style = Stroke(width = 1.5f))
            // a pulse of light along the track
            val segs = pts.size / 2 - 1
            val pos = ((time * 0.35f + n * 0.173f) % 1f) * segs
            val si = pos.toInt().coerceIn(0, segs - 1)
            val f = pos - si
            val px = c + (pts[2 * si] + (pts[2 * si + 2] - pts[2 * si]) * f) * s
            val py = c + (pts[2 * si + 1] + (pts[2 * si + 3] - pts[2 * si + 1]) * f) * s
            scope.drawCircle(Color.White.copy(alpha = 0.85f * fade), radius = 2.2f + amp * 1.5f, center = Offset(px, py))
        }
        // a vertical beam of light above the head
        scope.drawRect(
            Brush.verticalGradient(listOf(Color(0x8840E0FF), Color(0x0040E0FF)), startY = 0f, endY = size * 0.30f),
            topLeft = Offset(c - 1.5f, 0f), size = Size(3f, size * 0.30f),
        )
    }
}
