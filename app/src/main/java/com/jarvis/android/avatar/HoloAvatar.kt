package com.jarvis.android.avatar

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** What the face should be doing, reduced from the assistant's state. */
internal enum class Mood { IDLE, LISTENING, THINKING, ASLEEP }

/** Brow travel at full lift, in head-half-heights (a third of the brow-to-eye gap). */
private const val BROW_LIFT = 0.14f

// Mouth timing as time constants in seconds, so the motion is the same at 20, 30 or 60 frames a second.
private const val TAU_OPEN = 0.022f   // jaw dropping toward a vowel
private const val TAU_SHUT = 0.012f   // lips closing on a consonant, mid-word
private const val TAU_REST = 0.055f   // settling back after speech
private const val TAU_SHAPE = 0.018f  // openness following the schedule
private const val MIC_FLOOR = 0.14f
private const val CLOSE_FRAC = 0.10f  // -20 dB below this voice's loud level counts as a closure

/** Frame-rate independent lerp factor for an exponential approach with time constant [tau]. */
internal fun rate(dt: Float, tau: Float): Float = 1f - exp(-dt / tau)

/**
 * The animation of the head: jaw, lips, brows, eyes, blinks, sway, and what the assistant's state does to the face.
 * Ported from Mark-LIV's HoloAvatar (CC BY-NC 4.0, see assets/avatar/NOTICE.txt); this class has no drawing code.
 */
internal class HoloAvatar(val mesh: HeadMesh, private val random: Random = Random.Default) {
    var time = 0f; private set
    private var sway = 0f
    var yaw = 0f; private set
    var pitch = 0f; private set
    var mouth = 0f; private set
    var glow = 0f; private set
    var scan = -1.6f; private set
    var blink = 0f; private set
    private var blinkAt = 3f
    private var ampSlow = 0f
    private var expr = 0f
    private var exprTgt = 0f
    private var exprAt = 0f
    var brow = 0f; private set
    private var emph = 0f
    val gaze = FloatArray(2)
    private val gazeTgt = FloatArray(2)
    private var gazeAt = 0f
    private val gazeBias = FloatArray(2)
    private val biasTgt = FloatArray(2)
    private var biasAt = 0f
    var lids = 1f; private set
    private var browBias = 0f
    private var glance: FloatArray? = null // dx, dy, until
    private var vOpen = 1f
    private var vPeak = 0.18f
    var wide = 0f; private set
    private var lastVWide = 0f

    /** Posed vertices and normals of the last [pose] call. */
    val pv = FloatArray(mesh.verts.size)
    val pn = FloatArray(mesh.normals.size)

    private fun mouthStep(dt: Float, amp: Float, live: Boolean, vOpenIn: Float?, vLevel: Float?) {
        val shape: Float
        if (vOpenIn == null) {
            shape = 1f
        } else {
            vOpen += (vOpenIn - vOpen) * rate(dt, TAU_SHAPE)
            shape = vOpen
        }
        val drive: Float
        if (vLevel == null) {
            val gated = max(0f, (amp - MIC_FLOOR) / (1f - MIC_FLOOR))
            drive = Math.pow(gated.toDouble(), 0.6).toFloat() * Math.pow(shape.toDouble(), 0.75).toFloat()
        } else {
            // Speech RMS sits well below full scale: normalise it against a running estimate of this voice's own loud level.
            vPeak = max(vLevel, vPeak - dt * 0.55f)
            val ref = max(0.18f, vPeak)
            val q = (vLevel - CLOSE_FRAC * ref) / (ref * (1f - CLOSE_FRAC))
            drive = Math.pow(q.coerceIn(0f, 1f).toDouble(), 0.85).toFloat() * Math.pow(shape.toDouble(), 0.75).toFloat()
        }
        val target = if (live) min(1f, drive) else 0f
        val tau = when {
            target > mouth -> TAU_OPEN
            live -> TAU_SHUT
            else -> TAU_REST
        }
        mouth += (target - mouth) * rate(dt, tau)
        if (mouth < 0.002f) mouth = 0f
    }

    /**
     * Advances the animation by [dtIn] seconds. [amp] is the 0..1 output level; [frames] are the viseme frames that came
     * due since the last call (null when no voice schedule is playing, in which case loudness alone drives the mouth).
     */
    fun step(dtIn: Float, amp0: Float, speaking: Boolean, mood: Mood, frames: List<AudioViseme>?, hop: Float = 0.02f) {
        val dt = dtIn.coerceIn(0.001f, 0.10f)
        time += dt
        val t = time
        val amp = amp0.coerceIn(0f, 1f)
        val live = speaking

        // Idle sway: the phase is integrated, so changing speed never teleports the head.
        sway += dt * (if (live) 1.25f else 1f)
        val s = sway
        yaw = 0.26f * sin(s * 0.31f) + 0.09f * sin(s * 0.73f + 1.3f)
        pitch = 0.060f * sin(s * 0.23f + 0.7f) + 0.024f * sin(s * 0.61f)

        if (frames != null) {
            for (f in frames) mouthStep(hop, amp, live, f.open, f.level)
            if (frames.isNotEmpty()) lastVWide = frames.last().wide
        } else {
            mouthStep(dt, amp, live, null, null)
        }

        // Syllable emphasis nods the head.
        emph += (mouth - emph) * rate(dt, if (mouth > emph) 0.055f else 0.32f)
        pitch -= emph * 0.028f
        yaw += 0.018f * sin(t * 1.7f) * emph

        // The loudness envelope is lazier than the mouth: brows follow the phrase, not each syllable.
        val env = if (live) amp else 0f
        ampSlow += (env - ampSlow) * rate(dt, if (env > ampSlow) 0.16f else 0.36f)

        if (live) {
            if (t >= exprAt) {
                exprTgt = random.nextFloat() * 1.35f - 0.35f
                exprAt = t + 1.1f + 2f * random.nextFloat()
            }
        } else {
            exprTgt = 0f
            exprAt = t + 0.8f
        }
        expr += (exprTgt - expr) * rate(dt, 0.43f)

        val browT = 0.55f * ampSlow + 0.60f * expr + browBias
        brow += (browT.coerceIn(-0.4f, 1.2f) - brow) * rate(dt, 0.15f)

        // What the state does to the face: eyes off to the side while thinking, on the user while listening, lids low asleep.
        val thinking = mood == Mood.THINKING
        val asleep = mood == Mood.ASLEEP
        val browBiasTarget: Float
        val lidTarget: Float
        if (thinking) {
            if (t >= biasAt) {
                biasTgt[0] = (if (random.nextBoolean()) -1f else 1f) * (0.45f + 0.35f * random.nextFloat())
                biasTgt[1] = 0.25f + 0.30f * random.nextFloat()
                biasAt = t + 1.4f + 1.6f * random.nextFloat()
            }
            browBiasTarget = -0.28f; lidTarget = 0.94f
        } else if (asleep) {
            biasTgt[0] = 0f; biasTgt[1] = -0.25f
            browBiasTarget = -0.05f; lidTarget = 0.22f
        } else {
            biasTgt[0] = 0f; biasTgt[1] = 0f; biasAt = 0f
            browBiasTarget = if (mood == Mood.LISTENING) 0.10f else 0f
            lidTarget = 1f
        }
        for (i in 0..1) gazeBias[i] += (biasTgt[i] - gazeBias[i]) * rate(dt, 0.54f)
        lids += (lidTarget - lids) * rate(dt, 0.40f)
        browBias += (browBiasTarget - browBias) * rate(dt, 0.54f)

        // Gaze: near-instant saccades between fixations, more often when there is something to say, slow while thinking.
        if (t >= gazeAt) {
            val reach = if (live) 0.9f else if (thinking) 0.35f else 0.55f
            gazeTgt[0] = (random.nextFloat() * 2f - 1f) * reach
            gazeTgt[1] = (random.nextFloat() * 2f - 1f) * reach * 0.55f
            gazeAt = t + when {
                live -> 0.55f + 1.7f * random.nextFloat()
                thinking -> 1.8f + 2.4f * random.nextFloat()
                else -> 1.3f + 2.8f * random.nextFloat()
            }
        }
        glance?.let { g ->
            if (t < g[2]) { gazeTgt[0] = g[0]; gazeTgt[1] = g[1] } else glance = null
        }
        for (i in 0..1) gaze[i] += ((gazeTgt[i] + gazeBias[i]).coerceIn(-1f, 1f) - gaze[i]) * rate(dt, 0.09f)

        // Lips lead the jaw slightly and relax to neutral when the voice stops.
        val wideT = if (live && frames != null) lastVWide else 0f
        wide += (wideT.coerceIn(-1f, 1f) - wide) * rate(dt, 0.030f)

        val gl = amp
        glow += (gl - glow) * (if (gl > glow) 0.35f else 0.10f)
        scan += dt * (0.55f + 1.5f * glow)
        if (scan > 1.35f) scan = -1.75f

        if (blink > 0f) {
            blink = max(0f, blink - dt * 8.5f)
        } else if (t >= blinkAt) {
            if (asleep) {
                blinkAt = t + 6f
            } else {
                blink = 1f
                blinkAt = t + (if (thinking) 5.5f else 3.4f) + 3.1f * random.nextFloat()
            }
        }
    }

    /** Look deliberately somewhere for [hold] seconds (something appeared on screen), then wander again. */
    fun glance(dx: Float, dy: Float, hold: Float = 1.1f) {
        glance = floatArrayOf(dx.coerceIn(-1f, 1f), dy.coerceIn(-1f, 1f), time + max(0.1f, hold))
    }

    /** Applies the brow lift, lip spread or round, jaw drop and head rotation to the real geometry. */
    fun pose() {
        val n = mesh.vertexCount
        val v = pv
        System.arraycopy(mesh.verts, 0, v, 0, v.size)

        if (abs(brow) > 0.004f) {
            val k = brow * BROW_LIFT
            for (i in 0 until n) v[3 * i + 1] += mesh.brow[i] * k
        }
        // The lids: the skin round each eye drops (upper lid) and rises (lower lid) as the eyes close.
        val close = (1f - ((1f - blink) * lids.coerceIn(0f, 1f))).coerceIn(0f, 0.97f)
        if (close > 0.01f) {
            for (i in 0 until n) {
                val l = mesh.lid[i]
                if (l != 0f) v[3 * i + 1] -= l * close
            }
        }
        // The eyeballs turn towards the gaze, about their own centres.
        val ey = gaze[0] * 0.32f
        val ep = gaze[1] * 0.26f
        val cye = cos(ey); val sye = sin(ey); val cpe = cos(ep); val spe = sin(ep)
        for (e in mesh.eyeFirst.indices) {
            val cx = mesh.eyeCentre[3 * e]; val cy0 = mesh.eyeCentre[3 * e + 1]; val cz = mesh.eyeCentre[3 * e + 2]
            for (i in mesh.eyeFirst[e] until mesh.eyeFirst[e] + mesh.eyeCount[e]) {
                val x = v[3 * i] - cx; val y = v[3 * i + 1] - cy0; val z = v[3 * i + 2] - cz
                val x1 = x * cye + z * sye; val z1 = -x * sye + z * cye
                val y2 = y * cpe - z1 * spe; val z2 = y * spe + z1 * cpe
                v[3 * i] = cx + x1; v[3 * i + 1] = cy0 + y2; v[3 * i + 2] = cz + z2
            }
        }
        if (abs(wide) > 0.01f && mouth > 0f) {
            val kk = wide * mouth
            val lx = mesh.lipCentre[0]
            val ly = mesh.lipCentre[1]
            for (i in 0 until n) {
                val k = mesh.lips[i] * kk
                if (k == 0f) continue
                v[3 * i] += k * (v[3 * i] - lx) * 0.55f
                v[3 * i + 1] += k * (v[3 * i + 1] - ly) * 0.30f
                v[3 * i + 2] -= k * 0.055f
            }
        }
        if (mouth > 0.004f) {
            val py = JAW_PIVOT[1]
            val pz = JAW_PIVOT[2]
            for (i in 0 until n) {
                val w = mesh.jaw[i]
                if (w == 0f) continue
                val ang = w * (mouth * JAW_MAX)
                val ca = cos(ang)
                val sa = sin(ang)
                val dy = v[3 * i + 1] - py
                val dz = v[3 * i + 2] - pz
                v[3 * i + 1] = py + dy * ca - dz * sa
                v[3 * i + 2] = pz + dy * sa + dz * ca
            }
        }
        val cy = cos(yaw); val sy = sin(yaw)
        val cp = cos(pitch); val sp = sin(pitch)
        val m00 = cy; val m01 = 0f; val m02 = sy
        val m10 = sp * sy; val m11 = cp; val m12 = -sp * cy
        val m20 = -cp * sy; val m21 = sp; val m22 = cp * cy
        for (i in 0 until n) {
            val x = v[3 * i]; val y = v[3 * i + 1]; val z = v[3 * i + 2]
            v[3 * i] = m00 * x + m01 * y + m02 * z
            v[3 * i + 1] = m10 * x + m11 * y + m12 * z
            v[3 * i + 2] = m20 * x + m21 * y + m22 * z
            val nx = mesh.normals[3 * i]; val ny = mesh.normals[3 * i + 1]; val nzv = mesh.normals[3 * i + 2]
            pn[3 * i] = m00 * nx + m01 * ny + m02 * nzv
            pn[3 * i + 1] = m10 * nx + m11 * ny + m12 * nzv
            pn[3 * i + 2] = m20 * nx + m21 * ny + m22 * nzv
        }
    }
}
