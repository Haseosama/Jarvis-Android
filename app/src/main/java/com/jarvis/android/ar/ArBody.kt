package com.jarvis.android.ar

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Haseo's body for the table: a figure about five and a half heads tall, built every frame from simple shapes (tubes between the
 * joints, rounded at their ends, and a torso lofted through a few oval rings), in the head's own units: the head's half-height is 1,
 * its centre at the origin, +y up, +z to the front, +x to the right of someone facing it; the soles stand at y = [BODY_FEET].
 * It breathes, shifts its weight now and then, and moves its arms while the voice speaks. Drawn flat-shaded, a facet at a time, under
 * the head the face view draws on top.
 */
internal class ArBody {
    var time = 0f
        private set
    /** 0..1: how much the arms are talking, eased from the voice's loudness. */
    var talk = 0f
        private set

    /** Moves the animation on by [dt] seconds, the voice at [level] (0..1). */
    fun step(dt: Float, level: Float) {
        val d = dt.coerceIn(0f, 0.2f)
        time += d
        val target = (level * 2.2f).coerceIn(0f, 1f)
        talk += (target - talk) * (1f - exp(-d / if (target > talk) TALK_ATTACK else TALK_RELEASE))
    }

    /** The body in its pose at the current time. */
    fun build(): BodyMesh {
        val b = MeshBuilder()
        val t = time
        val breath = sin(t * 1.5f)
        // the weight shifting from one leg to the other, slowly: the whole upper body sways a little
        val shift = 0.06f * sin(t * 0.35f)
        val chest = 1f + 0.022f * breath
        val lift = 0.03f * breath

        // the legs: a little apart, straight
        for (s in SIDES) {
            val hip = v(s * 0.46f + shift * 0.3f, -4.85f, 0f)
            val knee = v(s * 0.44f, -7.35f, 0.04f)
            val ankle = v(s * 0.42f, -9.6f, 0f)
            b.tube(hip, knee, 0.43f, 0.33f, TROUSERS)
            b.ball(knee, 0.33f, TROUSERS)
            b.tube(knee, ankle, 0.32f, 0.24f, TROUSERS)
            // the shoe: from the heel to the toe, flat
            b.tube(v(s * 0.42f, -9.78f, -0.22f), v(s * 0.43f, -9.8f, 0.72f), 0.27f, 0.24f, SHOES, squash = 0.72f)
        }

        // the torso: oval rings from the hips to the neck, the belt among them
        val rings = listOf(
            Ring(-5.05f, 0.80f, 0.52f, TROUSERS), Ring(-4.7f, 0.98f, 0.62f, TROUSERS), Ring(-4.3f, 0.92f, 0.60f, BELT),
            Ring(-4.08f, 0.88f, 0.58f, JACKET), Ring(-3.4f, 0.86f, 0.56f, JACKET), Ring(-2.6f, 1.02f * chest, 0.64f * chest, JACKET),
            Ring(-2.0f + lift, 1.12f * chest, 0.62f * chest, JACKET), Ring(-1.62f + lift, 0.95f, 0.50f, TRIM), Ring(-1.4f + lift, 0.45f, 0.36f, TRIM),
        )
        b.loft(rings.map { v(shift, it.y, 0f) }, rings.map { it.rx }, rings.map { it.rz }, rings.map { it.part })

        // the neck, under the head's own (which fades out lower down)
        b.tube(v(shift * 0.5f, -1.7f + lift, -0.02f), v(0f, -0.55f, -0.08f), 0.31f, 0.29f, SKIN)

        // the arms: at rest a little out from the body; speaking, one gestures and the other follows a little
        for (s in SIDES) {
            val shoulder = v(s * 1.2f + shift, -1.92f + lift, -0.02f)
            val lead = if (s > 0f) 1f else 0.35f
            val wave = sin(t * (2.1f + 0.4f * s) + s)
            val abduct = 0.13f + 0.02f * sin(t * 0.7f + s) + talk * lead * 0.16f
            val flex = 0.05f + talk * lead * (0.32f + 0.14f * wave)
            val bend = 0.20f + talk * lead * (0.85f + 0.30f * sin(t * 3.3f + 2f * s))
            val upper = limbDirection(s, abduct, flex)
            val elbow = add(shoulder, upper, UPPER_ARM)
            val fore = limbDirection(s, abduct * 0.6f, flex + bend)
            val wrist = add(elbow, fore, FOREARM)
            b.ball(shoulder, 0.42f, JACKET)
            b.tube(shoulder, elbow, 0.34f, 0.28f, JACKET)
            b.ball(elbow, 0.28f, JACKET)
            b.tube(elbow, add(wrist, fore, -0.12f), 0.28f, 0.23f, JACKET)
            b.tube(add(wrist, fore, -0.18f), wrist, 0.245f, 0.245f, TRIM)
            b.tube(wrist, add(wrist, fore, HAND), 0.17f, 0.15f, SKIN, squash = 0.65f)
        }
        return b.mesh()
    }

    private class Ring(val y: Float, val rx: Float, val rz: Float, val part: Int)

    companion object {
        const val UPPER_ARM = 1.75f
        const val FOREARM = 1.55f
        const val HAND = 0.55f
        const val TALK_ATTACK = 0.12f
        const val TALK_RELEASE = 0.7f
        private val SIDES = floatArrayOf(1f, -1f)

        /** Down, swung out to the side [s] by [abduct] and forwards by [flex] (radians). */
        fun limbDirection(s: Float, abduct: Float, flex: Float): FloatArray =
            floatArrayOf(s * sin(abduct), -cos(abduct) * cos(flex), cos(abduct) * sin(flex))

        private fun v(x: Float, y: Float, z: Float) = floatArrayOf(x, y, z)
        private fun add(p: FloatArray, d: FloatArray, k: Float) = floatArrayOf(p[0] + d[0] * k, p[1] + d[1] * k, p[2] + d[2] * k)
    }
}

/** Where the soles are, in head half-heights below the head's centre: the figure is about 5.5 heads tall. */
internal const val BODY_FEET = -10.05f

// what each facet is made of (see bodyColour)
internal const val JACKET = 0
internal const val TRIM = 1
internal const val TROUSERS = 2
internal const val BELT = 3
internal const val SHOES = 4
internal const val SKIN = 5

/** Triangles: [pos] x, y, z per vertex, [tris] three vertex indices each (outward-facing when counter-clockwise), [part] per triangle. */
internal class BodyMesh(val pos: FloatArray, val tris: IntArray, val part: IntArray) {
    val triangleCount: Int get() = tris.size / 3
}

/** Gathers the body's shapes into one mesh. */
internal class MeshBuilder {
    private val pos = ArrayList<Float>()
    private val tris = ArrayList<Int>()
    private val parts = ArrayList<Int>()

    private fun vertex(x: Float, y: Float, z: Float): Int { pos += x; pos += y; pos += z; return pos.size / 3 - 1 }
    private fun tri(a: Int, b: Int, c: Int, part: Int) { tris += a; tris += b; tris += c; parts += part }

    /** A tube from [a] to [b], [ra] and [rb] its radii there, closed at both ends; [squash] flattens it (the shoes, the hands). */
    fun tube(a: FloatArray, b: FloatArray, ra: Float, rb: Float, part: Int, squash: Float = 1f) =
        loft(listOf(a, b), listOf(ra, rb), listOf(ra * squash, rb * squash), listOf(part, part))

    /** A rough ball round [c], to round off a joint. */
    fun ball(c: FloatArray, r: Float, part: Int) {
        val k = 0.72f
        loft(
            listOf(floatArrayOf(c[0], c[1] - r * k, c[2]), floatArrayOf(c[0], c[1], c[2]), floatArrayOf(c[0], c[1] + r * k, c[2])),
            listOf(r * 0.7f, r, r * 0.7f), listOf(r * 0.7f, r, r * 0.7f), listOf(part, part, part),
        )
    }

    /**
     * Rings of [SIDES] points round the path [centres], [ru] and [rv] their two radii (ru across, rv front to back for an upright
     * path), joined into a closed shape; [part] is the material from each ring to the next (the last one's is used for the end cap).
     */
    fun loft(centres: List<FloatArray>, ru: List<Float>, rv: List<Float>, part: List<Int>) {
        val n = centres.size
        val first = pos.size / 3
        for (i in 0 until n) {
            val d = direction(centres, i)
            // a side axis square to the path: across (x) when the path stands up, so ru is the width and rv the depth
            var u = cross(d, floatArrayOf(0f, 0f, 1f))
            if (length(u) < 0.3f) u = cross(d, floatArrayOf(1f, 0f, 0f)).let { cross(it, d) }
            u = normalise(u)
            if (u[0] < 0f || (u[0] == 0f && u[1] < 0f)) u = floatArrayOf(-u[0], -u[1], -u[2])
            val w = cross(d, u)   // d × u: with u, w, d right-handed the rings wind so the facets face out
            val c = centres[i]
            for (k in 0 until SIDES) {
                val a = 2f * PI.toFloat() * k / SIDES
                val cu = cos(a) * ru[i]; val sw = sin(a) * rv[i]
                vertex(c[0] + u[0] * cu + w[0] * sw, c[1] + u[1] * cu + w[1] * sw, c[2] + u[2] * cu + w[2] * sw)
            }
        }
        for (i in 0 until n - 1) {
            val r0 = first + i * SIDES; val r1 = r0 + SIDES
            for (k in 0 until SIDES) {
                val k1 = (k + 1) % SIDES
                tri(r0 + k, r0 + k1, r1 + k1, part[i])
                tri(r0 + k, r1 + k1, r1 + k, part[i])
            }
        }
        // the two ends, each a fan round a point a little beyond the last ring
        val d0 = direction(centres, 0); val dn = direction(centres, n - 1)
        val start = centres[0]; val end = centres[n - 1]
        val e0 = 0.35f * minOf(ru[0], rv[0]); val en = 0.35f * minOf(ru[n - 1], rv[n - 1])
        val c0 = vertex(start[0] - d0[0] * e0, start[1] - d0[1] * e0, start[2] - d0[2] * e0)
        val cn = vertex(end[0] + dn[0] * en, end[1] + dn[1] * en, end[2] + dn[2] * en)
        val last = first + (n - 1) * SIDES
        for (k in 0 until SIDES) {
            val k1 = (k + 1) % SIDES
            tri(c0, first + k1, first + k, part[0])
            tri(cn, last + k, last + k1, part[n - 1])
        }
    }

    fun mesh() = BodyMesh(pos.toFloatArray(), tris.toIntArray(), parts.toIntArray())

    private fun direction(c: List<FloatArray>, i: Int): FloatArray {
        val a = c[maxOf(0, i - 1)]; val b = c[minOf(c.size - 1, i + 1)]
        return normalise(floatArrayOf(b[0] - a[0], b[1] - a[1], b[2] - a[2]))
    }

    companion object {
        const val SIDES = 8
    }
}

internal fun cross(a: FloatArray, b: FloatArray) = floatArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
internal fun length(a: FloatArray) = sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2])
internal fun normalise(a: FloatArray): FloatArray { val l = length(a).coerceAtLeast(1e-9f); return floatArrayOf(a[0] / l, a[1] / l, a[2] / l) }

/**
 * The body ready to draw on the screen: [pos] x, y in pixels three vertices per triangle, farthest first so the nearer ones cover them;
 * [part] and [light] (0..1, how much each faces the light) per triangle, coloured when drawn (see bodyColour).
 */
internal class BodyDrawList(val pos: FloatArray, val part: IntArray, val light: FloatArray) {
    val count: Int get() = part.size
}

/**
 * Places [mesh] in the world, standing at ([ax], [ay], [az]) and facing the world angle [facing] (as [ArLook.facing]), one head
 * half-height being [unit] metres, then sees it through the camera's column-major [view] and [projection] onto a [width] × [height]
 * screen: the facets turned away are left out, the rest sorted far to near and lit from above the camera's left, as the face is.
 * Null when any of it is behind the camera.
 */
internal fun bodyDrawList(
    mesh: BodyMesh, ax: Float, ay: Float, az: Float, facing: Float, unit: Float,
    view: FloatArray, projection: FloatArray, width: Int, height: Int,
): BodyDrawList? {
    val n = mesh.pos.size / 3
    val eye = FloatArray(n * 3)
    val sx = FloatArray(n); val sy = FloatArray(n)
    val cf = cos(facing); val sf = sin(facing)
    for (i in 0 until n) {
        val lx = mesh.pos[3 * i]; val ly = mesh.pos[3 * i + 1] - BODY_FEET; val lz = mesh.pos[3 * i + 2]
        // the body's front (+z) turned to face the world angle
        val wx = ax + unit * (lx * cf + lz * sf)
        val wy = ay + unit * ly
        val wz = az + unit * (-lx * sf + lz * cf)
        val ex = view[0] * wx + view[4] * wy + view[8] * wz + view[12]
        val ey = view[1] * wx + view[5] * wy + view[9] * wz + view[13]
        val ez = view[2] * wx + view[6] * wy + view[10] * wz + view[14]
        if (-ez <= 0.01f) return null
        eye[3 * i] = ex; eye[3 * i + 1] = ey; eye[3 * i + 2] = ez
        val cx = projection[0] * ex + projection[4] * ey + projection[8] * ez + projection[12]
        val cy = projection[1] * ex + projection[5] * ey + projection[9] * ez + projection[13]
        val cw = projection[3] * ex + projection[7] * ey + projection[11] * ez + projection[15]
        sx[i] = (cx / cw + 1f) * 0.5f * width
        sy[i] = (1f - cy / cw) * 0.5f * height
    }
    val tc = mesh.triangleCount
    val depth = FloatArray(tc)
    val shade = FloatArray(tc)
    val shown = ArrayList<Int>(tc)
    val light = normalise(floatArrayOf(-0.55f, 0.50f, 0.52f))
    for (t in 0 until tc) {
        val a = mesh.tris[3 * t]; val b = mesh.tris[3 * t + 1]; val c = mesh.tris[3 * t + 2]
        val ab = floatArrayOf(eye[3 * b] - eye[3 * a], eye[3 * b + 1] - eye[3 * a + 1], eye[3 * b + 2] - eye[3 * a + 2])
        val ac = floatArrayOf(eye[3 * c] - eye[3 * a], eye[3 * c + 1] - eye[3 * a + 1], eye[3 * c + 2] - eye[3 * a + 2])
        val nrm = cross(ab, ac)
        val mx = (eye[3 * a] + eye[3 * b] + eye[3 * c]) / 3f
        val my = (eye[3 * a + 1] + eye[3 * b + 1] + eye[3 * c + 1]) / 3f
        val mz = (eye[3 * a + 2] + eye[3 * b + 2] + eye[3 * c + 2]) / 3f
        // turned away from the camera (which sits at the eye space's origin): hidden
        if (nrm[0] * -mx + nrm[1] * -my + nrm[2] * -mz <= 0f) continue
        val nn = normalise(nrm)
        shade[t] = (nn[0] * light[0] + nn[1] * light[1] + nn[2] * light[2]).coerceIn(0f, 1f)
        depth[t] = mz
        shown += t
    }
    // the farthest first (the most negative eye z)
    shown.sortBy { depth[it] }
    val out = FloatArray(shown.size * 6)
    val part = IntArray(shown.size)
    val lit = FloatArray(shown.size)
    shown.forEachIndexed { k, t ->
        for (j in 0..2) {
            val vi = mesh.tris[3 * t + j]
            out[6 * k + 2 * j] = sx[vi]; out[6 * k + 2 * j + 1] = sy[vi]
        }
        part[k] = mesh.part[t]
        lit[k] = shade[t]
    }
    return BodyDrawList(out, part, lit)
}

/**
 * The colour of a facet of [part] lit by [light] (0..1), for a face of [skin] (0: the dark web, 1..4 the skin tones, 5 and up the
 * holograms, as in the settings) and the theme's [primary] colour: a dark jacket with seams of the theme's colour, dark trousers.
 * The holograms get a body of the same dark glass, tinted with their colour.
 */
internal fun bodyColour(part: Int, light: Float, skin: Int, primary: Int): Int {
    val holo = skin == 0 || skin >= 5
    val base = when (part) {
        JACKET -> 0x262A33
        TRIM -> mixRgb(0x2E3440, primary and 0xFFFFFF, 0.65f)
        TROUSERS -> 0x1C1F26
        BELT -> 0x3A2A1E
        SHOES -> 0x121317
        else -> skinRgb(skin)
    }
    val tinted = if (holo && part != TRIM) mixRgb(0x0D1B2B, primary and 0xFFFFFF, if (part == SKIN) 0.45f else 0.18f) else base
    val k = 0.38f + 0.80f * light
    val r = (((tinted shr 16) and 0xFF) * k).toInt().coerceIn(0, 255)
    val g = (((tinted shr 8) and 0xFF) * k).toInt().coerceIn(0, 255)
    val b = ((tinted and 0xFF) * k).toInt().coerceIn(0, 255)
    val alpha = if (holo) 0xE6 else 0xFF
    return (alpha shl 24) or (r shl 16) or (g shl 8) or b
}

/** The face's skin tone (as AvatarRenderer's), for the neck and the hands; the first tone for the faces without one. */
internal fun skinRgb(skin: Int): Int = BODY_SKIN_TONES[(skin - 1).coerceIn(0, BODY_SKIN_TONES.size - 1)]

private val BODY_SKIN_TONES = intArrayOf(0xF1C9A8, 0xD9A47C, 0xB07A54, 0x7A4E36)

private fun mixRgb(a: Int, b: Int, f: Float): Int {
    fun ch(s: Int) = ((((a shr s) and 0xFF) * (1f - f)) + (((b shr s) and 0xFF) * f)).toInt().coerceIn(0, 255)
    return (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
}
