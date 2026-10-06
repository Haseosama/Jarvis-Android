package com.jarvis.android.ar

import com.jarvis.android.avatar.BLUE_HOLO_SKIN
import com.jarvis.android.avatar.DARK_BLUE_HOLO_SKIN
import com.jarvis.android.avatar.HOLO_SKIN
import com.jarvis.android.avatar.PolygonLevel
import com.jarvis.android.avatar.holoSkin
import com.jarvis.android.avatar.keyLight
import com.jarvis.android.avatar.litRgb
import com.jarvis.android.avatar.mixRgbOpaque
import com.jarvis.android.avatar.shadeSkin
import com.jarvis.android.avatar.skinTone
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * How the face is drawn, for the body to be drawn the same way: [skin] as the settings have it (0 the dark web, 1..4 a skin tone,
 * 5 and up the holograms, as AvatarView reads it), the theme's [primary] and [background] colours, and the head's polygon [level].
 */
internal data class BodyLook(val skin: Int, val primary: Int, val background: Int, val level: PolygonLevel) {
    val holo: Boolean get() = skin >= HOLO_SKIN
    val blueMix: Boolean get() = skin == BLUE_HOLO_SKIN || skin == DARK_BLUE_HOLO_SKIN
    /** The tone the head's renderer takes for this look (the holograms wear the matt one, or the blue skin). */
    val tone: Int get() = when {
        blueMix -> 5
        holo -> 2
        else -> skin
    }

    companion object {
        val DEFAULT = BodyLook(1, 0xFF00E5FF.toInt(), 0xFF0B0F14.toInt(), PolygonLevel.MEDIUM)
    }
}

/**
 * The body ready to draw on the screen: [pos] x, y in pixels three vertices per triangle and [colour] one per vertex, farthest first
 * so the nearer ones cover them, in [CHUNKS] runs ending at [chunkEnd]. With the dark web look, the web's [lines] (x0, y0, x1, y1)
 * and [nodes] (x, y) of each run are drawn right after it, so the nearer surface covers them too: run c's lines of brightness
 * bucket b are from [lineStart] at c * 4 + b to the next, its nodes from [nodeStart] at c * 3 + b (floats).
 */
internal class BodyDrawList(
    val pos: FloatArray, val colour: IntArray, val chunkEnd: IntArray,
    val lines: FloatArray, val lineStart: IntArray, val nodes: FloatArray, val nodeStart: IntArray,
) {
    val count: Int get() = colour.size / 3

    /** The same moved by ([dx], [dy]) pixels. */
    fun shifted(dx: Float, dy: Float) = BodyDrawList(moved(pos, dx, dy), colour, chunkEnd, moved(lines, dx, dy), lineStart, moved(nodes, dx, dy), nodeStart)

    private fun moved(a: FloatArray, dx: Float, dy: Float) = FloatArray(a.size) { if (it % 2 == 0) a[it] + dx else a[it] + dy }

    companion object {
        const val CHUNKS = 8
        const val LINE_BUCKETS = 4
        const val NODE_BUCKETS = 3
    }
}

/**
 * Places [mesh] in the world, standing at ([ax], [ay], [az]) and facing the world angle [facing] (as [ArLook.facing]), one head
 * half-height being [unit] metres, then sees it through the camera's column-major [view] and [projection] onto a [width] × [height]
 * screen, lit and coloured as the face is in [look], the voice at [amp] (0..1) and the web's nodes twinkling with [time]: the
 * triangles turned away are left out, the rest sorted far to near. Null when any of it is behind the camera.
 */
internal fun bodyDrawList(
    mesh: BodyMesh, ax: Float, ay: Float, az: Float, facing: Float, unit: Float,
    view: FloatArray, projection: FloatArray, width: Int, height: Int,
    look: BodyLook = BodyLook.DEFAULT, amp: Float = 0f, time: Float = 0f,
): BodyDrawList? {
    val n = mesh.vertexCount
    val eye = FloatArray(n * 3)
    val en = FloatArray(n * 3)      // the normals in the eye's space (+z to the viewer)
    val vz = FloatArray(n)          // how much each vertex faces the camera (1 straight at it, 0 at the contour)
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
    val flat = IntArray(tc)
    var shown = 0
    val web = look.skin == 0
    val gain = 0.88f + 0.24f * amp
    for (t in 0 until tc) {
        val a = mesh.tris[3 * t]; val b = mesh.tris[3 * t + 1]; val c = mesh.tris[3 * t + 2]
        val abx = eye[3 * b] - eye[3 * a]; val aby = eye[3 * b + 1] - eye[3 * a + 1]; val abz = eye[3 * b + 2] - eye[3 * a + 2]
        val acx = eye[3 * c] - eye[3 * a]; val acy = eye[3 * c + 1] - eye[3 * a + 1]; val acz = eye[3 * c + 2] - eye[3 * a + 2]
        val nx = aby * acz - abz * acy; val ny = abz * acx - abx * acz; val nz = abx * acy - aby * acx
        val mx = (eye[3 * a] + eye[3 * b] + eye[3 * c]) / 3f
        val my = (eye[3 * a + 1] + eye[3 * b + 1] + eye[3 * c + 1]) / 3f
        val mz = (eye[3 * a + 2] + eye[3 * b + 2] + eye[3 * c + 2]) / 3f
        // turned away from the camera (which sits at the eye space's origin), or a point: hidden
        val facingCamera = -(nx * mx + ny * my + nz * mz)
        if (facingCamera <= 0f) continue
        if (web) {
            // the dark look: lit per facet, as the head's web is (AvatarRenderer.shadeFaces)
            val len = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-12f)
            val toward = facingCamera / len / sqrt(mx * mx + my * my + mz * mz).coerceAtLeast(1e-6f)
            // (the head's powers 1.7 and 1.05 taken as 1.5 and 1: the same picture, without three powers per triangle)
            val f = (1f - toward).coerceIn(0f, 2f)
            val fres = f * sqrt(f)
            val lam = keyLight(nx / len, ny / len, nz / len)
            val bright = (0.26f + 0.20f * fres + 0.66f * lam) * gain
            var col = mixRgbOpaque(look.background, look.primary, bright.coerceIn(0f, 1f) * 0.36f)
            val rim = (fres * fres * 0.14f).coerceIn(0f, 0.14f)
            if (rim > 0.01f) col = mixRgbOpaque(col, look.primary, rim)
            flat[t] = col
        }
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
    val chunkEnd = IntArray(BodyDrawList.CHUNKS) { ((it + 1).toLong() * shown / BodyDrawList.CHUNKS).toInt() }
    val lines = Floats(); val lineStart = IntArray(BodyDrawList.CHUNKS * BodyDrawList.LINE_BUCKETS + 1)
    val nodes = Floats(); val nodeStart = IntArray(BodyDrawList.CHUNKS * BodyDrawList.NODE_BUCKETS + 1)
    val lineRun = Array(BodyDrawList.LINE_BUCKETS) { Floats() }
    val nodeRun = Array(BodyDrawList.NODE_BUCKETS) { Floats() }
    val webGain = 0.85f + 0.5f * amp
    var chunk = 0
    for (k in 0 until shown) {
        val t = (keys[k] and 0x7fffffffL).toInt()
        val a = mesh.tris[3 * t]; val b = mesh.tris[3 * t + 1]; val c = mesh.tris[3 * t + 2]
        val cx = (sx[a] + sx[b] + sx[c]) / 3f
        val cy = (sy[a] + sy[b] + sy[c]) / 3f
        for (j in 0..2) {
            val vi = mesh.tris[3 * t + j]
            // each triangle grows by about half a pixel round its centre, as the head's do: no specks of the camera between them
            val dx = sx[vi] - cx; val dy = sy[vi] - cy
            val grow = 0.6f / max(kotlin.math.abs(dx) + kotlin.math.abs(dy), 0.6f)
            pos[6 * k + 2 * j] = sx[vi] + dx * grow
            pos[6 * k + 2 * j + 1] = sy[vi] + dy * grow
            colour[3 * k + j] = if (web) flat[t] else {
                if (!done[vi]) {
                    vcol[vi] = bodyVertexColour(mesh.part[vi], en[3 * vi], en[3 * vi + 1], en[3 * vi + 2], vz[vi], look, amp)
                    done[vi] = true
                }
                vcol[vi]
            }
        }
        if (web) {
            val wa = mesh.webA[t]; val wb = mesh.webB[t]
            if (wa >= 0) {
                val za = vz[wa]; val zb = vz[wb]
                if (za >= 0.05f || zb >= 0.05f) {
                    val fres = steep(1f - 0.5f * (za + zb))
                    var alpha = (0.30f + 0.55f * fres) * webGain
                    if (za < 0.15f || zb < 0.15f) alpha *= 0.6f
                    if (alpha > 0.05f) lineRun[(alpha * 4f).toInt().coerceIn(0, 3)].add(sx[wa], sy[wa], sx[wb], sy[wb])
                }
            }
            val node = mesh.webNode[t]
            if (node >= 0 && vz[node] >= 0f) {
                val fres = steep(1f - vz[node])
                val twinkle = 0.8f + 0.2f * sin(time * 2.1f + node * 1.7f)
                val br = (0.55f + 0.9f * fres) * twinkle
                val hash = ((node * -1640531535) ushr 16) and 0xFF
                nodeRun[if (br > 0.85f || hash > 236) 2 else if (br > 0.5f) 1 else 0].add(sx[node], sy[node])
            }
        }
        // the end of a run: its web goes out in order of brightness
        while (chunk < BodyDrawList.CHUNKS && k + 1 == chunkEnd[chunk]) {
            for (bk in 0 until BodyDrawList.LINE_BUCKETS) {
                lines.addAll(lineRun[bk]); lineRun[bk].clear()
                lineStart[chunk * BodyDrawList.LINE_BUCKETS + bk + 1] = lines.size
            }
            for (bk in 0 until BodyDrawList.NODE_BUCKETS) {
                nodes.addAll(nodeRun[bk]); nodeRun[bk].clear()
                nodeStart[chunk * BodyDrawList.NODE_BUCKETS + bk + 1] = nodes.size
            }
            chunk++
        }
    }
    // runs left empty (fewer triangles than runs) still mark where their lines start
    for (i in 1 until lineStart.size) lineStart[i] = max(lineStart[i], lineStart[i - 1])
    for (i in 1 until nodeStart.size) nodeStart[i] = max(nodeStart[i], nodeStart[i - 1])
    return BodyDrawList(pos, colour, chunkEnd, lines.toArray(), lineStart, nodes.toArray(), nodeStart)
}

/**
 * The colour at a vertex of [part] facing ([nx], [ny], [nz]) in the eye's space, [vz] towards the camera, in [look] (not the web),
 * the voice at [amp]: the neck and the hands are the face's own skin; the clothes are lit by the same light (the jacket dark, its
 * collar and cuffs of the theme's colour, the belt and the shoes with a little shine), and the holograms put the same cool light on
 * the contour of all of it.
 */
internal fun bodyVertexColour(part: Int, nx: Float, ny: Float, nz: Float, vz: Float, look: BodyLook, amp: Float): Int {
    val vlam = keyLight(nx, ny, nz)
    val c = if (part == SKIN) {
        shadeSkin(skinTone(look.tone), nx, ny, nz, amp, matte = look.holo, porcelain = false)
    } else {
        val base = when (part) {
            JACKET -> 0xFF262A33.toInt()
            TRIM -> mixRgbOpaque(0xFF2E3440.toInt(), look.primary, 0.65f)
            TROUSERS -> 0xFF1C1F26.toInt()
            BELT -> 0xFF3A2A1E.toInt()
            else -> 0xFF121317.toInt()
        }
        val cloth = if (look.holo && part != TRIM) mixRgbOpaque(0xFF0D1B2B.toInt(), look.primary, 0.18f) else base
        val k = (0.30f + 0.85f * vlam + 0.10f * nz.coerceIn(0f, 1f)).coerceIn(0.15f, 1.15f) * (0.94f + 0.12f * amp)
        val lit = litRgb(cloth, k * 1.25f)
        if (part == BELT || part == SHOES) {
            val shine = ((nx * -0.22f + ny * 0.28f + nz * 0.93f).coerceIn(0f, 1f).pow(30f) * 60f).toInt()
            (lit and 0xFF000000.toInt()) or (((lit shr 16 and 0xFF) + shine).coerceAtMost(255) shl 16) or
                (((lit shr 8 and 0xFF) + shine).coerceAtMost(255) shl 8) or ((lit and 0xFF) + shine).coerceAtMost(255)
        } else lit
    }
    return if (look.holo) holoSkin(c, vlam, vz, look.blueMix, look.primary) else c
}

/** About [x] to the power 2.4 (as the head's web takes it), for 0..1: x² √x. */
private fun steep(x: Float): Float { val c = x.coerceIn(0f, 1f); return c * c * sqrt(c) }

/** A growing list of floats. */
private class Floats {
    var data = FloatArray(256)
    var size = 0
        private set

    fun add(x: Float, y: Float) { room(2); data[size++] = x; data[size++] = y }
    fun add(x0: Float, y0: Float, x1: Float, y1: Float) { room(4); data[size++] = x0; data[size++] = y0; data[size++] = x1; data[size++] = y1 }
    fun addAll(o: Floats) { room(o.size); System.arraycopy(o.data, 0, data, size, o.size); size += o.size }
    fun clear() { size = 0 }
    fun toArray(): FloatArray = data.copyOf(size)

    private fun room(more: Int) { if (size + more > data.size) data = data.copyOf(max(data.size * 2, size + more)) }
}
