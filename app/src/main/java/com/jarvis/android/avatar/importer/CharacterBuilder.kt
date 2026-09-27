package com.jarvis.android.avatar.importer

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Builds a textured character (JCH2, see CharacterMesh) from a glTF scene on the phone: the Kotlin twin of
 * tools/avatar/export_character.py, without anything Android in it (the atlas image itself is drawn by [CharacterImport]).
 */
internal object CharacterBuilder {
    const val NOSE_Z = 0.6796f
    const val MAX_TRIS = 40_000

    /** Every primitive merged, turned to face +z: positions, source uvs, faces, the material of each face and vertex, the UV islands. */
    class Scene(
        val pos: FloatArray, val uv: FloatArray, val faces: IntArray, val faceMat: IntArray, val vertMat: IntArray, val island: IntArray,
        val materials: List<Gltf.Material>,
    ) {
        val nV get() = pos.size / 3
        val nF get() = faces.size / 3
    }

    /** Where the face is in the scene's own units: the eye line, the chin, the middle of the face. */
    data class Framing(val eyeY: Float, val chin: Float, val centreX: Float)

    /** In the character's units (chin at -1): each eye's centre and size, the mouth's middle and half width. */
    data class Features(
        val eyes: List<FloatArray>,           // x, y, hw, hh, tilt (degrees)
        val mouth: FloatArray,                // cx, cy, hw, curve, slope
    )

    class Built(
        val pos: FloatArray, val normals: FloatArray, val srcUv: FloatArray, val vertMat: IntArray, val faces: IntArray,
        val headW: FloatArray, val jaw: FloatArray, val brow: FloatArray, val unlit: BooleanArray, val top: Float, val cut: Float,
        val eyeZ: FloatArray, val mouthPts: FloatArray,
    )

    fun gather(g: Gltf, yawDeg: Float): Scene {
        val prims = g.primitives()
        var nV = 0; var nF = 0
        for (p in prims) { nV += p.positions.size / 3; nF += p.indices.size / 3 }
        val pos = FloatArray(3 * nV); val uv = FloatArray(2 * nV); val faces = IntArray(3 * nF)
        val faceMat = IntArray(nF); val vertMat = IntArray(nV); val island = IntArray(nV)
        val c = cos(Math.toRadians(yawDeg.toDouble())).toFloat(); val s = sin(Math.toRadians(yawDeg.toDouble())).toFloat()
        var ov = 0; var of = 0; var islandBase = 0
        for (p in prims) {
            val n = p.positions.size / 3
            for (v in 0 until n) {
                val x = p.positions[3 * v]; val z = p.positions[3 * v + 2]
                pos[3 * (ov + v)] = c * x + s * z; pos[3 * (ov + v) + 1] = p.positions[3 * v + 1]; pos[3 * (ov + v) + 2] = -s * x + c * z
                uv[2 * (ov + v)] = p.uvs.getOrElse(2 * v) { 0f }; uv[2 * (ov + v) + 1] = p.uvs.getOrElse(2 * v + 1) { 0f }
                vertMat[ov + v] = p.material
            }
            // the islands: vertices joined by the triangles (the exporters split vertices along the seams)
            val parent = IntArray(n) { it }
            fun find(a: Int): Int { var x = a; while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x] }; return x }
            val m = p.indices.size / 3
            for (t in 0 until m) {
                val a = p.indices[3 * t]; val b = p.indices[3 * t + 1]; val d = p.indices[3 * t + 2]
                if (a >= n || b >= n || d >= n) continue
                parent[find(a)] = find(b); parent[find(b)] = find(d)
                faces[3 * (of + t)] = a + ov; faces[3 * (of + t) + 1] = b + ov; faces[3 * (of + t) + 2] = d + ov
                faceMat[of + t] = p.material
            }
            val ids = HashMap<Int, Int>()
            for (v in 0 until n) island[ov + v] = islandBase + ids.getOrPut(find(v)) { ids.size }
            islandBase += ids.size
            ov += n; of += m
        }
        return Scene(pos, uv, faces, faceMat, vertMat, island, g.materials)
    }

    /** A first guess of the framing, for the calibration: the head is taken to be the top seventh of a standing figure (or the top of a bust). */
    fun guess(sc: Scene): Framing {
        var yMin = Float.MAX_VALUE; var yMax = -Float.MAX_VALUE; var xMin = Float.MAX_VALUE; var xMax = -Float.MAX_VALUE
        for (v in 0 until sc.nV) {
            yMin = min(yMin, sc.pos[3 * v + 1]); yMax = max(yMax, sc.pos[3 * v + 1])
            xMin = min(xMin, sc.pos[3 * v]); xMax = max(xMax, sc.pos[3 * v])
        }
        val h = yMax - yMin
        val head = if (h > 2.2f * (xMax - xMin)) h / 7.2f else h / 2.2f
        return Framing(eyeY = yMax - 0.5f * head, chin = yMax - 1.05f * head, centreX = (xMin + xMax) / 2f)
    }

    private fun smooth(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** The scene framed as the heads: the chin at -1, the eyes at -0.05, the nose tip at z 0.68. */
    fun normalise(sc: Scene, f: Framing): FloatArray {
        val s = 0.95f / (f.eyeY - f.chin)
        var noseZ = -Float.MAX_VALUE
        val half = 0.5f * (f.eyeY - f.chin)
        for (v in 0 until sc.nV) {
            val y = sc.pos[3 * v + 1]
            if (y > f.chin && y < f.eyeY && abs(sc.pos[3 * v] - f.centreX) < half) noseZ = max(noseZ, sc.pos[3 * v + 2])
        }
        if (noseZ == -Float.MAX_VALUE) noseZ = 0f
        val out = FloatArray(sc.pos.size)
        for (v in 0 until sc.nV) {
            out[3 * v] = (sc.pos[3 * v] - f.centreX) * s
            out[3 * v + 1] = (sc.pos[3 * v + 1] - f.chin) * s - 1f
            out[3 * v + 2] = (sc.pos[3 * v + 2] - noseZ) * s + NOSE_Z
        }
        return out
    }

    /** From the calibration markers (character units of a first framing [f0]): the framing in the scene's units. */
    fun reframe(f0: Framing, eyeL: FloatArray, eyeR: FloatArray, chin: FloatArray): Framing {
        val s = 0.95f / (f0.eyeY - f0.chin)
        fun rawY(y: Float) = (y + 1f) / s + f0.chin
        fun rawX(x: Float) = x / s + f0.centreX
        return Framing(eyeY = rawY((eyeL[1] + eyeR[1]) / 2f), chin = rawY(chin[1]), centreX = rawX((eyeL[0] + eyeR[0]) / 2f))
    }

    /** The eyes and the mouth from the markers, once the framing puts the eyes at -0.05 (the markers moved with it). */
    fun features(eyeL: FloatArray, eyeR: FloatArray, mouth: FloatArray, f0: Framing, f1: Framing): Features {
        val s0 = 0.95f / (f0.eyeY - f0.chin); val s1 = 0.95f / (f1.eyeY - f1.chin)
        fun conv(p: FloatArray): FloatArray {
            val rx = p[0] / s0 + f0.centreX; val ry = (p[1] + 1f) / s0 + f0.chin
            return floatArrayOf((rx - f1.centreX) * s1, (ry - f1.chin) * s1 - 1f)
        }
        val l = conv(eyeL); val r = conv(eyeR); val m = conv(mouth)
        val d = sqrt((r[0] - l[0]).pow(2) + (r[1] - l[1]).pow(2)).coerceAtLeast(0.2f)
        val tilt = Math.toDegrees(kotlin.math.atan2((r[1] - l[1]).toDouble(), (r[0] - l[0]).toDouble())).toFloat()
        val hw = 0.27f * d; val hh = 0.10f * d          // an eye is about a quarter of the distance between them, each way from its centre
        return Features(
            eyes = listOf(floatArrayOf(l[0], l[1], hw, hh, tilt), floatArrayOf(r[0], r[1], hw, hh, tilt)),
            mouth = floatArrayOf(m[0], m[1], 0.42f * d, 0f, kotlin.math.tan(Math.toRadians(tilt.toDouble())).toFloat()),
        )
    }

    /** The bust (cut below the shoulders, the arms out), simplified to [maxTris], rigged. */
    fun build(sc: Scene, V0: FloatArray, feat: Features, cut: Float = -1.9f, cutX: Float = 1.6f, maxTris: Int = MAX_TRIS): Built {
        // the bust
        val keepF = ArrayList<Int>()
        for (t in 0 until sc.nF) {
            var ok = true
            for (k in 0..2) {
                val v = sc.faces[3 * t + k]
                if (V0[3 * v + 1] <= cut || abs(V0[3 * v]) >= cutX) { ok = false; break }
            }
            if (ok) keepF += t
        }
        val remap = IntArray(sc.nV) { -1 }
        var n = 0
        for (t in keepF) for (k in 0..2) { val v = sc.faces[3 * t + k]; if (remap[v] < 0) remap[v] = n++ }
        var V = FloatArray(3 * n); var UV = FloatArray(2 * n); var VM = IntArray(n); var ISL = IntArray(n)
        for (v in 0 until sc.nV) {
            val r = remap[v]; if (r < 0) continue
            for (c in 0..2) V[3 * r + c] = V0[3 * v + c]
            UV[2 * r] = sc.uv[2 * v]; UV[2 * r + 1] = sc.uv[2 * v + 1]; VM[r] = sc.vertMat[v]; ISL[r] = sc.island[v]
        }
        var F = IntArray(3 * keepF.size) { remap[sc.faces[3 * keepF[it / 3] + it % 3]] }
        if (F.size / 3 > maxTris) {
            var fc = 0.014f; var bc = 0.045f
            var r = cluster(V, F, ISL, fc, bc)
            var tries = 0
            while (r.second.size / 3 > maxTris && tries < 30) {
                fc *= 1.15f; bc *= 1.15f
                r = cluster(V, F, ISL, fc, bc)
                tries++
            }
            val (clOf, nf, cellOfCluster) = r
            // one position per cell, one uv per cluster (its own island's mean), clusters numbered 0 until k
            val k = cellOfCluster.size
            val cnt = IntArray(k); val cp = FloatArray(3 * k); val cu = FloatArray(2 * k); val cm = IntArray(k)
            val cellCount = HashMap<Int, Int>(); val cellPos = HashMap<Int, FloatArray>()
            for (v in 0 until V.size / 3) {
                val c = clOf[v]; cnt[c]++
                cu[2 * c] += UV[2 * v]; cu[2 * c + 1] += UV[2 * v + 1]; cm[c] = VM[v]
                val cell = cellOfCluster[c]
                val a = cellPos.getOrPut(cell) { FloatArray(3) }
                a[0] += V[3 * v]; a[1] += V[3 * v + 1]; a[2] += V[3 * v + 2]
                cellCount[cell] = (cellCount[cell] ?: 0) + 1
            }
            for (c in 0 until k) {
                val cell = cellOfCluster[c]; val a = cellPos.getValue(cell); val m = cellCount.getValue(cell).toFloat()
                cp[3 * c] = a[0] / m; cp[3 * c + 1] = a[1] / m; cp[3 * c + 2] = a[2] / m
                if (cnt[c] > 0) { cu[2 * c] /= cnt[c]; cu[2 * c + 1] /= cnt[c] }
            }
            // keep only the clusters in use
            val used = IntArray(k) { -1 }; var u = 0
            for (i in nf.indices) if (used[nf[i]] < 0) used[nf[i]] = u++
            V = FloatArray(3 * u); UV = FloatArray(2 * u); VM = IntArray(u)
            for (c in 0 until k) {
                val r = used[c]; if (r < 0) continue
                for (q in 0..2) V[3 * r + q] = cp[3 * c + q]
                UV[2 * r] = cu[2 * c]; UV[2 * r + 1] = cu[2 * c + 1]; VM[r] = cm[c]
            }
            F = IntArray(nf.size) { used[nf[it]] }
        }
        val nv = V.size / 3
        // normals
        val N = FloatArray(3 * nv)
        for (t in 0 until F.size / 3) {
            val a = F[3 * t]; val b = F[3 * t + 1]; val c = F[3 * t + 2]
            val ux = V[3 * b] - V[3 * a]; val uy = V[3 * b + 1] - V[3 * a + 1]; val uz = V[3 * b + 2] - V[3 * a + 2]
            val wx = V[3 * c] - V[3 * a]; val wy = V[3 * c + 1] - V[3 * a + 1]; val wz = V[3 * c + 2] - V[3 * a + 2]
            val nx = uy * wz - uz * wy; val ny = uz * wx - ux * wz; val nz = ux * wy - uy * wx
            for (v in intArrayOf(a, b, c)) { N[3 * v] += nx; N[3 * v + 1] += ny; N[3 * v + 2] += nz }
        }
        for (v in 0 until nv) {
            val l = max(sqrt(N[3 * v] * N[3 * v] + N[3 * v + 1] * N[3 * v + 1] + N[3 * v + 2] * N[3 * v + 2]), 1e-12f)
            N[3 * v] /= l; N[3 * v + 1] /= l; N[3 * v + 2] /= l
        }
        // the rig, as export_character.py
        val headW = FloatArray(nv); val jaw = FloatArray(nv); val brow = FloatArray(nv); val unlit = BooleanArray(nv)
        val (mcx, mcy, mhw, mcurve, mslope) = feat.mouth.let { listOf(it[0], it[1], it[2], it[3], it[4]) }
        val eyeTop = feat.eyes.maxOf { it[1] }
        var top = -Float.MAX_VALUE
        for (v in 0 until nv) {
            val x = V[3 * v]; val y = V[3 * v + 1]; val z = V[3 * v + 2]
            top = max(top, y)
            headW[v] = 0.12f + 0.88f * smooth(-1.45f, -1.05f, y)
            val seam = mcy + mslope * (x - mcx) + mcurve * (x - mcx) * (x - mcx)
            val dy = y - seam
            val mw = 1f - smooth(mhw * 0.9f, mhw * 1.35f, abs(x - mcx))
            var j = ((mcy - y) / (mcy + 1f)).coerceIn(0f, 1f).pow(0.8f)
            j *= smooth(-0.05f, 0.45f, z) * (1f - smooth(-1.02f, -1.22f, y))
            if (z > 0.25f) {
                if (dy <= 0f && dy > -0.22f) j = max(j, 0.92f * mw * (1f - smooth(0.08f, 0.22f, -dy)))
                if (dy > 0f) j = 0f
            }
            if (headW[v] < 0.9f) j *= smooth(0.12f, 0.9f, headW[v])
            jaw[v] = j
            var b = 0f
            for (e in feat.eyes) {
                val bx = e[0]; val by = e[1] + e[3] + 0.12f
                b = max(b, exp(-((x - bx) / (1.3f * e[2])).pow(2) - ((y - by) / 0.11f).pow(2)) * (if (z > NOSE_Z - 0.75f) 1f else 0f))
            }
            brow[v] = b * smooth(0.02f, 0.2f, y - eyeTop)
            unlit[v] = sc.materials.getOrNull(VM[v])?.unlit == true
        }
        // where the eyes and the mouth are on the surface
        fun hitZ(px: Float, py: Float): Float {
            var best = -Float.MAX_VALUE
            for (t in 0 until F.size / 3) {
                val a = F[3 * t]; val b = F[3 * t + 1]; val c = F[3 * t + 2]
                val ax = V[3 * a]; val ay = V[3 * a + 1]; val bx = V[3 * b]; val by = V[3 * b + 1]; val cx = V[3 * c]; val cy = V[3 * c + 1]
                if (px < min(ax, min(bx, cx)) || px > max(ax, max(bx, cx)) || py < min(ay, min(by, cy)) || py > max(ay, max(by, cy))) continue
                val den = (by - cy) * (ax - cx) + (cx - bx) * (ay - cy)
                if (abs(den) < 1e-12f) continue
                val l0 = ((by - cy) * (px - cx) + (cx - bx) * (py - cy)) / den
                val l1 = ((cy - ay) * (px - cx) + (ax - cx) * (py - cy)) / den
                val l2 = 1 - l0 - l1
                if (l0 < 0 || l1 < 0 || l2 < 0) continue
                best = max(best, l0 * V[3 * a + 2] + l1 * V[3 * b + 2] + l2 * V[3 * c + 2])
            }
            return if (best == -Float.MAX_VALUE) 0.4f else best
        }
        val eyeZ = FloatArray(feat.eyes.size) { hitZ(feat.eyes[it][0], feat.eyes[it][1]) }
        val mouthPts = FloatArray(3 * 13)
        for (i in 0 until 13) {
            val x = mcx - mhw + 2f * mhw * i / 12f
            val y = mcy + mslope * (x - mcx) + mcurve * (x - mcx) * (x - mcx)
            mouthPts[3 * i] = x; mouthPts[3 * i + 1] = y; mouthPts[3 * i + 2] = hitZ(x, y)
        }
        return Built(V, N, UV, VM, F, headW, jaw, brow, unlit, top, cut, eyeZ, mouthPts)
    }

    /** Clustering on a grid (finer on the face), per UV island: (cluster of each vertex, the new faces, the cell of each cluster). */
    private fun cluster(V: FloatArray, F: IntArray, isl: IntArray, fc: Float, bc: Float): Triple<IntArray, IntArray, IntArray> {
        val n = V.size / 3
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
        for (v in 0 until n) { minX = min(minX, V[3 * v]); minY = min(minY, V[3 * v + 1]); minZ = min(minZ, V[3 * v + 2]) }
        val cellIds = HashMap<Long, Int>()
        val cellOf = IntArray(n)
        for (v in 0 until n) {
            val x = V[3 * v]; val y = V[3 * v + 1]
            val face = x > -0.75f && x < 0.75f && y > -1.15f && y < 1.05f
            val cs = if (face) fc else bc
            val gx = floor((x - minX) / cs).toLong(); val gy = floor((y - minY) / cs).toLong(); val gz = floor((V[3 * v + 2] - minZ) / cs).toLong()
            val key = ((gx * 100_003L + gy) * 100_003L + gz) * 2 + (if (face) 1 else 0)
            cellOf[v] = cellIds.getOrPut(key) { cellIds.size }
        }
        val clIds = HashMap<Long, Int>()
        val clOf = IntArray(n)
        val cellOfClusterList = ArrayList<Int>()
        for (v in 0 until n) {
            val key = cellOf[v].toLong() * 1_000_000_007L + isl[v]
            clOf[v] = clIds.getOrPut(key) { cellOfClusterList += cellOf[v]; clIds.size }
        }
        val seen = HashSet<Long>()
        val out = ArrayList<Int>()
        for (t in 0 until F.size / 3) {
            val a = F[3 * t]; val b = F[3 * t + 1]; val c = F[3 * t + 2]
            if (cellOf[a] == cellOf[b] || cellOf[b] == cellOf[c] || cellOf[a] == cellOf[c]) continue
            val x = clOf[a]; val y = clOf[b]; val z = clOf[c]
            val lo = min(x, min(y, z)); val hi = max(x, max(y, z)); val mid = x + y + z - lo - hi
            if (!seen.add((lo.toLong() shl 42) or (mid.toLong() shl 21) or hi.toLong())) continue
            out += x; out += y; out += z
        }
        return Triple(clOf, out.toIntArray(), cellOfClusterList.toIntArray())
    }

    /** A tile of the atlas: the part (u0..u1, v0..v1) of material [mat]'s texture, drawn at (x, y, w, h) in an atlas [size] wide. */
    class Tile(val mat: Int, val u0: Float, val u1: Float, val v0: Float, val v1: Float, val plain: Boolean, var x: Int = 0, var y: Int = 0, var w: Int = 0, var h: Int = 0)

    /** Packs the used part of each texture ([texSize] w, h per material, null for a plain colour) into one atlas. */
    fun planAtlas(b: Built, texSize: List<IntArray?>, size: Int = 2048, pad: Int = 4): Pair<List<Tile>, Int> {
        val tiles = ArrayList<Tile>()
        val mats = b.vertMat.toSet().sorted()
        for (m in mats) {
            val ts = texSize.getOrNull(m)
            if (ts == null) { tiles += Tile(m, 0f, 1f, 0f, 1f, true); continue }
            var u0 = Float.MAX_VALUE; var u1 = -Float.MAX_VALUE; var v0 = Float.MAX_VALUE; var v1 = -Float.MAX_VALUE
            for (v in b.vertMat.indices) if (b.vertMat[v] == m) {
                u0 = min(u0, b.srcUv[2 * v]); u1 = max(u1, b.srcUv[2 * v]); v0 = min(v0, b.srcUv[2 * v + 1]); v1 = max(v1, b.srcUv[2 * v + 1])
            }
            tiles += Tile(m, u0, u1, v0, v1, false)
        }
        fun pack(scale: Float): Int? {
            var x = 0; var y = 0; var row = 0
            val order = tiles.sortedByDescending { t -> if (t.plain) 8f else (t.v1 - t.v0) * texSize[t.mat]!![1] * scale }
            for (t in order) {
                val w = if (t.plain) 8 else max(8, ceil((t.u1 - t.u0) * texSize[t.mat]!![0] * scale).toInt())
                val h = if (t.plain) 8 else max(8, ceil((t.v1 - t.v0) * texSize[t.mat]!![1] * scale).toInt())
                if (w + 2 * pad > size) return null
                if (x + w + 2 * pad > size) { x = 0; y += row; row = 0 }
                if (y + h + 2 * pad > size) return null
                t.x = x + pad; t.y = y + pad; t.w = w; t.h = h
                x += w + 2 * pad; row = max(row, h + 2 * pad)
            }
            return y + row
        }
        var used = pack(1f)
        if (used == null) {
            var lo = 0.01f; var hi = 1f
            repeat(24) { val mid = (lo + hi) / 2; if (pack(mid) == null) hi = mid else lo = mid }
            used = pack(lo) ?: size
        }
        var h = 16
        while (h < used) h *= 2
        return tiles to min(h, size)
    }

    /** The atlas uv of every vertex (0..1 of an atlas [w] x [h]). */
    fun atlasUv(b: Built, tiles: List<Tile>, w: Int, h: Int): FloatArray {
        val byMat = tiles.associateBy { it.mat }
        val out = FloatArray(2 * b.vertMat.size)
        for (v in b.vertMat.indices) {
            val t = byMat[b.vertMat[v]] ?: continue
            if (t.plain) { out[2 * v] = (t.x + t.w / 2f) / w; out[2 * v + 1] = (t.y + t.h / 2f) / h; continue }
            val du = max(t.u1 - t.u0, 1e-9f); val dv = max(t.v1 - t.v0, 1e-9f)
            out[2 * v] = (t.x + (b.srcUv[2 * v] - t.u0) / du * t.w) / w
            out[2 * v + 1] = (t.y + (b.srcUv[2 * v + 1] - t.v0) / dv * t.h) / h
        }
        return out
    }

    /** The JCH2 file. */
    fun meshBytes(b: Built, auv: FloatArray): ByteArray {
        val nV = b.pos.size / 3; val nF = b.faces.size / 3
        val flagsPad = (4 - nV % 4) % 4
        val bb = ByteBuffer.allocate(12 + 4 * (3 * nV + 3 * nV + 2 * nV + 3 * nV) + nV + flagsPad + 12 * nF).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("JCH2".toByteArray(Charsets.US_ASCII)); bb.putInt(nV); bb.putInt(nF)
        for (a in listOf(b.pos, b.normals, auv, b.headW, b.jaw, b.brow)) for (f in a) bb.putFloat(f)
        for (v in 0 until nV) bb.put((if (b.unlit[v]) 1 else 0).toByte())
        repeat(flagsPad) { bb.put(0) }
        for (i in b.faces) bb.putInt(i)
        return bb.array()
    }
}
