package com.jarvis.android.ar

import com.jarvis.android.avatar.PolygonLevel
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The little chrome robot that stands on the table in augmented reality (assets/avatar/robot_mesh.bin, see [RobotModel]), in its own
 * units: 2 tall, its soles at y = -1 and the top of its head at +1, +z its front, +x its left. It is one rigid-looking surface moved
 * in parts, as a robot is: its body breathes and bobs on its legs, its head turns to whoever looks at it and tilts, its arms swing and
 * talk while the voice speaks, it waves when it is put down, it steps from one foot to the other while it turns, and its eyes glow
 * with the voice and blink now and then.
 */
internal class ArRobot(private val model: RobotModel) {
    var time = 0f
        private set
    /** 0..1: how much it is talking, eased from the voice's loudness. */
    var talk = 0f
        private set
    /** 0..1: how lit its eyes are (they go out for a blink). */
    var eyes = 1f
        private set
    /** Seconds left of the wave it gives when it is put down. */
    var waving = WAVE_TIME
        private set
    private var stepping = 0f
    private var stepPhase = 0f
    private var headYaw = 0f
    private var headPitch = 0f
    private var nextBlink = 2.5f
    private var blinkLeft = 0f
    private var blinks = 0
    private val meshes = HashMap<Int, RobotMesh>()

    /** Put down somewhere else: it waves again. */
    fun greet() { waving = WAVE_TIME }

    /**
     * Moves it on by [dt] seconds, the voice at [level] (0..1), its body turning by [turnRate] radians a second, its head asked to
     * turn by [yaw] (to its left: positive) and to look up by [pitch] (radians, from its body's front).
     */
    fun step(dt: Float, level: Float, turnRate: Float = 0f, yaw: Float = 0f, pitch: Float = 0f) {
        val d = dt.coerceIn(0f, 0.2f)
        time += d
        val target = (level * 2.2f).coerceIn(0f, 1f)
        talk += (target - talk) * (1f - exp(-d / if (target > talk) TALK_ATTACK else TALK_RELEASE))
        waving = (waving - d).coerceAtLeast(0f)
        // it steps while its body turns, and settles once it stops
        val wantStep = (abs(turnRate) / STEP_TURN).coerceIn(0f, 1f)
        stepping += (wantStep - stepping) * (1f - exp(-d / 0.25f))
        stepPhase += d * STEP_RATE * stepping
        // the head follows where it is asked to look, quick but not at once
        val k = 1f - exp(-d / HEAD_TAU)
        headYaw += (yaw.coerceIn(-MAX_YAW, MAX_YAW) - headYaw) * k
        headPitch += (pitch.coerceIn(-MAX_PITCH, MAX_PITCH) - headPitch) * k
        // blinks: every few seconds, the eyes go out for a moment (sometimes twice)
        if (blinkLeft > 0f) {
            blinkLeft -= d
            if (blinkLeft <= 0f) {
                blinkLeft = 0f
                blinks++
                nextBlink = if (blinks % 4 == 1) 0.25f else 2.6f + 2.4f * (0.5f + 0.5f * sin(blinks * 2.3f))
            }
        } else {
            nextBlink -= d
            if (nextBlink <= 0f) blinkLeft = BLINK_TIME
        }
        eyes = if (blinkLeft > 0f) {
            val u = 1f - blinkLeft / BLINK_TIME
            abs(2f * u - 1f).let { it * it }
        } else 1f
    }

    /** The robot in its pose at the current time, cut for [level]. The mesh's arrays are overwritten by the next call. */
    fun build(level: PolygonLevel = PolygonLevel.MEDIUM): RobotMesh {
        val index = RobotModel.levelIndex(level)
        val shape = model.levels[index]
        val mesh = meshes.getOrPut(index) { shape.newMesh() }
        pose(shape, mesh)
        mesh.eyes = eyes
        mesh.voice = talk
        return mesh
    }

    private fun pose(shape: RobotModel.Level, mesh: RobotMesh) {
        val t = time
        // the body over its hips: it breathes, bobs on its knees, rocks a little from side to side, bounces while it talks, and dips
        // with each step
        val hips = model.hips
        val step = sin(stepPhase)
        val bob = 0.012f * sin(t * 1.9f) + 0.035f * talk * abs(sin(t * 5.5f)) - 0.03f * stepping * abs(step)
        val body = Joint(hips, rotZ(0.035f * sin(t * 0.8f) + 0.05f * stepping * step) * rotX(0.02f * sin(t * 1.9f) - 0.04f * talk), 0f, bob, 0f)
        // the head: where it is asked to look, a curious tilt now and then, and nods while it talks
        val tilt = 0.07f * sin(t * 0.45f) * (0.5f + 0.5f * sin(t * 0.17f))
        val nod = 0.07f * talk * sin(t * 6.3f)
        val head = body * Joint(model.neck, rotY(headYaw) * rotX(-headPitch - nod) * rotZ(tilt))
        // the arms at the shoulders: swinging gently, talking with the voice (the right one leads), and the right one waving hello
        val wave = (waving / WAVE_TIME).let { if (it <= 0f) 0f else smooth01(0f, 0.25f, it) * smooth01(1f, 0.85f, it) }
        val arms = Array(2) { k ->
            val s = if (k == 0) 1f else -1f
            val lead = if (s < 0f) 1f else 0.4f
            var swing = 0.07f * sin(t * 1.3f + s * 1.5f) + talk * lead * (0.75f + 0.30f * sin(t * 3.1f + s))
            var out = 0.05f + 0.02f * sin(t * 0.9f + s) + talk * lead * (0.35f + 0.20f * sin(t * 4.2f + 2f * s))
            if (s < 0f && wave > 0f) {
                // up beside the head, swinging from side to side
                out += wave * (2.35f - out + 0.30f * sin(t * 11f))
                swing *= 1f - wave
            }
            body * Joint(model.shoulders[k], rotZ(s * out) * rotX(-swing))
        }
        // the legs at the hips: a foot lifts in turn while it steps; otherwise they stand
        val legs = Array(2) { k ->
            val lift = stepping * max(0f, if (k == 0) step else -step)
            Joint(model.hipJoints[k], rotX(0.12f * lift), 0f, 0.09f * lift, 0.02f * lift)
        }
        val rest = shape.pos; val restN = shape.nrm
        val pos = mesh.pos; val nrm = mesh.nrm
        val p = FloatArray(3); val q = FloatArray(3); val n = FloatArray(3); val m = FloatArray(3)
        for (i in 0 until shape.vertexCount) {
            val x = rest[3 * i]; val y = rest[3 * i + 1]; val z = rest[3 * i + 2]
            val nx = restN[3 * i]; val ny = restN[3 * i + 1]; val nz = restN[3 * i + 2]
            body.apply(x, y, z, p); body.turn(nx, ny, nz, n)
            val w = shape.weight[i]
            if (w > 0f) {
                val side = if (shape.side[i] < 0) 1 else 0
                val j = when (shape.part[i]) {
                    HEAD -> head
                    ARM -> arms[side]
                    LEG -> legs[side]
                    else -> body
                }
                j.apply(x, y, z, q); j.turn(nx, ny, nz, m)
                for (c in 0..2) { p[c] += (q[c] - p[c]) * w; n[c] += (m[c] - n[c]) * w }
                val l = sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]).coerceAtLeast(1e-9f)
                n[0] /= l; n[1] /= l; n[2] /= l
            }
            pos[3 * i] = p[0]; pos[3 * i + 1] = p[1]; pos[3 * i + 2] = p[2]
            nrm[3 * i] = n[0]; nrm[3 * i + 1] = n[1]; nrm[3 * i + 2] = n[2]
        }
    }

    companion object {
        const val TALK_ATTACK = 0.12f
        const val TALK_RELEASE = 0.7f
        const val HEAD_TAU = 0.22f
        const val MAX_YAW = 1.1f
        const val MAX_PITCH = 0.5f
        const val WAVE_TIME = 2.4f
        const val BLINK_TIME = 0.16f
        /** The body's turn (radians a second) at which it steps fully, and how fast its steps go then. */
        const val STEP_TURN = 0.6f
        const val STEP_RATE = 9f

        private fun smooth01(e0: Float, e1: Float, x: Float): Float {
            val u = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
            return u * u * (3f - 2f * u)
        }
    }
}

/** A 3 × 3 rotation (row-major). */
internal class Rot(val m: FloatArray) {
    operator fun times(o: Rot): Rot {
        val a = m; val b = o.m
        return Rot(FloatArray(9) { val r = it / 3; val c = it % 3; a[3 * r] * b[c] + a[3 * r + 1] * b[3 + c] + a[3 * r + 2] * b[6 + c] })
    }
}

internal fun rotX(a: Float): Rot { val c = cos(a); val s = sin(a); return Rot(floatArrayOf(1f, 0f, 0f, 0f, c, -s, 0f, s, c)) }
internal fun rotY(a: Float): Rot { val c = cos(a); val s = sin(a); return Rot(floatArrayOf(c, 0f, s, 0f, 1f, 0f, -s, 0f, c)) }
internal fun rotZ(a: Float): Rot { val c = cos(a); val s = sin(a); return Rot(floatArrayOf(c, -s, 0f, s, c, 0f, 0f, 0f, 1f)) }

/**
 * A move of the robot's parts: turned by [r] about [pivot], then moved by ([dx], [dy], [dz]); [a] * [b] is [b] then [a], as a child's
 * joint goes with its parent's.
 */
internal class Joint(pivot: FloatArray, r: Rot, dx: Float = 0f, dy: Float = 0f, dz: Float = 0f) {
    // p' = M p + T
    val mat: FloatArray = r.m.copyOf()
    val off: FloatArray = FloatArray(3)

    init {
        for (k in 0..2) off[k] = pivot[k] - (mat[3 * k] * pivot[0] + mat[3 * k + 1] * pivot[1] + mat[3 * k + 2] * pivot[2])
        off[0] += dx; off[1] += dy; off[2] += dz
    }

    private constructor(mat: FloatArray, off: FloatArray) : this(floatArrayOf(0f, 0f, 0f), Rot(mat)) {
        off.copyInto(this.off)
    }

    operator fun times(o: Joint): Joint {
        val a = mat; val b = o.mat
        val mm = FloatArray(9) { val r = it / 3; val c = it % 3; a[3 * r] * b[c] + a[3 * r + 1] * b[3 + c] + a[3 * r + 2] * b[6 + c] }
        val t = FloatArray(3) { k -> a[3 * k] * o.off[0] + a[3 * k + 1] * o.off[1] + a[3 * k + 2] * o.off[2] + off[k] }
        return Joint(mm, t)
    }

    fun apply(x: Float, y: Float, z: Float, out: FloatArray) {
        out[0] = mat[0] * x + mat[1] * y + mat[2] * z + off[0]
        out[1] = mat[3] * x + mat[4] * y + mat[5] * z + off[1]
        out[2] = mat[6] * x + mat[7] * y + mat[8] * z + off[2]
    }

    fun turn(x: Float, y: Float, z: Float, out: FloatArray) {
        out[0] = mat[0] * x + mat[1] * y + mat[2] * z
        out[1] = mat[3] * x + mat[4] * y + mat[5] * z
        out[2] = mat[6] * x + mat[7] * y + mat[8] * z
    }
}

/**
 * The robot, as tools/avatar/export_robot.py wrote it: per polygon level its rest pose, its colour baked from its texture, how much
 * each vertex glows (its eyes and green trim) and is an eye's light, which part moves it (see [HEAD]) on which side and how much; and
 * its joints: the [neck], the [shoulders] and the hips ([hipJoints]), left first (x > 0).
 */
internal class RobotModel(val levels: List<Level>, val neck: FloatArray, val shoulders: List<FloatArray>, val hipJoints: List<FloatArray>) {
    /** Between the hips: what the body rocks about. */
    val hips: FloatArray = floatArrayOf(0f, (hipJoints[0][1] + hipJoints[1][1]) / 2f, (hipJoints[0][2] + hipJoints[1][2]) / 2f)

    class Level(
        val pos: FloatArray, val nrm: FloatArray, val colour: IntArray, val glow: FloatArray, val eye: FloatArray,
        val part: IntArray, val side: IntArray, val weight: FloatArray, val tris: IntArray,
    ) {
        val vertexCount: Int get() = pos.size / 3

        /** A mesh to pose this level into: its own positions and normals, the rest shared. */
        fun newMesh() = RobotMesh(pos.copyOf(), nrm.copyOf(), colour, glow, eye, tris)
    }

    companion object {
        /** Eco, Léger, Standard, Haute définition; Ultra is Haute définition. */
        fun levelIndex(level: PolygonLevel) = when (level) {
            PolygonLevel.ECO -> 0
            PolygonLevel.LOW -> 1
            PolygonLevel.MEDIUM -> 2
            PolygonLevel.HIGH, PolygonLevel.ULTRA -> 3
        }

        fun parse(bytes: ByteArray): RobotModel {
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            require(b.get() == 'J'.code.toByte() && b.get() == 'R'.code.toByte() && b.get() == 'B'.code.toByte() && b.get() == '1'.code.toByte()) { "not a robot mesh" }
            val count = b.int
            val joints = List(5) { FloatArray(3) { b.float } }
            val levels = List(count) {
                val v = b.int; val t = b.int
                val pos = FloatArray(v * 3) { b.float }
                val nrm = FloatArray(v * 3)
                for (i in 0 until v) {
                    val x = b.get() / 127f; val y = b.get() / 127f; val z = b.get() / 127f
                    val l = sqrt(x * x + y * y + z * z).coerceAtLeast(1e-6f)
                    nrm[3 * i] = x / l; nrm[3 * i + 1] = y / l; nrm[3 * i + 2] = z / l
                }
                val colour = IntArray(v) {
                    val r = b.get().toInt() and 0xFF; val g = b.get().toInt() and 0xFF; val bl = b.get().toInt() and 0xFF
                    (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
                }
                val glow = FloatArray(v) { (b.get().toInt() and 0xFF) / 255f }
                val eye = FloatArray(v) { (b.get().toInt() and 0xFF) / 255f }
                val part = IntArray(v) { b.get().toInt() and 0xFF }
                val side = IntArray(v) { b.get().toInt() }
                val weight = FloatArray(v) { (b.get().toInt() and 0xFF) / 255f }
                val tris = IntArray(t * 3) { b.short.toInt() and 0xFFFF }
                Level(pos, nrm, colour, glow, eye, part, side, weight, tris)
            }
            return RobotModel(levels, joints[0], listOf(joints[1], joints[2]), listOf(joints[3], joints[4]))
        }
    }
}

// which part moves a vertex (see RobotModel)
internal const val BODY = 0
internal const val HEAD = 1
internal const val ARM = 2
internal const val LEG = 3

/** Where the soles are, in the robot's units. */
internal const val ROBOT_FEET = -1f

/** Metres per robot unit on the table: a robot 24 cm tall. */
internal const val ROBOT_UNIT = 0.12f

/**
 * The surface posed: [pos] x, y, z and [nrm] the smooth outward normal per vertex, its [colour], how much it [glow]s and is an
 * [eye]'s light, [tris] three vertex indices each (counter-clockwise seen from outside); how lit its [eyes] are and how much its
 * [voice] makes it glow this frame.
 */
internal class RobotMesh(
    val pos: FloatArray, val nrm: FloatArray, val colour: IntArray, val glow: FloatArray, val eye: FloatArray, val tris: IntArray,
) {
    var eyes = 1f
    var voice = 0f
    val triangleCount: Int get() = tris.size / 3
    val vertexCount: Int get() = pos.size / 3
}

internal fun cross(a: FloatArray, b: FloatArray) = floatArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
internal fun length(a: FloatArray) = sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2])
internal fun normalise(a: FloatArray): FloatArray { val l = length(a).coerceAtLeast(1e-9f); return floatArrayOf(a[0] / l, a[1] / l, a[2] / l) }

