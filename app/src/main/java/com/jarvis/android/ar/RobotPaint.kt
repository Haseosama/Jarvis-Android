package com.jarvis.android.ar

import com.jarvis.android.avatar.PolygonLevel
import com.jarvis.android.avatar.keyLight
import com.jarvis.android.avatar.mixRgbOpaque
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** How the robot is drawn: the theme's [primary] colour (its lights take some of it) and the polygon [level] of the settings. */
internal data class RobotLook(val primary: Int, val level: PolygonLevel) {
    companion object {
        val DEFAULT = RobotLook(0xFF00E5FF.toInt(), PolygonLevel.MEDIUM)
    }
}

/**
 * The robot ready to draw on the screen: [pos] x, y in pixels three vertices per triangle and [colour] one per vertex, farthest first
 * so the nearer ones cover them, in [CHUNKS] runs ending at [chunkEnd].
 */
internal class FigureDrawList(val pos: FloatArray, val colour: IntArray, val chunkEnd: IntArray) {
    val count: Int get() = colour.size / 3

    /**
     * Copies run [c]'s triangles to the start of [toPos] and [toColour] (at least [count] triangles long) and returns how many there
     * are. Canvas.drawVertices takes them from offset 0 only: with a vertex offset and no texture coordinates, Android reads texture
     * coordinates from a bad address and the app crashes.
     */
    fun chunk(c: Int, toPos: FloatArray, toColour: IntArray): Int {
        val from = if (c == 0) 0 else chunkEnd[c - 1]
        val n = chunkEnd[c] - from
        if (n <= 0) return 0
        System.arraycopy(pos, from * 6, toPos, 0, n * 6)
        System.arraycopy(colour, from * 3, toColour, 0, n * 3)
        return n
    }

    /** The same moved by ([dx], [dy]) pixels. */
    fun shifted(dx: Float, dy: Float) = FigureDrawList(FloatArray(pos.size) { if (it % 2 == 0) pos[it] + dx else pos[it] + dy }, colour, chunkEnd)

    companion object {
        const val CHUNKS = 8
    }
}

/**
 * Places [mesh] in the world, standing at ([ax], [ay], [az]) and facing the world angle [facing] (as [ArLook.facing]), one robot
 * unit being [unit] metres, then sees it through the camera's column-major [view] and [projection] onto a [width] × [height] screen,
 * lit as chrome in [look]: the triangles turned away are left out, the rest sorted far to near. Null when any of it is behind the
 * camera.
 */
internal fun robotDrawList(
    mesh: RobotMesh, ax: Float, ay: Float, az: Float, facing: Float, unit: Float,
    view: FloatArray, projection: FloatArray, width: Int, height: Int, look: RobotLook = RobotLook.DEFAULT,
): FigureDrawList? {
    val n = mesh.vertexCount
    val eye = FloatArray(n * 3)
    val en = FloatArray(n * 3)      // the normals in the eye's space (+z to the viewer)
    val vz = FloatArray(n)          // how much each vertex faces the camera (1 straight at it, 0 at the contour)
    val sx = FloatArray(n); val sy = FloatArray(n)
    val cf = cos(facing); val sf = sin(facing)
    for (i in 0 until n) {
        val lx = mesh.pos[3 * i]; val ly = mesh.pos[3 * i + 1] - ROBOT_FEET; val lz = mesh.pos[3 * i + 2]
        // the robot's front (+z) turned to face the world angle
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
        val mx = mesh.nrm[3 * i]; val my = mesh.nrm[3 * i + 1]; val mz = mesh.nrm[3 * i + 2]
        val nwx = mx * cf + mz * sf; val nwz = -mx * sf + mz * cf
        val nx = view[0] * nwx + view[4] * my + view[8] * nwz
        val ny = view[1] * nwx + view[5] * my + view[9] * nwz
        val nz = view[2] * nwx + view[6] * my + view[10] * nwz
        en[3 * i] = nx; en[3 * i + 1] = ny; en[3 * i + 2] = nz
        val d = sqrt(ex * ex + ey * ey + ez * ez).coerceAtLeast(1e-6f)
        vz[i] = -(nx * ex + ny * ey + nz * ez) / d
    }

    val tc = mesh.triangleCount
    val keys = LongArray(tc)
    var shown = 0
    for (t in 0 until tc) {
        val a = mesh.tris[3 * t]; val b = mesh.tris[3 * t + 1]; val c = mesh.tris[3 * t + 2]
        val abx = eye[3 * b] - eye[3 * a]; val aby = eye[3 * b + 1] - eye[3 * a + 1]; val abz = eye[3 * b + 2] - eye[3 * a + 2]
        val acx = eye[3 * c] - eye[3 * a]; val acy = eye[3 * c + 1] - eye[3 * a + 1]; val acz = eye[3 * c + 2] - eye[3 * a + 2]
        val nx = aby * acz - abz * acy; val ny = abz * acx - abx * acz; val nz = abx * acy - aby * acx
        val mx = (eye[3 * a] + eye[3 * b] + eye[3 * c]) / 3f
        val my = (eye[3 * a + 1] + eye[3 * b + 1] + eye[3 * c + 1]) / 3f
        val mz = (eye[3 * a + 2] + eye[3 * b + 2] + eye[3 * c + 2]) / 3f
        // turned away from the camera (which sits at the eye space's origin), or a point: hidden
        if (-(nx * mx + ny * my + nz * mz) <= 0f) continue
        val bits = java.lang.Float.floatToIntBits(mz)
        val mapped = if (bits >= 0) bits else bits xor 0x7fffffff
        keys[shown++] = (mapped.toLong() shl 32) or t.toLong()
    }
    // the farthest first (the most negative eye z)
    java.util.Arrays.sort(keys, 0, shown)

    val vcol = IntArray(n)
    val done = BooleanArray(n)
    val pos = FloatArray(shown * 6)
    val colour = IntArray(shown * 3)
    val chunkEnd = IntArray(FigureDrawList.CHUNKS) { ((it + 1).toLong() * shown / FigureDrawList.CHUNKS).toInt() }
    for (k in 0 until shown) {
        val t = (keys[k] and 0x7fffffffL).toInt()
        val a = mesh.tris[3 * t]; val b = mesh.tris[3 * t + 1]; val c = mesh.tris[3 * t + 2]
        val cx = (sx[a] + sx[b] + sx[c]) / 3f
        val cy = (sy[a] + sy[b] + sy[c]) / 3f
        for (j in 0..2) {
            val vi = mesh.tris[3 * t + j]
            // each triangle grows by about half a pixel round its centre: no specks of the camera between them
            val dx = sx[vi] - cx; val dy = sy[vi] - cy
            val grow = 0.6f / max(abs(dx) + abs(dy), 0.6f)
            pos[6 * k + 2 * j] = sx[vi] + dx * grow
            pos[6 * k + 2 * j + 1] = sy[vi] + dy * grow
            if (!done[vi]) {
                vcol[vi] = robotVertexColour(
                    mesh.colour[vi], en[3 * vi], en[3 * vi + 1], en[3 * vi + 2], vz[vi],
                    mesh.glow[vi], mesh.eye[vi], mesh.eyes, mesh.voice, look.primary,
                )
                done[vi] = true
            }
            colour[3 * k + j] = vcol[vi]
        }
    }
    return FigureDrawList(pos, colour, chunkEnd)
}

/**
 * The colour of the robot at a vertex painted [albedo], facing ([nx], [ny], [nz]) in the eye's space, [vz] towards the camera: its
 * painted chrome lit by the face's key light with a sharp highlight and a cool rim of the theme's colour; where it [glow]s (its eyes,
 * its green trim) its own light, brighter with the [voice] (0..1), the [eye]s' part of it going out as their light [eyes] does
 * (a blink), all tinted towards the theme's [primary].
 */
internal fun robotVertexColour(
    albedo: Int, nx: Float, ny: Float, nz: Float, vz: Float, glow: Float, eye: Float, eyes: Float, voice: Float, primary: Int,
): Int {
    val lam = keyLight(nx, ny, nz)
    // the highlight of the key light seen from the front (its half-way vector with the view)
    val hx = -0.55f; val hy = 0.50f; val hz = 1.52f
    val hl = sqrt(hx * hx + hy * hy + hz * hz)
    val spec = ((nx * hx + ny * hy + nz * hz) / hl).coerceIn(0f, 1f).pow(36f) * 0.55f
    val rim = (1f - vz.coerceIn(0f, 1f)).let { it * it * it } * 0.22f
    val k = 0.52f + 0.62f * lam + 0.08f * nz.coerceIn(0f, 1f)
    var r = ((albedo shr 16) and 0xFF) * k
    var g = ((albedo shr 8) and 0xFF) * k
    var b = (albedo and 0xFF) * k
    r += 255f * spec; g += 255f * spec; b += 255f * spec
    val pr = (primary shr 16) and 0xFF; val pg = (primary shr 8) and 0xFF; val pb = primary and 0xFF
    r += (pr - r * 0.3f) * rim; g += (pg - g * 0.3f) * rim; b += (pb - b * 0.3f) * rim
    var c = (0xFF shl 24) or (r.toInt().coerceIn(0, 255) shl 16) or (g.toInt().coerceIn(0, 255) shl 8) or b.toInt().coerceIn(0, 255)
    if (glow > 0.01f) {
        // its own light, unlit by the lamp: the painted colour brightened and drawn towards the theme's, out for a blink
        val on = (1f - eye * (1f - eyes)).coerceIn(0f, 1f)
        val light = mixRgbOpaque(albedo, primary, 0.45f).let { boost(it, 1.25f + 0.55f * voice) }
        val off = mixRgbOpaque(albedo, 0xFF0A1418.toInt(), 0.7f)
        val lit = mixRgbOpaque(off, light, on)
        c = mixRgbOpaque(c, lit, glow.coerceIn(0f, 1f))
    }
    return c
}

private fun boost(rgb: Int, k: Float): Int {
    val r = ((rgb shr 16) and 0xFF) * k; val g = ((rgb shr 8) and 0xFF) * k; val b = (rgb and 0xFF) * k
    // past white the extra spills into the other channels, so a bright light whitens instead of saturating
    val over = (max(r, max(g, b)) - 255f).coerceAtLeast(0f) * 0.5f
    return (0xFF shl 24) or ((r + over).toInt().coerceIn(0, 255) shl 16) or ((g + over).toInt().coerceIn(0, 255) shl 8) or (b + over).toInt().coerceIn(0, 255)
}
