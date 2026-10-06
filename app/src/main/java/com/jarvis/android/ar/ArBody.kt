package com.jarvis.android.ar

import com.jarvis.android.avatar.PolygonLevel
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Haseo's body for the table: a figure about seven heads tall, an adult's proportions, in the head's own units: the head's half-height is 1, its centre
 * at the origin, +y up, +z to the front, +x to the right of someone facing it; the soles stand at y = [BODY_FEET].
 * Each limb, the torso with the neck, the shoes and the hands is one smooth surface swept along a curve through its joints (a ring of
 * points every [bodySpacing], closed by round ends), cut as finely as the head is at the chosen polygon level and shaded per vertex
 * as its skin is (see bodyDrawList), so the body is the same kind of mesh as the head on top of it.
 * It breathes, shifts its weight now and then, and moves its arms while the voice speaks.
 */
internal class ArBody {
    var time = 0f
        private set
    /** 0..1: how much the arms are talking, eased from the voice's loudness. */
    var talk = 0f
        private set
    private var shape: BodyShape? = null

    /** Moves the animation on by [dt] seconds, the voice at [level] (0..1). */
    fun step(dt: Float, level: Float) {
        val d = dt.coerceIn(0f, 0.2f)
        time += d
        val target = (level * 2.2f).coerceIn(0f, 1f)
        talk += (target - talk) * (1f - exp(-d / if (target > talk) TALK_ATTACK else TALK_RELEASE))
    }

    /** The body in its pose at the current time, cut for [level]. The mesh's arrays are overwritten by the next call. */
    fun build(level: PolygonLevel = PolygonLevel.MEDIUM): BodyMesh {
        val parts = pose()
        val s = shape?.takeIf { it.level == level } ?: BodyShape(level, parts).also { shape = it }
        s.place(parts)
        return s.mesh
    }

    /** Every shape in its pose: a path of keys from one end to the other, the surface passing smoothly through them. */
    fun pose(): List<List<Key>> {
        val t = time
        val breath = sin(t * 1.5f)
        // the weight shifting from one leg to the other, slowly: the whole upper body sways a little
        val shift = 0.06f * sin(t * 0.35f)
        val chest = 1f + 0.022f * breath
        val lift = 0.03f * breath
        val parts = ArrayList<List<Key>>()

        // the torso from the crotch to the neck, as an adult's: trousers closing between the thighs, the hips, a belt at the top of
        // them, the waist, the chest, the jacket broad at the shoulders and sloping up over them to a crew collar of the theme's
        // colour, then the neck. Over the camera the head's own neck is not drawn: this one is the neck, leaning forward a little as
        // a real one, and it ends inside the head (its keys are in the head's units, the rest of the body set back by BODY_BACK under
        // it), where the head drawn over it hides its top whichever way the head turns
        parts += listOf(
            Key(shift, -6.60f, -0.02f, 0.46f, 0.36f, TROUSERS),
            Key(shift, -6.40f, -0.02f, 1.04f, 0.62f, TROUSERS),
            Key(shift, -6.00f, -0.04f, 1.24f, 0.72f, TROUSERS),
            Key(shift, -5.40f, -0.02f, 1.15f, 0.68f, TROUSERS),
            Key(shift, -5.05f, 0f, 1.10f, 0.66f, BELT),
            Key(shift, -4.80f, 0f, 1.06f, 0.64f, JACKET),
            Key(shift, -4.25f, 0f, 1.02f, 0.62f, JACKET),
            Key(shift, -3.50f, 0.03f, 1.12f, 0.68f, JACKET),
            Key(shift, -2.85f, 0.05f, 1.24f * chest, 0.74f * chest, JACKET),
            Key(shift, -2.25f + lift, 0.02f, 1.34f * chest, 0.68f * chest, JACKET),
            Key(shift, -1.90f + lift, -0.03f, 1.20f, 0.58f, JACKET),
            Key(shift * 0.8f, -1.70f + lift, -0.06f, 0.90f, 0.53f, JACKET),
            Key(shift * 0.6f, -1.59f + lift, -0.08f, 0.58f, 0.52f, TRIM),
        ).map { it.back() } + listOf(
            Key(shift * 0.4f, -1.50f + lift, -0.60f, 0.49f, 0.48f, SKIN),
            Key(shift * 0.2f, -1.30f, -0.56f, 0.44f, 0.46f, SKIN),
            Key(0f, -1.12f, -0.50f, 0.43f, 0.47f, SKIN),
            Key(0f, -0.92f, -0.46f, 0.44f, 0.52f, SKIN),
            Key(0f, -0.72f, -0.44f, 0.42f, 0.50f, SKIN),
            Key(0f, -0.58f, -0.40f, 0.30f, 0.34f, SKIN),
        )

        // the legs: a little apart and closing in towards the ankles, the thigh full at the top, the knee, the calf rounder at the
        // back; the shoes from the heel to the toe, flat
        for (s in SIDES) {
            parts += listOf(
                Key(s * 0.58f + shift * 0.3f, -6.15f, -0.02f, 0.58f, 0.62f, TROUSERS),
                Key(s * 0.58f + shift * 0.2f, -7.70f, 0.02f, 0.52f, 0.54f, TROUSERS),
                Key(s * 0.54f + shift * 0.1f, -9.10f, 0.04f, 0.40f, 0.42f, TROUSERS),
                Key(s * 0.52f, -9.65f, 0.05f, 0.39f, 0.41f, TROUSERS),
                Key(s * 0.51f, -10.45f, -0.04f, 0.42f, 0.45f, TROUSERS),
                Key(s * 0.49f, -11.40f, -0.01f, 0.31f, 0.32f, TROUSERS),
                Key(s * 0.48f, -12.30f, 0f, 0.24f, 0.25f, TROUSERS),
            )
            parts += listOf(
                Key(s * 0.48f, -12.62f, -0.30f, 0.27f, 0.24f, SHOES),
                Key(s * 0.49f, -12.64f, 0.20f, 0.30f, 0.24f, SHOES),
                Key(s * 0.51f, -12.67f, 0.80f, 0.23f, 0.18f, SHOES),
            )
        }

        // the arms: at rest a little out from the body and a little bent, the wrists by the top of the thighs; speaking, one
        // gestures and the other follows a little
        for (s in SIDES) {
            val shoulder = v(s * 1.38f + shift, -2.22f + lift, -0.04f)
            val lead = if (s > 0f) 1f else 0.35f
            val wave = sin(t * (2.1f + 0.4f * s) + s)
            val abduct = 0.11f + 0.02f * sin(t * 0.7f + s) + talk * lead * 0.16f
            val flex = 0.05f + talk * lead * (0.32f + 0.14f * wave)
            val bend = 0.22f + talk * lead * (0.85f + 0.30f * sin(t * 3.3f + 2f * s))
            val upper = limbDirection(s, abduct, flex)
            val elbow = add(shoulder, upper, UPPER_ARM)
            val fore = limbDirection(s, abduct * 0.5f, flex + bend)
            val wrist = add(elbow, fore, FOREARM)
            // the sleeve: the shoulder's round, the upper arm, the elbow, the forearm fuller near the elbow, a cuff at the wrist
            parts += listOf(
                key(shoulder, 0.43f, 0.43f, JACKET),
                key(add(shoulder, upper, UPPER_ARM * 0.30f), 0.38f, 0.39f, JACKET),
                key(add(shoulder, upper, UPPER_ARM * 0.80f), 0.31f, 0.32f, JACKET),
                key(elbow, 0.30f, 0.30f, JACKET),
                key(add(elbow, fore, FOREARM * 0.28f), 0.31f, 0.31f, JACKET),
                key(add(wrist, fore, -0.24f), 0.24f, 0.24f, TRIM),
                key(wrist, 0.25f, 0.25f, TRIM),
            )
            // the hand: its palm to the thigh (narrower across than front to back), the fingers together and a little curled
            val curl = limbDirection(s, abduct * 0.3f, flex + bend + 0.35f)
            val knuckles = add(wrist, fore, HAND * 0.55f)
            parts += listOf(
                key(add(wrist, fore, -0.06f), 0.15f, 0.18f, SKIN),
                key(add(wrist, fore, HAND * 0.25f), 0.18f, 0.25f, SKIN),
                key(knuckles, 0.17f, 0.25f, SKIN),
                key(add(knuckles, curl, HAND * 0.30f), 0.13f, 0.21f, SKIN),
                key(add(knuckles, curl, HAND * 0.45f), 0.07f, 0.12f, SKIN),
            )
            // the thumb, along the front of the hand
            val base = add(add(wrist, fore, HAND * 0.12f), v(s * 0.05f, 0f, 1f), 0.15f)
            val thumb = limbDirection(s, abduct * 0.3f, flex + bend + 0.55f)
            parts += listOf(
                key(base, 0.085f, 0.085f, SKIN),
                key(add(base, thumb, HAND * 0.28f), 0.072f, 0.072f, SKIN),
                key(add(base, thumb, HAND * 0.42f), 0.055f, 0.055f, SKIN),
            )
        }
        return parts.mapIndexed { i, keys -> if (i == 0) keys else keys.map { it.back() } }
    }

    /** The key set back under the head (see BODY_BACK). */
    private fun Key.back() = Key(x, y, z + BODY_BACK, ru, rv, part)

    /** A point the surface passes through, [ru] its radius across and [rv] front to back (for an upright path), [part] its material up to the next key. */
    class Key(val x: Float, val y: Float, val z: Float, val ru: Float, val rv: Float, val part: Int)

    companion object {
        const val UPPER_ARM = 2.55f
        const val FOREARM = 2.15f
        const val HAND = 1.40f
        const val TALK_ATTACK = 0.12f
        const val TALK_RELEASE = 0.7f
        private val SIDES = floatArrayOf(1f, -1f)

        /** Down, swung out to the side [s] by [abduct] and forwards by [flex] (radians). */
        fun limbDirection(s: Float, abduct: Float, flex: Float): FloatArray =
            floatArrayOf(s * sin(abduct), -cos(abduct) * cos(flex), cos(abduct) * sin(flex))

        private fun v(x: Float, y: Float, z: Float) = floatArrayOf(x, y, z)
        private fun add(p: FloatArray, d: FloatArray, k: Float) = floatArrayOf(p[0] + d[0] * k, p[1] + d[1] * k, p[2] + d[2] * k)
        private fun key(p: FloatArray, ru: Float, rv: Float, part: Int) = Key(p[0], p[1], p[2], ru, rv, part)
    }
}

/** Where the soles are, in head half-heights below the head's centre: the figure is about seven heads tall, as an adult. */
internal const val BODY_FEET = -12.88f

/**
 * How far the body stands behind the head's centre, in head half-heights: the head's origin is near the front of the face (its eye
 * line), its neck well behind it, so the shoulders and all go back with the neck. It turns about the same upright line as the head.
 */
internal const val BODY_BACK = -0.5f

// what each part of the surface is made of (see bodyVertexColour)
internal const val JACKET = 0
internal const val TRIM = 1
internal const val TROUSERS = 2
internal const val BELT = 3
internal const val SHOES = 4
internal const val SKIN = 5

/**
 * How far apart the body's rings, and the points round each, are at the head's polygon [level], in head half-heights: about the
 * length of the head's own skin edges at that level (a little more, since a ring's quad cut in two also has a diagonal), the finer
 * levels halving it as the head's cut each triangle in four. Ultra is High: what it adds on the head is the hair's own triangles.
 */
internal fun bodySpacing(level: PolygonLevel): Float = when (level) {
    PolygonLevel.ECO -> 0.121f
    PolygonLevel.LOW -> 0.104f
    PolygonLevel.MEDIUM -> 0.0895f
    PolygonLevel.HIGH, PolygonLevel.ULTRA -> 0.059f
}

/**
 * The surface: [pos] x, y, z and [nrm] the smooth outward normal per vertex, [part] per vertex, [tris] three vertex indices each
 * (counter-clockwise seen from outside). The web of the dark look runs round the rings and down the sides: [webA]-[webB] is the
 * stretch of it a triangle carries (-1: none), [webNode] its node (-1: none).
 */
internal class BodyMesh(
    val pos: FloatArray, val nrm: FloatArray, val part: IntArray, val tris: IntArray,
    val webA: IntArray = IntArray(tris.size / 3) { -1 }, val webB: IntArray = IntArray(tris.size / 3) { -1 },
    val webNode: IntArray = IntArray(tris.size / 3) { -1 },
) {
    val triangleCount: Int get() = tris.size / 3
    val vertexCount: Int get() = pos.size / 3
}

/** The body's points and triangles for one polygon [level], laid out once from a first pose; [place] moves them to each new pose. */
internal class BodyShape(val level: PolygonLevel, firstPose: List<List<ArBody.Key>>) {
    private class Layout(val keys: Int, val sides: Int, val tube: Int, val cap0: Int, val cap1: Int, val first: Int) {
        val rings get() = cap0 + tube + cap1
        val pole0 get() = first + rings * sides
        val pole1 get() = pole0 + 1
        val cosA = FloatArray(sides) { cos(2f * PI.toFloat() * it / sides) }
        val sinA = FloatArray(sides) { sin(2f * PI.toFloat() * it / sides) }
    }

    private val layouts: List<Layout>
    val mesh: BodyMesh

    init {
        val sp = bodySpacing(level)
        var first = 0
        val ls = ArrayList<Layout>()
        for (keys in firstPose) {
            val length = Path(keys).length
            val perimeter = keys.maxOf { PI.toFloat() * (it.ru + it.rv) }
            val sides = max(12, ceil(perimeter / sp).toInt())
            val tube = max(2, ceil(length / sp).toInt() + 1)
            fun cap(k: ArBody.Key) = max(1, ceil(PI.toFloat() / 2f * 0.5f * (k.ru + k.rv) / sp).toInt() - 1)
            val l = Layout(keys.size, sides, tube, cap(keys.first()), cap(keys.last()), first)
            ls += l
            first = l.pole1 + 1
        }
        layouts = ls
        val stride = max(1, (WEB_SPACING / sp).roundToInt())
        val tris = ArrayList<Int>()
        val webA = ArrayList<Int>(); val webB = ArrayList<Int>(); val webNode = ArrayList<Int>()
        fun tri(a: Int, b: Int, c: Int, wa: Int = -1, wb: Int = -1, node: Int = -1) { tris += a; tris += b; tris += c; webA += wa; webB += wb; webNode += node }
        for (l in layouts) {
            val s = l.sides
            fun at(r: Int, k: Int) = l.first + r * s + (k % s)
            for (r in 0 until l.rings - 1) for (k in 0 until s) {
                val ringLine = r % stride == 0
                val sideLine = k % stride == 0
                tri(at(r, k), at(r, k + 1), at(r + 1, k + 1), if (ringLine) at(r, k) else -1, if (ringLine) at(r, k + 1) else -1, if (ringLine && sideLine) at(r, k) else -1)
                tri(at(r, k), at(r + 1, k + 1), at(r + 1, k), if (sideLine) at(r, k) else -1, if (sideLine) at(r + 1, k) else -1)
            }
            for (k in 0 until s) {
                tri(l.pole0, at(0, k + 1), at(0, k))
                tri(l.pole1, at(l.rings - 1, k), at(l.rings - 1, k + 1))
            }
        }
        mesh = BodyMesh(FloatArray(first * 3), FloatArray(first * 3), IntArray(first), tris.toIntArray(), webA.toIntArray(), webB.toIntArray(), webNode.toIntArray())
    }

    /** Moves every point to the shapes of [pose] (the same shapes, with as many keys each, as the first). */
    fun place(pose: List<List<ArBody.Key>>) {
        val pos = mesh.pos; val nrm = mesh.nrm; val part = mesh.part
        val sample = FloatArray(8)
        for ((index, l) in layouts.withIndex()) {
            val keys = pose[index]
            require(keys.size == l.keys)
            val path = Path(keys)
            val rings = l.rings
            val c = FloatArray(rings * 3); val d = FloatArray(rings * 3); val ru = FloatArray(rings); val rv = FloatArray(rings); val mat = IntArray(rings)
            for (j in 0 until l.tube) {
                val r = l.cap0 + j
                mat[r] = path.at(path.length * j / (l.tube - 1), sample)
                for (q in 0..2) { c[3 * r + q] = sample[q]; d[3 * r + q] = sample[3 + q] }
                // the torso's rings stay level: where its middle moves back towards the neck, tilted rings this wide would fold
                if (index == 0) { d[3 * r] = 0f; d[3 * r + 1] = 1f; d[3 * r + 2] = 0f }
                ru[r] = sample[6]; rv[r] = sample[7]
            }
            // the round ends: rings shrinking as on a ball, out to a point beyond the first and the last key
            val start = l.cap0; val end = l.cap0 + l.tube - 1
            val e0 = 0.5f * (ru[start] + rv[start]); val e1 = 0.5f * (ru[end] + rv[end])
            for (m in 1..l.cap0) {
                val a = PI.toFloat() / 2f * m / (l.cap0 + 1)
                val r = start - m
                for (q in 0..2) { c[3 * r + q] = c[3 * start + q] - d[3 * start + q] * e0 * sin(a); d[3 * r + q] = d[3 * start + q] }
                ru[r] = ru[start] * cos(a); rv[r] = rv[start] * cos(a); mat[r] = mat[start]
            }
            for (m in 1..l.cap1) {
                val a = PI.toFloat() / 2f * m / (l.cap1 + 1)
                val r = end + m
                for (q in 0..2) { c[3 * r + q] = c[3 * end + q] + d[3 * end + q] * e1 * sin(a); d[3 * r + q] = d[3 * end + q] }
                ru[r] = ru[end] * cos(a); rv[r] = rv[end] * cos(a); mat[r] = mat[end]
            }
            // each ring's own axes, carried along the path so that the rings do not twist
            var u = sideAxis(d[0], d[1], d[2])
            for (r in 0 until rings) {
                val dx = d[3 * r]; val dy = d[3 * r + 1]; val dz = d[3 * r + 2]
                if (r > 0) {
                    val k = u[0] * dx + u[1] * dy + u[2] * dz
                    val moved = floatArrayOf(u[0] - dx * k, u[1] - dy * k, u[2] - dz * k)
                    u = if (length(moved) > 1e-4f) normalise(moved) else sideAxis(dx, dy, dz)
                }
                val w = cross(floatArrayOf(dx, dy, dz), u)   // d × u: with u, w, d right-handed the rings wind so the facets face out
                for (k in 0 until l.sides) {
                    val cu = l.cosA[k] * ru[r]; val sw = l.sinA[k] * rv[r]
                    val vi = l.first + r * l.sides + k
                    pos[3 * vi] = c[3 * r] + u[0] * cu + w[0] * sw
                    pos[3 * vi + 1] = c[3 * r + 1] + u[1] * cu + w[1] * sw
                    pos[3 * vi + 2] = c[3 * r + 2] + u[2] * cu + w[2] * sw
                    part[vi] = mat[r]
                }
            }
            for (q in 0..2) {
                pos[3 * l.pole0 + q] = c[3 * start + q] - d[3 * start + q] * e0
                pos[3 * l.pole1 + q] = c[3 * end + q] + d[3 * end + q] * e1
                nrm[3 * l.pole0 + q] = -d[3 * start + q]
                nrm[3 * l.pole1 + q] = d[3 * end + q]
            }
            part[l.pole0] = mat[0]; part[l.pole1] = mat[rings - 1]
            // smooth normals, from the neighbours round the ring and along the path (the poles past the ends): (round) × (along)
            for (r in 0 until rings) for (k in 0 until l.sides) {
                val vi = l.first + r * l.sides + k
                val next = l.first + r * l.sides + (k + 1) % l.sides
                val prev = l.first + r * l.sides + (k + l.sides - 1) % l.sides
                val up = if (r + 1 < rings) vi + l.sides else l.pole1
                val down = if (r > 0) vi - l.sides else l.pole0
                val tx = pos[3 * next] - pos[3 * prev]; val ty = pos[3 * next + 1] - pos[3 * prev + 1]; val tz = pos[3 * next + 2] - pos[3 * prev + 2]
                val bx = pos[3 * up] - pos[3 * down]; val by = pos[3 * up + 1] - pos[3 * down + 1]; val bz = pos[3 * up + 2] - pos[3 * down + 2]
                val nx = ty * bz - tz * by; val ny = tz * bx - tx * bz; val nz = tx * by - ty * bx
                val len = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-9f)
                nrm[3 * vi] = nx / len; nrm[3 * vi + 1] = ny / len; nrm[3 * vi + 2] = nz / len
            }
        }
    }

    companion object {
        /** How far apart the lines of the web are on the body: about as the nodes of the head's (NetworkWeb). */
        const val WEB_SPACING = 0.058f

        /** A side axis square to ([dx], [dy], [dz]): across (x) when the path stands up, so ru is the width and rv the depth. */
        private fun sideAxis(dx: Float, dy: Float, dz: Float): FloatArray {
            val d = floatArrayOf(dx, dy, dz)
            var u = cross(d, floatArrayOf(0f, 0f, 1f))
            if (length(u) < 0.3f) u = cross(cross(d, floatArrayOf(1f, 0f, 0f)), d)
            u = normalise(u)
            if (u[0] < 0f || (u[0] == 0f && u[1] < 0f)) u = floatArrayOf(-u[0], -u[1], -u[2])
            return u
        }
    }
}

/** A smooth curve through [keys] (Catmull-Rom), measured along its length; [at] reads the centre, direction and radii at a distance. */
private class Path(private val keys: List<ArBody.Key>) {
    private val n = keys.size
    private val table = FloatArray((n - 1) * SUB + 1)     // the distance along the curve at each of SUB steps per segment
    val length: Float

    init {
        val p = FloatArray(3); val q = FloatArray(3)
        point(0f, p)
        for (i in 1 until table.size) {
            point(i.toFloat() / SUB, q)
            table[i] = table[i - 1] + sqrt((q[0] - p[0]) * (q[0] - p[0]) + (q[1] - p[1]) * (q[1] - p[1]) + (q[2] - p[2]) * (q[2] - p[2]))
            p[0] = q[0]; p[1] = q[1]; p[2] = q[2]
        }
        length = table.last()
    }

    /** At [distance] along the curve: [out] gets x, y, z, the unit direction, then the two radii; returns the material there. */
    fun at(distance: Float, out: FloatArray): Int {
        var i = 0
        while (i < table.size - 2 && table[i + 1] < distance) i++
        val span = (table[i + 1] - table[i]).coerceAtLeast(1e-9f)
        val last = (n - 1).toFloat()
        val param = ((i + ((distance - table[i]) / span).coerceIn(0f, 1f)) / SUB).coerceIn(0f, last)
        point(param, out)
        val a = FloatArray(3); val b = FloatArray(3)
        point((param - 0.02f).coerceAtLeast(0f), a); point((param + 0.02f).coerceAtMost(last), b)
        val dir = normalise(floatArrayOf(b[0] - a[0], b[1] - a[1], b[2] - a[2]))
        out[3] = dir[0]; out[4] = dir[1]; out[5] = dir[2]
        val seg = param.toInt().coerceAtMost(n - 2)
        val u = param - seg
        out[6] = spline(seg, u) { it.ru }.coerceAtLeast(0.02f)
        out[7] = spline(seg, u) { it.rv }.coerceAtLeast(0.02f)
        return keys[if (u > 0.999f) seg + 1 else seg].part
    }

    private fun point(param: Float, out: FloatArray) {
        val seg = param.toInt().coerceIn(0, n - 2)
        val u = param - seg
        out[0] = spline(seg, u) { it.x }; out[1] = spline(seg, u) { it.y }; out[2] = spline(seg, u) { it.z }
    }

    /** One value of the keys along the curve, from key [seg] to the next at [u] (0..1); past the ends the curve goes straight on. */
    private inline fun spline(seg: Int, u: Float, value: (ArBody.Key) -> Float): Float {
        val p1 = value(keys[seg]); val p2 = value(keys[seg + 1])
        val p0 = if (seg > 0) value(keys[seg - 1]) else 2f * p1 - p2
        val p3 = if (seg + 2 < n) value(keys[seg + 2]) else 2f * p2 - p1
        val u2 = u * u; val u3 = u2 * u
        return 0.5f * (2f * p1 + (p2 - p0) * u + (2f * p0 - 5f * p1 + 4f * p2 - p3) * u2 + (3f * p1 - p0 - 3f * p2 + p3) * u3)
    }

    companion object {
        const val SUB = 16
    }
}

internal fun cross(a: FloatArray, b: FloatArray) = floatArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
internal fun length(a: FloatArray) = sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2])
internal fun normalise(a: FloatArray): FloatArray { val l = length(a).coerceAtLeast(1e-9f); return floatArrayOf(a[0] / l, a[1] / l, a[2] / l) }
