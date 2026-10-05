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

/** The hair's spring (stiffness, damping: a little overshoot) and the point it turns about, the top of the skull. */
private const val HAIR_STIFFNESS = 38f
private const val HAIR_DAMPING = 7.5f
private const val HAIR_PIVOT_X = 0f
private const val HAIR_PIVOT_Y = 0.55f
private const val HAIR_PIVOT_Z = -0.25f

// Mouth timing as time constants in seconds, so the motion is the same at 20, 30 or 60 frames a second.
private const val TAU_OPEN = 0.022f   // jaw dropping toward a vowel
private const val TAU_SHUT = 0.012f   // lips closing on a consonant, mid-word
private const val TAU_REST = 0.055f   // settling back after speech
private const val TAU_SHAPE = 0.018f  // openness following the schedule
private const val MIC_FLOOR = 0.14f
private const val CLOSE_FRAC = 0.10f  // -20 dB below this voice's loud level counts as a closure

// Expressions (see Expressions.kt), in head-half-heights: how far a full smile lifts the corners of the mouth and pulls them out, how
// far worry lifts the inner ends of the brows, how much wider surprise opens the eyes (a share of the lids' travel), and how long the
// face keeps a feeling once the voice stops.
private const val SMILE_LIFT = 0.070f
private const val SMILE_PULL = 0.030f
private const val INNER_LIFT = 0.090f
private const val WIDEN_OPEN = 0.30f
private const val FEELING_HOLD = 1.6f
private const val TAU_FEELING = 0.22f
private const val MOUTH_INSIDE_WEIGHT = 0.7f   // about the lips' own weight at the corners of the mouth

// A finger on the screen (see HoloAvatar.follow): how far in front of the face the screen sits, in head half-heights; how long the eyes
// stay on where the finger was once it lifts; how far the head turns towards it. And how often a blink comes twice in a row.
private const val FINGER_DEPTH = 1.3f
private const val FOLLOW_HOLD = 0.9f
private const val AIM_SWAY = 0.35f   // the idle sway kept while looking at someone
private const val AIM_HOLD = 3f      // the eyes stay on someone 3 to 6 s between two glances aside
private const val AIM_AWAY = 0.45f   // how long a glance aside lasts
private const val FOLLOW_YAW = 0.30f
private const val FOLLOW_PITCH = 0.16f
private const val DOUBLE_BLINK = 0.15f

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
    @Volatile var yawOverride: Float? = null
    @Volatile var pitchOverride: Float? = null
    @Volatile var rollOverride: Float? = null
    @Volatile var mouthOverride: Float? = null   // for looking at the mouth wide open (debug builds set it)
    @Volatile var browOverride: Float? = null    // for looking at the brows raised (debug builds set it)

    /** While a video plays: the eyes rest on it (down, in front) instead of on the user. */
    @Volatile var watching = false

    /**
     * Someone to look at, set from outside every frame (the face standing on a table in augmented reality, see ar/ArLook.kt), or null:
     * yaw and pitch the head turns by on top of its idle sway (which shrinks), then where the eyes look (each -1..1, as [gaze]).
     */
    @Volatile var aim: FloatArray? = null
    private var lookAwayUntil = -10f
    private var lookBackAt = 2f
    private var reactAt = -10f

    /** A short reaction: the brows go up and the head nods, once (a video that starts, or the face coming back after it). */
    fun react() { reactAt = time }
    var pitch = 0f; private set
    var roll = 0f; private set
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

    // a finger on the screen: where the eyes turn to follow it (-1..1), until when, and how far the head has turned towards it
    private val follow = FloatArray(2)
    private var followUntil = -10f
    private val turn = FloatArray(2)
    /** The eyes' height in head half-heights from the head's centre, downwards (the screen's way), to aim them from there. */
    private val eyeDrop: Float = if (mesh.eyeCentre.size >= 6) -0.5f * (mesh.eyeCentre[1] + mesh.eyeCentre[4]) else -0.2f

    /** The face is following a finger (or still looking where it lifted). */
    val following: Boolean get() = time < followUntil

    /** What the words being said show (see Expressions.kt): set from outside every frame, eased in [step]. */
    @Volatile var feeling: Feeling = Feeling.NEUTRAL
    /** The corners of the mouth: up in a smile (to 1), down when worried (negative). */
    var smile = 0f; private set
    /** The inner ends of the brows lifted (worry), 0..1. */
    var inner = 0f; private set
    /** The eyes opened wider than at rest (surprise), 0..1. */
    var widen = 0f; private set
    /** The lips kept a little apart (surprise), as a share of the jaw's travel. */
    var parted = 0f; private set
    private var feelingBrow = 0f
    private var tilt = 0f
    private var quiet = 10f

    /**
     * How much each hair vertex swings (0..1): a chosen hairstyle's own weights, or along the head's locks (their tips more than their
     * roots, the low ones more than those on top). Empty when the head has no hair that moves.
     */
    private val swayWeight: FloatArray = mesh.hairSway ?: FloatArray(mesh.vertexCount).also { w ->
        val rows = mesh.lockRows
        if (mesh.lockCount > 0 && rows > 1) for (l in 0 until mesh.lockCount) for (r in 0 until rows) for (k in 0..2) {
            val i = mesh.lockFirst + (l * rows + r) * 3 + k
            if (i >= mesh.vertexCount) continue
            val along = r / (rows - 1f)
            val y = mesh.verts[3 * i + 1]
            val low = ((0.35f - y) / 1.1f).coerceIn(0f, 1f)
            w[i] = 0.6f * along * along * (0.35f + 0.65f * low)
        }
    }
    private val swaying: IntArray = swayWeight.indices.filter { swayWeight[it] > 0.01f }.toIntArray()

    // the hair lags behind the head on a spring (a little overshoot, then it settles): its angles, and how fast they change
    private var hairYaw = 0f; private var hairYawV = 0f
    private var hairPitch = 0f; private var hairPitchV = 0f
    private var hairRoll = 0f; private var hairRollV = 0f

    private fun spring(pos: Float, vel: Float, target: Float, dt: Float): Pair<Float, Float> {
        val acc = (target - pos) * HAIR_STIFFNESS - vel * HAIR_DAMPING
        val v = vel + acc * dt
        return (pos + v * dt) to v
    }

    /** Posed vertices and normals of the last [pose] call. */
    val pv = FloatArray(mesh.verts.size)
    val pn = FloatArray(mesh.normals.size)

    /**
     * How much of the jaw's drop reaches a vertex, by how far across the mouth it sits: 1 at the middle, fading out over the
     * outer part towards each corner (computed once, from the rest pose, from how wide the lower lip actually is). Without it,
     * every jaw-weighted vertex swings by the same angle and an open mouth reads as a rectangle; a real jaw barely moves the
     * corners, which is what gives an open mouth its rounded, almond shape. Applied at render time (not baked into the mesh) so
     * it reaches every jaw vertex the same way, with no seam where two differently-weighted vertices could tear apart.
     */
    private val cornerFactor: FloatArray = run {
        val cx = mesh.lipCentre[0]
        var halfWidth = 0.01f
        for (i in mesh.mouthLower) halfWidth = max(halfWidth, abs(mesh.verts[3 * i] - cx))
        FloatArray(mesh.vertexCount) { i ->
            val d = abs(mesh.verts[3 * i] - cx) / halfWidth
            val t = ((d - 0.55f) / (1.05f - 0.55f)).coerceIn(0f, 1f)
            1f - t * t * (3f - 2f * t)
        }
    }

    /** Where the corners of the mouth are, per vertex: 0 at the middle of the lips, ±1 at and beyond each corner (the sign: the side). */
    private val cornerSide: FloatArray = run {
        val cx = mesh.lipCentre[0]
        var halfWidth = 0.01f
        for (i in mesh.mouthLower) halfWidth = max(halfWidth, abs(mesh.verts[3 * i] - cx))
        FloatArray(mesh.vertexCount) { i ->
            val d = (mesh.verts[3 * i] - cx) / halfWidth
            val t = ((abs(d) - 0.2f) / 0.8f).coerceIn(0f, 1f)
            (if (d < 0f) -1f else 1f) * t * t * (3f - 2f * t)
        }
    }

    /**
     * How much of a smile reaches a vertex: the lips' own weight on the skin, and as much as the lips for what is drawn inside the mouth
     * (the mouth line, the cavity and the teeth), which carries no lip weight but has to stay inside the lips.
     */
    private val smileWeight: FloatArray = run {
        val w = mesh.lips.copyOf()
        val eyeball = BooleanArray(mesh.vertexCount)
        for (e in mesh.eyeFirst.indices) for (i in mesh.eyeFirst[e] until (mesh.eyeFirst[e] + mesh.eyeCount[e]).coerceAtMost(mesh.vertexCount)) eyeball[i] = true
        val cx = mesh.lipCentre[0]; val cy = mesh.lipCentre[1]
        for (i in 0 until mesh.vertexCount) {
            if (w[i] != 0f || mesh.paint[i] == 0 || eyeball[i]) continue
            val dx = mesh.verts[3 * i] - cx; val dy = mesh.verts[3 * i + 1] - cy
            if (dx * dx + dy * dy < 0.35f * 0.35f) w[i] = MOUTH_INSIDE_WEIGHT
        }
        for (i in mesh.mouthUpper) w[i] = MOUTH_INSIDE_WEIGHT
        for (i in mesh.mouthLower) w[i] = MOUTH_INSIDE_WEIGHT
        w
    }

    /** The brows' inner ends: 1 near the middle of the face, falling to -0.35 at their outer ends (a worried brow slopes). */
    private val innerBrow: FloatArray = run {
        val mid = if (mesh.eyeCentre.size >= 6) 0.5f * (mesh.eyeCentre[0] + mesh.eyeCentre[3]) else mesh.lipCentre[0]
        FloatArray(mesh.vertexCount) { i ->
            val t = ((abs(mesh.verts[3 * i] - mid) - 0.06f) / 0.24f).coerceIn(0f, 1f)
            1f - 1.35f * t * t * (3f - 2f * t)
        }
    }

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
        // looking at someone, the head wanders much less: it keeps facing them
        val wander = if (aim != null) AIM_SWAY else 1f
        yaw = wander * (0.26f * sin(s * 0.31f) + 0.09f * sin(s * 0.73f + 1.3f))
        pitch = wander * (0.060f * sin(s * 0.23f + 0.7f) + 0.024f * sin(s * 0.61f))
        // A head that only turns and nods reads as a camera on a gimbal; real idle movement also tilts, off its own,
        // slower rhythm so the three never lock into a visibly repeating combination. Kept small — a few degrees at
        // most — since a head that visibly tips over looks drunk, not alive.
        roll = 0.045f * sin(s * 0.17f + 2.6f) + 0.018f * sin(s * 0.44f)

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
        // the head turns a little towards a finger
        val rt = rate(dt, 0.28f)
        for (i in 0..1) turn[i] += ((if (following && mood != Mood.ASLEEP) followTurn(i) else 0f) - turn[i]) * rt
        yaw += turn[0]; pitch += turn[1]
        aim?.let { yaw += it[0]; pitch += it[1] }
        yawOverride?.let { yaw = it }       // for looking at the head from a chosen side (debug builds set it)
        pitchOverride?.let { pitch = it }
        rollOverride?.let { roll = it }
        mouthOverride?.let { mouth = it }
        if (swaying.isNotEmpty()) {
            // in small steps: a spring stepped over a long frame overshoots wildly
            var left = dt
            while (left > 0f) {
                val h = min(left, 0.016f); left -= h
                spring(hairYaw, hairYawV, yaw, h).let { hairYaw = it.first; hairYawV = it.second }
                spring(hairPitch, hairPitchV, pitch, h).let { hairPitch = it.first; hairPitchV = it.second }
                spring(hairRoll, hairRollV, roll, h).let { hairRoll = it.first; hairRollV = it.second }
            }
        }

        // What the words show: held a moment after the voice stops, a warm face while listening, nothing asleep.
        quiet = if (live) 0f else quiet + dt
        var tg = expressionTargets(if (quiet < FEELING_HOLD) feeling else Feeling.NEUTRAL)
        if (mood == Mood.LISTENING && tg.smile == 0f) tg = tg.copy(smile = 0.12f)
        if (mood == Mood.ASLEEP) tg = ExpressionTargets()
        val rf = rate(dt, TAU_FEELING)
        smile += (tg.smile - smile) * rf
        inner += (tg.inner - inner) * rf
        widen += (tg.widen - widen) * rf
        parted += (tg.jaw - parted) * rf
        feelingBrow += (tg.brow - feelingBrow) * rf
        tilt += (tg.tilt - tilt) * rate(dt, 0.5f)
        roll += tilt
        rollOverride?.let { roll = it }

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

        val browT = 0.55f * ampSlow + 0.60f * expr + browBias + feelingBrow
        brow += (browT.coerceIn(-0.4f, 1.2f) - brow) * rate(dt, 0.15f)
        // a reaction: brows up and a small nod, over a little under a second
        val sinceReact = t - reactAt
        if (sinceReact in 0f..0.9f) {
            val bump = sin(3.1415927f * sinceReact / 0.9f)
            brow += 0.55f * bump
            pitch -= 0.07f * bump
        }
        browOverride?.let { brow = it }

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
            biasTgt[0] = 0f; biasTgt[1] = if (watching) -0.6f else 0f; biasAt = 0f
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
        // a finger wins over everything else, and the eyes rest on it with nothing added (not asleep: closed eyes follow nothing)
        val onFinger = following && !asleep
        if (onFinger) { gazeTgt[0] = follow[0]; gazeTgt[1] = follow[1]; gazeAt = t + 0.4f }
        // someone to look at: the eyes rest on them, with a glance aside now and then (a stare never broken reads as a doll)
        val who = aim
        if (who != null && t >= lookBackAt) { lookAwayUntil = t + AIM_AWAY; lookBackAt = t + AIM_HOLD + AIM_HOLD * random.nextFloat() }
        val onAim = who != null && !onFinger && !asleep && glance == null && t >= lookAwayUntil
        if (onAim && who != null) { gazeTgt[0] = who[2].coerceIn(-1f, 1f); gazeTgt[1] = who[3].coerceIn(-1f, 1f) }
        for (i in 0..1) gaze[i] += ((gazeTgt[i] + if (onFinger || onAim) 0f else gazeBias[i]).coerceIn(-1f, 1f) - gaze[i]) * rate(dt, 0.09f)

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
                // now and then two in a row, as people do
                blinkAt = if (random.nextFloat() < DOUBLE_BLINK) t + 0.28f else t + (if (thinking) 5.5f else 3.4f) + 3.1f * random.nextFloat()
            }
        }
    }

    /** Look deliberately somewhere for [hold] seconds (something appeared on screen), then wander again. */
    fun glance(dx: Float, dy: Float, hold: Float = 1.1f) {
        glance = floatArrayOf(dx.coerceIn(-1f, 1f), dy.coerceIn(-1f, 1f), time + max(0.1f, hold))
    }

    /** The head's turn towards the finger: yaw for [i] 0, pitch for 1, a share of where the eyes look. */
    private fun followTurn(i: Int): Float = follow[i] * (if (i == 0) FOLLOW_YAW else FOLLOW_PITCH)

    /**
     * A finger is on the screen at ([x], [y]) in head half-heights from the head's centre (x to the right, y down, as on the screen):
     * the eyes turn to it and the head a little, for as long as it stays and a moment after. A finger that just arrived catches the
     * eye: the face blinks.
     */
    fun follow(x: Float, y: Float) {
        val d = fingerGaze(x, y, eyeDrop)
        follow[0] = d[0]; follow[1] = d[1]
        if (!following && blink == 0f) blink = 1f
        followUntil = time + FOLLOW_HOLD
    }

    /** Applies the brow lift, lip spread or round, jaw drop and head rotation to the real geometry. */
    fun pose() {
        val n = mesh.vertexCount
        val v = pv
        System.arraycopy(mesh.verts, 0, v, 0, v.size)

        if (abs(brow) > 0.004f || inner > 0.004f) {
            val k = brow * BROW_LIFT
            val ki = inner * INNER_LIFT
            for (i in 0 until n) {
                val b = mesh.brow[i]
                if (b != 0f) v[3 * i + 1] += b * (k + ki * innerBrow[i])
            }
        }
        // The lids: the skin round each eye drops (upper lid) and rises (lower lid) as the eyes close; surprise opens them past rest.
        val close = (1f - ((1f - blink) * lids.coerceIn(0f, 1f)) - WIDEN_OPEN * widen * (1f - blink)).coerceIn(-WIDEN_OPEN, 0.97f)
        if (abs(close) > 0.01f) {
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
        if (abs(smile) > 0.004f) {
            // the corners of the mouth rise (and pull out, back into the cheeks) or drop; the middle of the lips stays
            val lift = smile * SMILE_LIFT
            val pull = max(0f, smile) * SMILE_PULL
            val back = abs(smile) * 0.012f
            for (i in 0 until n) {
                val w = smileWeight[i]
                if (w == 0f) continue
                val c = cornerSide[i]
                val a = abs(c) * w
                v[3 * i] += pull * c * w
                v[3 * i + 1] += lift * a
                v[3 * i + 2] -= back * a
            }
        }
        val open = max(mouth, parted)
        if (open > 0.004f) {
            val py = JAW_PIVOT[1]
            val pz = JAW_PIVOT[2]
            for (i in 0 until n) {
                val w = mesh.jaw[i]
                if (w == 0f) continue
                val ang = w * cornerFactor[i] * (open * JAW_MAX)
                val ca = cos(ang)
                val sa = sin(ang)
                val dy = v[3 * i + 1] - py
                val dz = v[3 * i + 2] - pz
                v[3 * i + 1] = py + dy * ca - dz * sa
                v[3 * i + 2] = pz + dy * sa + dz * ca
            }
        }
        if (swaying.isNotEmpty()) {
            // the hair hangs back from where the head has just turned: turned about the top of the skull by the lag, more at the tips,
            // with a slow breath of its own
            val lagY = (hairYaw - yaw).coerceIn(-0.35f, 0.35f) + 0.012f * sin(time * 1.3f)
            val lagP = (hairPitch - pitch).coerceIn(-0.3f, 0.3f)
            val lagR = (hairRoll - roll).coerceIn(-0.3f, 0.3f) + 0.008f * sin(time * 0.9f + 1f)
            for (i in swaying) {
                val w = swayWeight[i]
                val ax = lagP * w; val ay = lagY * w; val az = lagR * w
                val dx = v[3 * i] - HAIR_PIVOT_X; val dy = v[3 * i + 1] - HAIR_PIVOT_Y; val dz = v[3 * i + 2] - HAIR_PIVOT_Z
                // a small turn: d + (a x d)
                v[3 * i] += ay * dz - az * dy
                v[3 * i + 1] += az * dx - ax * dz
                v[3 * i + 2] += ax * dy - ay * dx
            }
        }
        val cy = cos(yaw); val sy = sin(yaw)
        val cp = cos(pitch); val sp = sin(pitch)
        val m00 = cy; val m01 = 0f; val m02 = sy
        val m10 = sp * sy; val m11 = cp; val m12 = -sp * cy
        val m20 = -cp * sy; val m21 = sp; val m22 = cp * cy
        // Roll (head tilt) is composed last, around the axis pointing out of the screen: it mixes the already
        // yaw/pitch-rotated x and y, leaving z (depth) alone — a simple 2D turn of the posed head in the viewing plane.
        val cr = cos(roll); val sr = sin(roll)
        for (i in 0 until n) {
            val x = v[3 * i]; val y = v[3 * i + 1]; val z = v[3 * i + 2]
            val rx = m00 * x + m01 * y + m02 * z
            val ry = m10 * x + m11 * y + m12 * z
            val rz = m20 * x + m21 * y + m22 * z
            v[3 * i] = rx * cr - ry * sr
            v[3 * i + 1] = rx * sr + ry * cr
            v[3 * i + 2] = rz
            val nx = mesh.normals[3 * i]; val ny = mesh.normals[3 * i + 1]; val nzv = mesh.normals[3 * i + 2]
            val rnx = m00 * nx + m01 * ny + m02 * nzv
            val rny = m10 * nx + m11 * ny + m12 * nzv
            val rnz = m20 * nx + m21 * ny + m22 * nzv
            pn[3 * i] = rnx * cr - rny * sr
            pn[3 * i + 1] = rnx * sr + rny * cr
            pn[3 * i + 2] = rnz
        }
    }
}

/**
 * Where the eyes look (each -1..1, as [HoloAvatar.gaze]) at a finger at ([x], [y]) in head half-heights from the head's centre (x right,
 * y down), the eyes being [eyeDrop] below it: the direction from the eyes to the finger, the screen sitting [FINGER_DEPTH] in front of
 * them, so a finger right over the face is looked at almost straight on and one far off at the eyes' full reach.
 */
internal fun fingerGaze(x: Float, y: Float, eyeDrop: Float): FloatArray {
    val dy = y - eyeDrop
    val len = kotlin.math.sqrt(x * x + dy * dy + FINGER_DEPTH * FINGER_DEPTH)
    return floatArrayOf((1.6f * x / len).coerceIn(-1f, 1f), (1.6f * dy / len).coerceIn(-1f, 1f))
}
