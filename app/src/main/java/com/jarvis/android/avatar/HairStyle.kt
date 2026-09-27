package com.jarvis.android.avatar

import org.json.JSONArray
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sqrt

/**
 * A hairstyle the user can put on a head (format JHR1, written by tools/avatar/export_hair.py from Sketchfab collections, see
 * assets/avatar/NOTICE.txt): the strands, and the skull they were made on, as its radius in every direction from its centre.
 *
 * [fitOn] moves the strands onto another head radially, as tools/avatar/hair_fit.py does: along each direction from the skull's centre a
 * point keeps its height over the scalp (a strand lying on the source scalp lies on the new one, a lock standing out still stands out),
 * and the strands are coloured with the face's hair colours and given normals leaning outwards.
 */
internal class HairStyle(
    val positions: FloatArray,
    val key: ByteArray,
    val faces: IntArray,
    private val centre: FloatArray,
    private val radius: FloatArray,
    private val az: Int,
    private val el: Int,
) {
    val vertexCount get() = positions.size / 3
    val faceCount get() = faces.size / 3

    /** The face's hair colours (RGB), and how many strands turn grey (0..1). */
    class Colours(val body: Int, val root: Int, val tip: Int, val grey: Float = 0f, val greyRgb: Int = 0x8E8B86)

    /** A copy of [head] wearing this style instead of its own locks (its cap, the coloured scalp, stays under the strands). */
    fun fitOn(head: HeadMesh, colours: Colours, lift: Float = 0.012f): HeadMesh {
        // the target skull: the skin, not the hair, nor what is painted inside it (the eyeballs, the mouth, the teeth): the eye
        // openings are then filled from the lids around them, and no strand is set down inside an eye socket
        val skull = ArrayList<Int>()
        for (t in 0 until head.faceCount) {
            if (head.faceGroup[t] > 1.5f) continue
            val a = head.faces[3 * t]; val b = head.faces[3 * t + 1]; val c = head.faces[3 * t + 2]
            if (head.paint[a] != 0 || head.paint[b] != 0 || head.paint[c] != 0) continue
            skull += t
        }
        val ct = skullCentre(head.verts, head.faces, skull)
        val rt = radiusMap(head.verts, head.faces, skull, ct, az, el)
        val n = vertexCount
        val out = FloatArray(3 * n)
        val heightOver = FloatArray(n)
        for (i in 0 until n) {
            val x = positions[3 * i] - centre[0]; val y = positions[3 * i + 1] - centre[1]; val z = positions[3 * i + 2] - centre[2]
            val r = max(sqrt(x * x + y * y + z * z), 1e-9f)
            val ux = x / r; val uy = y / r; val uz = z / r
            val (a, e) = gridOf(ux, uy, uz, az, el)
            val h = max(r - sample(radius, a, e, az, el), lift)
            val nr = sample(rt, a, e, az, el) + h
            out[3 * i] = ct[0] + ux * nr; out[3 * i + 1] = ct[1] + uy * nr; out[3 * i + 2] = ct[2] + uz * nr
            heightOver[i] = h
        }
        // normals: the surface's, turned outwards, leaning towards the direction out of the head
        val nrm = FloatArray(3 * n)
        for (t in 0 until faceCount) {
            val a = faces[3 * t]; val b = faces[3 * t + 1]; val c = faces[3 * t + 2]
            val ux = out[3 * b] - out[3 * a]; val uy = out[3 * b + 1] - out[3 * a + 1]; val uz = out[3 * b + 2] - out[3 * a + 2]
            val vx = out[3 * c] - out[3 * a]; val vy = out[3 * c + 1] - out[3 * a + 1]; val vz = out[3 * c + 2] - out[3 * a + 2]
            val fx = uy * vz - uz * vy; val fy = uz * vx - ux * vz; val fz = ux * vy - uy * vx
            for (v in intArrayOf(a, b, c)) { nrm[3 * v] += fx; nrm[3 * v + 1] += fy; nrm[3 * v + 2] += fz }
        }
        val paint = IntArray(n)
        for (i in 0 until n) {
            val ox = out[3 * i] - ct[0]; val oy = out[3 * i + 1] - ct[1]; val oz = out[3 * i + 2] - ct[2]
            val ol = max(sqrt(ox * ox + oy * oy + oz * oz), 1e-9f)
            var sx = nrm[3 * i]; var sy = nrm[3 * i + 1]; var sz = nrm[3 * i + 2]
            val sl = max(sqrt(sx * sx + sy * sy + sz * sz), 1e-12f)
            sx /= sl; sy /= sl; sz /= sl
            if (sx * ox + sy * oy + sz * oz < 0f) { sx = -sx; sy = -sy; sz = -sz }
            var nx = 0.45f * sx + 0.55f * ox / ol; var ny = 0.45f * sy + 0.55f * oy / ol; var nz = 0.45f * sz + 0.55f * oz / ol
            val nl = max(sqrt(nx * nx + ny * ny + nz * nz), 1e-12f)
            nx /= nl; ny /= nl; nz /= nl
            nrm[3 * i] = nx; nrm[3 * i + 1] = ny; nrm[3 * i + 2] = nz
            paint[i] = strandColour(key[i].toInt() and 0xFF, (heightOver[i] / 0.10f).coerceIn(0f, 1f), colours)
        }
        return withHair(head, out, nrm, paint)
    }

    private fun withHair(head: HeadMesh, hv: FloatArray, hn: FloatArray, hp: IntArray): HeadMesh {
        val base = head.vertexCount
        val lockEnd = head.lockFirst + head.lockCount * 3 * head.lockRows
        fun isLock(v: Int) = head.lockCount > 0 && v >= head.lockFirst && v < lockEnd
        val keptFaces = ArrayList<Int>()
        for (t in 0 until head.faceCount) {
            val a = head.faces[3 * t]; val b = head.faces[3 * t + 1]; val c = head.faces[3 * t + 2]
            if (!(isLock(a) || isLock(b) || isLock(c))) keptFaces += t
        }
        val nf = keptFaces.size + faceCount
        val faces = IntArray(3 * nf); val group = FloatArray(nf)
        var k = 0
        for (t in keptFaces) {
            for (c in 0..2) faces[3 * k + c] = head.faces[3 * t + c]
            group[k++] = head.faceGroup[t]
        }
        for (t in 0 until faceCount) {
            for (c in 0..2) faces[3 * k + c] = base + this.faces[3 * t + c]
            group[k++] = 2.5f                                        // a chosen hairstyle: strands drawn from both sides
        }
        val n = base + vertexCount
        fun ext(a: FloatArray, fill: Float) = FloatArray(n) { if (it < base) a[it] else fill }
        return HeadMesh(
            verts = head.verts + hv, normals = head.normals + hn,
            jaw = ext(head.jaw, 0f), brow = ext(head.brow, 0f), lips = ext(head.lips, 0f), fade = ext(head.fade, 1f),
            faceGroup = group, faces = faces, edges = head.edges, landmarks = head.landmarks, lipCentre = head.lipCentre,
            nHead = head.nHead, nFace = head.nFace, crown = head.crown, bottom = head.bottom,
            paint = IntArray(n) { if (it < base) head.paint[it] else hp[it - base] },
            lid = ext(head.lid, 0f), lipMask = ext(head.lipMask, 0f),
            eyeFirst = head.eyeFirst, eyeCount = head.eyeCount, eyeCentre = head.eyeCentre, eyelidRim = head.eyelidRim,
            mouthUpper = head.mouthUpper, mouthLower = head.mouthLower,
            lockFirst = 0, lockCount = 0, lockRows = 0,                  // no fine fibres drawn over hand-made strands
        )
    }

    companion object {
        fun parse(bytes: ByteArray): HairStyle {
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            require(String(bytes, 0, 4, Charsets.US_ASCII) == "JHR1") { "not a hairstyle" }
            b.position(4)
            val nv = b.int; val nf = b.int; val az = b.int; val el = b.int
            val centre = FloatArray(3) { b.float }
            val lo = FloatArray(3) { b.float }; val step = FloatArray(3) { b.float }
            val radius = FloatArray(el * az) { b.float }
            val pos = FloatArray(3 * nv)
            for (i in 0 until 3 * nv) pos[i] = lo[i % 3] + (b.short.toInt() and 0xFFFF) * step[i % 3]
            val key = ByteArray(nv).also { b.get(it) }
            b.position(b.position() + (4 - nv % 4) % 4)
            val faces = IntArray(3 * nf) { b.short.toInt() and 0xFFFF }
            require(faces.all { it < nv }) { "hairstyle indices out of range" }
            return HairStyle(pos, key, faces, centre, radius, az, el)
        }

        /** The colour of a point of a strand: the face's hair colour, darker right at the roots, lighter towards long tips, a shade of
         * its own per strand, and some strands grey. [height] is 0 on the scalp, 1 ten centimetres out. */
        fun strandColour(key: Int, height: Float, c: Colours): Int {
            fun ch(rgb: Int, s: Int) = ((rgb shr s) and 0xFF).toFloat()
            val grey = ((key * 37) % 256) / 256f < c.grey
            val tone = 0.86f + 0.24f * key / 255f
            val rootMix = 0.45f + 0.55f * (height * 8f).coerceIn(0f, 1f)
            val tipMix = ((height - 0.45f) * 1.2f).coerceIn(0f, 1f) * 0.35f
            var rgb = 0
            for (s in intArrayOf(16, 8, 0)) {
                var v = if (grey) ch(c.greyRgb, s) * (0.85f + 0.15f * height)
                else (ch(c.root, s) + (ch(c.body, s) - ch(c.root, s)) * rootMix).let { it + (ch(c.tip, s) - it) * tipMix }
                v = (v * tone).coerceIn(0f, 255f)
                rgb = rgb or (round(v).toInt() shl s)
            }
            return (254 shl 24) or rgb                                        // alpha 254: all hair, no skin through it
        }

        fun gridOf(ux: Float, uy: Float, uz: Float, az: Int, el: Int): Pair<Float, Float> {
            val a = ((atan2(ux, uz) + PI.toFloat()) / (2f * PI.toFloat())) * az
            val e = ((asin(uy.coerceIn(-1f, 1f)) + PI.toFloat() / 2f) / PI.toFloat()) * (el - 1)
            return a to e
        }

        fun sample(r: FloatArray, a: Float, e: Float, az: Int, el: Int): Float {
            val a0f = floor(a); val t = a - a0f
            val e0 = floor(e).toInt().coerceIn(0, el - 1); val e1 = min(e0 + 1, el - 1); val s = (e - e0).coerceIn(0f, 1f)
            val a0 = ((a0f.toInt() % az) + az) % az; val a1 = (a0 + 1) % az
            return (r[e0 * az + a0] * (1 - t) + r[e0 * az + a1] * t) * (1 - s) + (r[e1 * az + a0] * (1 - t) + r[e1 * az + a1] * t) * s
        }

        /** The middle of the vault (the head above y -0.1), in the middle plane. */
        fun skullCentre(v: FloatArray, f: IntArray, tris: List<Int>): FloatArray {
            var yMin = Float.MAX_VALUE; var yMax = -Float.MAX_VALUE; var zMin = Float.MAX_VALUE; var zMax = -Float.MAX_VALUE
            for (t in tris) for (c in 0..2) {
                val i = f[3 * t + c]; val y = v[3 * i + 1]
                if (y <= -0.1f) continue
                yMin = min(yMin, y); yMax = max(yMax, y); zMin = min(zMin, v[3 * i + 2]); zMax = max(zMax, v[3 * i + 2])
            }
            return floatArrayOf(0f, (yMin + yMax) / 2f, (zMin + zMax) / 2f)
        }

        /** The skull's outermost radius from [c] on the grid of directions, sampled on the triangles, holes filled, smoothed. */
        fun radiusMap(v: FloatArray, f: IntArray, tris: List<Int>, c: FloatArray, az: Int, el: Int): FloatArray {
            val r = FloatArray(el * az) { Float.NaN }
            val w = arrayOf(floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 1f, 0f), floatArrayOf(0f, 0f, 1f), floatArrayOf(1 / 3f, 1 / 3f, 1 / 3f),
                floatArrayOf(.5f, .5f, 0f), floatArrayOf(0f, .5f, .5f), floatArrayOf(.5f, 0f, .5f))
            for (t in tris) {
                val a = f[3 * t]; val b = f[3 * t + 1]; val d = f[3 * t + 2]
                if ((v[3 * a + 1] + v[3 * b + 1] + v[3 * d + 1]) / 3f <= -1.25f) continue
                for (ww in w) {
                    val x = ww[0] * v[3 * a] + ww[1] * v[3 * b] + ww[2] * v[3 * d] - c[0]
                    val y = ww[0] * v[3 * a + 1] + ww[1] * v[3 * b + 1] + ww[2] * v[3 * d + 1] - c[1]
                    val z = ww[0] * v[3 * a + 2] + ww[1] * v[3 * b + 2] + ww[2] * v[3 * d + 2] - c[2]
                    val rr = sqrt(x * x + y * y + z * z)
                    if (rr < 1e-9f) continue
                    val (ga, ge) = gridOf(x / rr, y / rr, z / rr, az, el)
                    val ia = ((floor(ga).toInt() % az) + az) % az
                    val ie = round(ge).toInt().coerceIn(0, el - 1)
                    val k = ie * az + ia
                    if (r[k].isNaN() || rr > r[k]) r[k] = rr
                }
            }
            val nb = FloatArray(4)
            repeat(60) {
                if (r.none { it.isNaN() }) return@repeat
                val next = r.copyOf()
                for (ie in 0 until el) for (ia in 0 until az) {
                    val k = ie * az + ia
                    if (!r[k].isNaN()) continue
                    nb[0] = r[ie * az + (ia + 1) % az]; nb[1] = r[ie * az + (ia + az - 1) % az]
                    nb[2] = r[max(ie - 1, 0) * az + ia]; nb[3] = r[min(ie + 1, el - 1) * az + ia]
                    var s = 0f; var cnt = 0
                    for (x in nb) if (!x.isNaN()) { s += x; cnt++ }
                    if (cnt > 0) next[k] = s / cnt
                }
                System.arraycopy(next, 0, r, 0, r.size)
            }
            val mean = r.filter { !it.isNaN() }.average().toFloat()
            for (k in r.indices) if (r[k].isNaN()) r[k] = mean
            repeat(2) {
                val next = r.copyOf()
                for (ie in 0 until el) for (ia in 0 until az) {
                    val k = ie * az + ia
                    next[k] = (2 * r[k] + r[ie * az + (ia + 1) % az] + r[ie * az + (ia + az - 1) % az] +
                        r[max(ie - 1, 0) * az + ia] + r[min(ie + 1, el - 1) * az + ia]) / 6f
                }
                System.arraycopy(next, 0, r, 0, r.size)
            }
            return r
        }
    }
}

/** One of the hairstyles offered (assets/avatar/hair/styles.json). */
internal data class HairChoice(val id: String, val labelFr: String, val labelEn: String, val women: Boolean) {
    val asset get() = "avatar/hair/$id.bin"
    fun label(english: Boolean) = if (english) labelEn else labelFr

    companion object {
        fun parseList(json: String): List<HairChoice> {
            val a = JSONArray(json)
            return (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                HairChoice(o.getString("id"), o.getString("fr"), o.getString("en"), o.optBoolean("women"))
            }
        }
    }
}
