package com.jarvis.android.ar

import com.jarvis.android.avatar.PolygonLevel
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Haseo's body for the table: a sculpted adult man's body (assets/avatar/body_mesh.bin, see [BodyModel]) about seven and a half heads
 * tall in a bodysuit, in the head's own units: the head's half-height is 1, its centre at the origin, +y up, +z to the front, +x to
 * the right of someone facing it; the soles stand at y = [BODY_FEET]. Its own head is cut off under the app's, which is drawn over it.
 * It has as many triangles as the head at each polygon level and is shaded per vertex as its skin is (see bodyDrawList), so the body
 * is the same kind of mesh as the head on top of it.
 * It breathes, shifts its weight now and then, and moves its arms while the voice speaks: the sculpt stands with its arms out (an
 * A-pose), and each frame turns them down to the sides at the shoulders and the elbows, the skin round the joints following both bones.
 */
internal class ArBody(private val model: BodyModel) {
    var time = 0f
        private set
    /** 0..1: how much the arms are talking, eased from the voice's loudness. */
    var talk = 0f
        private set
    private val meshes = HashMap<Int, BodyMesh>()

    /** Moves the animation on by [dt] seconds, the voice at [level] (0..1). */
    fun step(dt: Float, level: Float) {
        val d = dt.coerceIn(0f, 0.2f)
        time += d
        val target = (level * 2.2f).coerceIn(0f, 1f)
        talk += (target - talk) * (1f - exp(-d / if (target > talk) TALK_ATTACK else TALK_RELEASE))
    }

    /** The body in its pose at the current time, cut for [level]. The mesh's arrays are overwritten by the next call. */
    fun build(level: PolygonLevel = PolygonLevel.MEDIUM): BodyMesh {
        val index = BodyModel.levelIndex(level)
        val shape = model.levels[index]
        val mesh = meshes.getOrPut(index) { shape.newMesh() }
        pose(shape, mesh)
        return mesh
    }

    private fun pose(shape: BodyModel.Level, mesh: BodyMesh) {
        val t = time
        val breath = sin(t * 1.5f)
        // the weight shifting from one leg to the other, slowly: the upper body sways a little over the hips
        val shift = 0.06f * sin(t * 0.35f)
        // per side: the turn of the upper arm at the shoulder (r1) and of the forearm at the elbow after it (r2 · r1)
        val r1 = Array(2) { FloatArray(9) }; val r21 = Array(2) { FloatArray(9) }; val elbow = Array(2) { FloatArray(3) }
        for (k in 0..1) {
            val s = if (k == 0) 1f else -1f
            val j = model.joints[k]
            val lead = if (s > 0f) 1f else 0.35f
            val wave = sin(t * (2.1f + 0.4f * s) + s)
            val abduct = REST_ABDUCT + 0.02f * sin(t * 0.7f + s) + talk * lead * 0.16f
            val flex = 0.05f + talk * lead * (0.32f + 0.14f * wave)
            val bend = 0.22f + talk * lead * (0.85f + 0.30f * sin(t * 3.3f + 2f * s))
            val upper0 = floatArrayOf(j[3] - j[0], j[4] - j[1], j[5] - j[2])
            val fore0 = floatArrayOf(j[6] - j[3], j[7] - j[4], j[8] - j[5])
            turn(upper0, limbDirection(s, abduct, flex), r1[k])
            val e = apply(r1[k], upper0)
            elbow[k][0] = j[0] + e[0]; elbow[k][1] = j[1] + e[1]; elbow[k][2] = j[2] + e[2]
            val r2 = FloatArray(9)
            turn(apply(r1[k], fore0), limbDirection(s, abduct * 0.5f, flex + bend), r2)
            times(r2, r1[k], r21[k])
        }
        val rest = shape.pos; val restN = shape.nrm
        val pos = mesh.pos; val nrm = mesh.nrm
        for (i in 0 until shape.vertexCount) {
            var x = rest[3 * i]; var y = rest[3 * i + 1]; var z = rest[3 * i + 2]
            var nx = restN[3 * i]; var ny = restN[3 * i + 1]; var nz = restN[3 * i + 2]
            val arm = shape.arm[i]
            if (arm > 0f) {
                val k = if (shape.side[i] > 0) 0 else 1
                val j = model.joints[k]
                val fore = shape.fore[i]
                // with the upper arm: about the shoulder
                val ux = x - j[0]; val uy = y - j[1]; val uz = z - j[2]
                val a = r1[k]
                val upX = j[0] + a[0] * ux + a[1] * uy + a[2] * uz
                val upY = j[1] + a[3] * ux + a[4] * uy + a[5] * uz
                val upZ = j[2] + a[6] * ux + a[7] * uy + a[8] * uz
                // with the forearm: about the elbow, wherever the upper arm took it
                val fx = x - j[3]; val fy = y - j[4]; val fz = z - j[5]
                val b = r21[k]; val e = elbow[k]
                val foX = e[0] + b[0] * fx + b[1] * fy + b[2] * fz
                val foY = e[1] + b[3] * fx + b[4] * fy + b[5] * fz
                val foZ = e[2] + b[6] * fx + b[7] * fy + b[8] * fz
                val wu = arm * (1f - fore); val wf = arm * fore; val w0 = 1f - arm
                x = w0 * x + wu * upX + wf * foX; y = w0 * y + wu * upY + wf * foY; z = w0 * z + wu * upZ + wf * foZ
                val anx = a[0] * nx + a[1] * ny + a[2] * nz; val any = a[3] * nx + a[4] * ny + a[5] * nz; val anz = a[6] * nx + a[7] * ny + a[8] * nz
                val bnx = b[0] * nx + b[1] * ny + b[2] * nz; val bny = b[3] * nx + b[4] * ny + b[5] * nz; val bnz = b[6] * nx + b[7] * ny + b[8] * nz
                nx = w0 * nx + wu * anx + wf * bnx; ny = w0 * ny + wu * any + wf * bny; nz = w0 * nz + wu * anz + wf * bnz
                val l = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-9f)
                nx /= l; ny /= l; nz /= l
            }
            // breathing: the chest and the shoulders over it widen and rise a little
            val chest = smooth(CHEST_LOW, CHEST_MID, rest[3 * i + 1]) * (1f - smooth(CHEST_MID, CHEST_HIGH, rest[3 * i + 1]))
            val grow = 1f + 0.022f * breath * chest
            x *= grow
            z = BREATH_AXIS_Z + (z - BREATH_AXIS_Z) * grow
            y += 0.03f * breath * smooth(CHEST_LOW, CHEST_HIGH, rest[3 * i + 1])
            // the sway: nothing at the feet, all of it from the hips up
            x += shift * smooth(BODY_FEET, HIPS, rest[3 * i + 1])
            pos[3 * i] = x; pos[3 * i + 1] = y; pos[3 * i + 2] = z
            nrm[3 * i] = nx; nrm[3 * i + 1] = ny; nrm[3 * i + 2] = nz
        }
    }

    companion object {
        const val TALK_ATTACK = 0.12f
        const val TALK_RELEASE = 0.7f
        /** How far out from the sides the arms hang at rest (radians): clear of the sculpt's broad back and hips. */
        const val REST_ABDUCT = 0.17f
        // where the chest breathes, in the head's units (from the waist up through the chest to the collar), and about which depth
        private const val CHEST_LOW = -4.7f
        private const val CHEST_MID = -3.0f
        private const val CHEST_HIGH = -1.6f
        private const val BREATH_AXIS_Z = -0.4f
        private const val HIPS = -6.0f

        /** Down, swung out to the side [s] by [abduct] and forwards by [flex] (radians). */
        fun limbDirection(s: Float, abduct: Float, flex: Float): FloatArray =
            floatArrayOf(s * sin(abduct), -cos(abduct) * cos(flex), cos(abduct) * sin(flex))

        private fun smooth(e0: Float, e1: Float, x: Float): Float {
            val u = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
            return u * u * (3f - 2f * u)
        }

        /** The shortest turn taking direction [from] to [to], into [out] (3 × 3, row-major). */
        private fun turn(from: FloatArray, to: FloatArray, out: FloatArray) {
            val a = normalise(from); val b = normalise(to)
            val v = cross(a, b)
            val s = length(v); val c = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
            out.fill(0f); out[0] = 1f; out[4] = 1f; out[8] = 1f
            if (s < 1e-6f) return
            val kx = v[0] / s; val ky = v[1] / s; val kz = v[2] / s
            val q = 1f - c
            out[0] = c + kx * kx * q; out[1] = kx * ky * q - kz * s; out[2] = kx * kz * q + ky * s
            out[3] = ky * kx * q + kz * s; out[4] = c + ky * ky * q; out[5] = ky * kz * q - kx * s
            out[6] = kz * kx * q - ky * s; out[7] = kz * ky * q + kx * s; out[8] = c + kz * kz * q
        }

        private fun apply(m: FloatArray, v: FloatArray) = floatArrayOf(
            m[0] * v[0] + m[1] * v[1] + m[2] * v[2], m[3] * v[0] + m[4] * v[1] + m[5] * v[2], m[6] * v[0] + m[7] * v[1] + m[8] * v[2],
        )

        private fun times(a: FloatArray, b: FloatArray, out: FloatArray) {
            for (r in 0..2) for (c in 0..2) out[3 * r + c] = a[3 * r] * b[c] + a[3 * r + 1] * b[3 + c] + a[3 * r + 2] * b[6 + c]
        }
    }
}

/**
 * The sculpted body, as tools/avatar/export_body.py wrote it: per polygon level its rest pose (arms out), what each vertex is made of,
 * how much it follows the upper arm and the forearm, its triangles and the web of the dark look; and each side's shoulder, elbow
 * and wrist ([joints], left side first: x > 0).
 */
internal class BodyModel(val levels: List<Level>, val joints: List<FloatArray>) {
    class Level(
        val pos: FloatArray, val nrm: FloatArray, val part: IntArray, val trim: FloatArray, val bare: FloatArray,
        val side: IntArray, val arm: FloatArray, val fore: FloatArray,
        val tris: IntArray, val webA: IntArray, val webB: IntArray, val webNode: IntArray,
    ) {
        val vertexCount: Int get() = pos.size / 3

        /** A mesh to pose this level into: its own positions and normals, the rest shared. */
        fun newMesh() = BodyMesh(pos.copyOf(), nrm.copyOf(), part, trim, bare, tris, webA, webB, webNode)
    }

    companion object {
        /** Eco, Léger, Standard, Haute définition; Ultra is Haute définition (what it adds on the head is the hair's own triangles). */
        fun levelIndex(level: PolygonLevel) = when (level) {
            PolygonLevel.ECO -> 0
            PolygonLevel.LOW -> 1
            PolygonLevel.MEDIUM -> 2
            PolygonLevel.HIGH, PolygonLevel.ULTRA -> 3
        }

        fun parse(bytes: ByteArray): BodyModel {
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            require(b.get() == 'J'.code.toByte() && b.get() == 'H'.code.toByte() && b.get() == 'B'.code.toByte() && b.get() == '2'.code.toByte()) { "not a body mesh" }
            val count = b.int
            val joints = List(2) { FloatArray(9) { b.float } }
            val levels = List(count) {
                val v = b.int; val t = b.int
                val pos = FloatArray(v * 3) { b.float }
                val nrm = FloatArray(v * 3)
                for (i in 0 until v) {
                    val x = b.get() / 127f; val y = b.get() / 127f; val z = b.get() / 127f
                    val l = sqrt(x * x + y * y + z * z).coerceAtLeast(1e-6f)
                    nrm[3 * i] = x / l; nrm[3 * i + 1] = y / l; nrm[3 * i + 2] = z / l
                }
                val part = IntArray(v) { b.get().toInt() and 0xFF }
                val trim = FloatArray(v) { (b.get().toInt() and 0xFF) / 255f }
                val bare = FloatArray(v) { (b.get().toInt() and 0xFF) / 255f }
                val side = IntArray(v) { b.get().toInt() }
                val arm = FloatArray(v) { (b.get().toInt() and 0xFF) / 255f }
                val fore = FloatArray(v) { (b.get().toInt() and 0xFF) / 255f }
                val tris = IntArray(t * 3) { b.short.toInt() and 0xFFFF }
                val webA = IntArray(t) { -1 }; val webB = IntArray(t) { -1 }; val webNode = IntArray(t) { -1 }
                repeat(b.int) { val tri = b.int; webA[tri] = b.short.toInt() and 0xFFFF; webB[tri] = b.short.toInt() and 0xFFFF }
                repeat(b.int) { val tri = b.int; webNode[tri] = b.short.toInt() and 0xFFFF }
                Level(pos, nrm, part, trim, bare, side, arm, fore, tris, webA, webB, webNode)
            }
            return BodyModel(levels, joints)
        }
    }
}

/** Where the soles are, in head half-heights below the head's centre: the figure is about seven and a half heads tall. */
internal const val BODY_FEET = -13.8f

// the garment under each vertex (see bodyVertexColour); the collar, the cuffs and the bare skin are blended over them
internal const val JACKET = 0
internal const val TROUSERS = 2
internal const val BELT = 3
internal const val SHOES = 4

/**
 * The surface: [pos] x, y, z and [nrm] the smooth outward normal per vertex, per vertex the garment under it ([part]) with how much
 * of the collar or cuff colour ([trim]) and of bare skin ([bare]) shows over it, [tris] three vertex indices each
 * (counter-clockwise seen from outside). The web of the dark look: [webA]-[webB] is the stretch of it a triangle carries (-1: none),
 * [webNode] its node (-1: none).
 */
internal class BodyMesh(
    val pos: FloatArray, val nrm: FloatArray, val part: IntArray,
    val trim: FloatArray, val bare: FloatArray, val tris: IntArray,
    val webA: IntArray = IntArray(tris.size / 3) { -1 }, val webB: IntArray = IntArray(tris.size / 3) { -1 },
    val webNode: IntArray = IntArray(tris.size / 3) { -1 },
) {
    val triangleCount: Int get() = tris.size / 3
    val vertexCount: Int get() = pos.size / 3
}

internal fun cross(a: FloatArray, b: FloatArray) = floatArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
internal fun length(a: FloatArray) = sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2])
internal fun normalise(a: FloatArray): FloatArray { val l = length(a).coerceAtLeast(1e-9f); return floatArrayOf(a[0] / l, a[1] / l, a[2] / l) }
