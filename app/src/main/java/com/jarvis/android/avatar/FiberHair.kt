package com.jarvis.android.avatar

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The hair of the hologram look: optical fibres. Each lock of the mesh carries a bundle of thin strands that sweep over the head like
 * the fibres of a fibre-optic lamp: dim at the root, brighter towards the tip, which ends in a small gold spark, and a pulse of light
 * runs from the root to the tip of some of them. The strands are added to what is behind them (they glow) and sway a little. They follow
 * the locks of the mesh, so they move with the head. The mass of the hair underneath is a dark blue (see AvatarRenderer).
 */
internal class FiberHair(private val mesh: HeadMesh) {
    private val rows = mesh.lockRows
    private val locks = min(mesh.lockCount, MAX_LOCKS)
    private val total = locks * PER_LOCK
    private val across = FloatArray(total)     // where the strand lies across the lock, -0.9 to 0.9
    private val amp = FloatArray(total)
    private val freq = FloatArray(total)
    private val phase = FloatArray(total)
    private val pulse = BooleanArray(total)
    private val spark = BooleanArray(total)
    private val buckets = Array(max(rows - 1, 1)) { FloatArray(total * 4) }
    private val counts = IntArray(max(rows - 1, 1))
    private val hotLines = FloatArray(total * 16)
    private var hotCount = 0
    private val sparks = FloatArray(total * 2)
    private var sparkCount = 0
    private val add = PorterDuffXfermode(PorterDuff.Mode.ADD)

    init {
        for (l in 0 until locks) for (f in 0 until PER_LOCK) {
            val i = l * PER_LOCK + f
            val h = ((l * 31 + f * 1039) * -1640531535)
            across[i] = -0.9f + 1.8f * (f + 0.5f + 0.4f * (((h ushr 8) and 0xFF) / 255f - 0.5f)) / PER_LOCK
            amp[i] = 0.10f + 0.16f * ((h ushr 16) and 0xFF) / 255f
            freq[i] = 1.0f + 1.8f * ((h ushr 4) and 0xFF) / 255f
            phase[i] = ((h ushr 12) and 0xFF) / 255f * 6.2832f
            pulse[i] = ((h ushr 24) and 7) < 3
            spark[i] = ((h ushr 20) and 7) < 3
        }
    }

    fun draw(nc: Canvas, xs: FloatArray, ys: FloatArray, nrm: FloatArray, primary: Int, gold: Int, t: Float, strokePx: Float, paint: Paint) {
        if (rows < 3 || locks == 0) return
        counts.fill(0); hotCount = 0; sparkCount = 0
        for (l in 0 until locks) {
            val base = mesh.lockFirst + l * 3 * rows
            if (nrm[3 * (base + 1) + 2] < 0.10f) continue           // the root faces away
            for (f in 0 until PER_LOCK) {
                val i = l * PER_LOCK + f
                val u = across[i]
                val ph = if (pulse[i]) (t * 0.55f + i * 0.0137f) % 1.6f else -9f
                var px = 0f; var py = 0f
                for (s in 0 until rows) {
                    val li = base + 3 * s; val ci = li + 1; val ri = li + 2
                    val dx = xs[ri] - xs[li]; val dy = ys[ri] - ys[li]
                    val half = 0.5f * sqrt(dx * dx + dy * dy)
                    val along = s / (rows - 1f)
                    // a slow sway, stronger towards the tip
                    val sway = half * amp[i] * sin(along * freq[i] * 6.2832f + phase[i] + t * 1.1f) * (0.4f + 0.6f * along)
                    val nl = max(sqrt(dx * dx + dy * dy), 1e-4f)
                    val x = xs[ci] + dx * 0.5f * u + (dy / nl) * sway
                    val y = ys[ci] + dy * 0.5f * u - (dx / nl) * sway
                    if (s > 0) {
                        if (abs(along - ph) < 0.2f) {
                            val o = hotCount; hotLines[o] = px; hotLines[o + 1] = py; hotLines[o + 2] = x; hotLines[o + 3] = y; hotCount += 4
                        } else {
                            val b = counts.size.coerceAtMost(s) - 1
                            val o = counts[b]; buckets[b][o] = px; buckets[b][o + 1] = py; buckets[b][o + 2] = x; buckets[b][o + 3] = y; counts[b] = o + 4
                        }
                    }
                    px = x; py = y
                }
                if (spark[i]) { sparks[sparkCount] = px; sparks[sparkCount + 1] = py; sparkCount += 2 }
            }
        }
        val old = paint.xfermode
        val cap = paint.strokeCap
        paint.xfermode = add
        paint.strokeCap = Paint.Cap.ROUND
        for (b in counts.indices) {
            if (counts[b] == 0) continue
            val frac = (b + 1f) / counts.size
            paint.strokeWidth = max(0.7f, strokePx * 0.5f)
            paint.color = withAlphaInt(mixInt(primary, 0xFFFFFFFF.toInt(), 0.25f * frac * frac), 3f + 17f * frac * frac)
            nc.drawLines(buckets[b], 0, counts[b], paint)
        }
        if (hotCount > 0) {
            paint.strokeWidth = max(1.3f, strokePx * 0.95f)
            paint.color = withAlphaInt(mixInt(primary, 0xFFFFFFFF.toInt(), 0.6f), 120f)
            nc.drawLines(hotLines, 0, hotCount, paint)
        }
        if (sparkCount > 0) {
            paint.strokeWidth = max(2.4f, strokePx * 1.6f)
            paint.color = withAlphaInt(gold, 120f)
            nc.drawPoints(sparks, 0, sparkCount, paint)
        }
        paint.xfermode = old
        paint.strokeCap = cap
    }

    private fun withAlphaInt(rgb: Int, a: Float): Int = (a.toInt().coerceIn(0, 255) shl 24) or (rgb and 0x00FFFFFF)

    private fun mixInt(a: Int, b: Int, f: Float): Int {
        fun ch(shift: Int): Int {
            val x = (a shr shift) and 0xFF; val y = (b shr shift) and 0xFF
            return (x + (y - x) * f).toInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    companion object {
        private const val PER_LOCK = 3
        private const val MAX_LOCKS = 1100
    }
}
