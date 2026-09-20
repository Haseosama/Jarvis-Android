package com.jarvis.android.avatar

import kotlin.math.max
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * The web drawn over the head: nodes spread evenly over the mesh (a Poisson-disc sample, closer together on the eyes and lips)
 * and joined to their nearest neighbours. Each node sits on a triangle by barycentric weights, so it follows the jaw and the
 * head as the mesh is posed. Generated once at start-up from the rest pose, with a fixed seed.
 */
internal class NetworkWeb(mesh: HeadMesh) {
    val triA: IntArray
    val triB: IntArray
    val triC: IntArray
    val wu: FloatArray
    val wv: FloatArray
    val fade: FloatArray
    val edges: IntArray // pairs of node indices
    val count: Int get() = triA.size

    init {
        val v = mesh.verts
        val nrm = mesh.normals
        val f = mesh.faces
        val nF = mesh.faceCount
        // area of each triangle of the head (the far end of the neck is left out), for area-weighted sampling
        val cumulative = DoubleArray(nF)
        var total = 0.0
        for (t in 0 until nF) {
            val a = f[3 * t]; val b = f[3 * t + 1]; val c = f[3 * t + 2]
            val avgFade = (mesh.fade[a] + mesh.fade[b] + mesh.fade[c]) / 3f
            val plain = mesh.paint[a] == 0 && mesh.paint[b] == 0 && mesh.paint[c] == 0 // no nodes on the eyeballs or in the mouth
            if (avgFade > 0.2f && plain) {
                val abx = v[3 * b] - v[3 * a]; val aby = v[3 * b + 1] - v[3 * a + 1]; val abz = v[3 * b + 2] - v[3 * a + 2]
                val acx = v[3 * c] - v[3 * a]; val acy = v[3 * c + 1] - v[3 * a + 1]; val acz = v[3 * c + 2] - v[3 * a + 2]
                val cx = aby * acz - abz * acy; val cy = abz * acx - abx * acz; val cz = abx * acy - aby * acx
                total += 0.5 * sqrt((cx * cx + cy * cy + cz * cz).toDouble())
            }
            cumulative[t] = total
        }
        // denser round the eyes and the mouth
        val dense = ArrayList<FloatArray>() // x, y, z, radius
        for (name in listOf("eye_l", "eye_r", "lips_out")) {
            val ring = mesh.landmarks.getValue(name)
            var x = 0f; var y = 0f; var z = 0f; var rad = 0f
            for (i in ring) { x += v[3 * i]; y += v[3 * i + 1]; z += v[3 * i + 2] }
            x /= ring.size; y /= ring.size; z /= ring.size
            for (i in ring) rad = max(rad, sqrt((v[3 * i] - x) * (v[3 * i] - x) + (v[3 * i + 1] - y) * (v[3 * i + 1] - y) + (v[3 * i + 2] - z) * (v[3 * i + 2] - z)))
            dense += floatArrayOf(x, y, z, rad * 1.5f)
        }

        val r0 = R0
        val rnd = Random(31)
        val accepted = ArrayList<Int>()
        val px = ArrayList<Float>(); val py = ArrayList<Float>(); val pz = ArrayList<Float>(); val pr = ArrayList<Float>()
        val ta = ArrayList<Int>(); val tb = ArrayList<Int>(); val tc = ArrayList<Int>()
        val wa = ArrayList<Float>(); val wb = ArrayList<Float>(); val fd = ArrayList<Float>()
        val grid = HashMap<Long, MutableList<Int>>()
        fun cell(x: Float) = Math.floor((x / r0).toDouble()).toInt()
        fun key(i: Int, j: Int, k: Int) = ((i + 512).toLong() shl 40) or ((j + 512).toLong() shl 20) or (k + 512).toLong()
        val tries = 70_000
        for (n in 0 until tries) {
            val target = rnd.nextDouble() * total
            var lo = 0; var hi = nF - 1
            while (lo < hi) { val mid = (lo + hi) ushr 1; if (cumulative[mid] < target) lo = mid + 1 else hi = mid }
            val t = lo
            val a = f[3 * t]; val b = f[3 * t + 1]; val c = f[3 * t + 2]
            if ((mesh.fade[a] + mesh.fade[b] + mesh.fade[c]) / 3f <= 0.2f) continue
            if (mesh.paint[a] != 0 || mesh.paint[b] != 0 || mesh.paint[c] != 0) continue
            var u = rnd.nextFloat(); var w = rnd.nextFloat()
            if (u + w > 1f) { u = 1f - u; w = 1f - w }
            val s = 1f - u - w
            val x = s * v[3 * a] + u * v[3 * b] + w * v[3 * c]
            val y = s * v[3 * a + 1] + u * v[3 * b + 1] + w * v[3 * c + 1]
            val z = s * v[3 * a + 2] + u * v[3 * b + 2] + w * v[3 * c + 2]
            // the spacing widens smoothly away from the eyes and the mouth: a hard edge would line the nodes up along it
            var radius = r0
            for (d in dense) {
                val dx = x - d[0]; val dy = y - d[1]; val dz = z - d[2]
                val q = (dx * dx + dy * dy + dz * dz) / (d[3] * d[3] * 2.2f)
                if (q < 1f) radius = minOf(radius, r0 * (0.68f + 0.32f * q))
            }
            val ci = cell(x); val cj = cell(y); val ck = cell(z)
            var ok = true
            loop@ for (i in -1..1) for (j in -1..1) for (k in -1..1) {
                val list = grid[key(ci + i, cj + j, ck + k)] ?: continue
                for (o in list) {
                    val dx = x - px[o]; val dy = y - py[o]; val dz = z - pz[o]
                    val m = minOf(radius, pr[o])
                    if (dx * dx + dy * dy + dz * dz < m * m) { ok = false; break@loop }
                }
            }
            if (!ok) continue
            val idx = px.size
            px += x; py += y; pz += z; pr += radius
            ta += a; tb += b; tc += c; wa += u; wb += w
            fd += s * mesh.fade[a] + u * mesh.fade[b] + w * mesh.fade[c]
            grid.getOrPut(key(ci, cj, ck)) { ArrayList() } += idx
            accepted += idx
        }
        val n = px.size
        triA = IntArray(n) { ta[it] }; triB = IntArray(n) { tb[it] }; triC = IntArray(n) { tc[it] }
        wu = FloatArray(n) { wa[it] }; wv = FloatArray(n) { wb[it] }
        fade = FloatArray(n) { fd[it] }

        // each node is joined to its nearest neighbours (at most 5, and not too far)
        val pairs = HashSet<Long>()
        val nearIdx = IntArray(5); val nearD = FloatArray(5)
        for (i in 0 until n) {
            nearIdx.fill(-1); nearD.fill(Float.MAX_VALUE)
            for (j in 0 until n) {
                if (j == i) continue
                val dx = px[i] - px[j]; val dy = py[i] - py[j]; val dz = pz[i] - pz[j]
                val d = dx * dx + dy * dy + dz * dz
                if (d >= nearD[4]) continue
                var p = 4
                while (p > 0 && nearD[p - 1] > d) { nearD[p] = nearD[p - 1]; nearIdx[p] = nearIdx[p - 1]; p-- }
                nearD[p] = d; nearIdx[p] = j
            }
            val limit = 2.1f * r0
            for (k in 0 until 5) {
                val j = nearIdx[k]
                if (j < 0 || nearD[k] > limit * limit) continue
                pairs += (minOf(i, j).toLong() shl 32) or maxOf(i, j).toLong()
            }
        }
        val list = pairs.toLongArray()
        edges = IntArray(list.size * 2) { if (it % 2 == 0) (list[it / 2] shr 32).toInt() else (list[it / 2] and 0xFFFFFFFFL).toInt() }
    }

    companion object {
        /** Minimum distance between two nodes, in head-half-heights. */
        const val R0 = 0.05f
    }
}
