package com.jarvis.android.avatar

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Electronic-circuit tracks laid on the face for the hologram look: paths that run along a grid and turn by 45 or 90 degrees, like the
 * tracks of a printed circuit, ending in round pads. Each point sits on a triangle of the mesh by barycentric weights (as the nodes of the
 * web do), so the tracks follow the relief and the movement of the head. Generated once from the rest pose, with a fixed seed. They keep
 * away from the eyes and the mouth.
 */
internal class CircuitTraces(mesh: HeadMesh) {
    val triA: IntArray
    val triB: IntArray
    val triC: IntArray
    val wu: FloatArray
    val wv: FloatArray
    val fade: FloatArray

    /** Pairs of point indices. */
    val segments: IntArray

    /** For each segment: the track it belongs to, and where it is along it (0 to 1), for the pulse of light that runs along the track. */
    val segTrack: IntArray
    val segAlong: FloatArray

    /** Points that end a track: they get a round pad. */
    val pads: IntArray

    /** For each track: 0 or 1. On the blue skin the tracks of kind 1 are deep blue, in the other looks all are gold. */
    val trackKind: IntArray
    val trackCount: Int
    val count: Int get() = triA.size

    init {
        val v = mesh.verts
        val nrm = mesh.normals
        val f = mesh.faces
        val nF = mesh.faceCount

        // the triangles a track can lie on: the front of the head's skin, not the hair, the neck's far end, the eyes or the mouth
        val usable = ArrayList<Int>()
        for (t in 0 until nF) {
            val a = f[3 * t]; val b = f[3 * t + 1]; val c = f[3 * t + 2]
            if (mesh.faceGroup[t] > 1.5f || mesh.faceGroup[t] < 0.5f) continue
            if (mesh.paint[a] != 0 || mesh.paint[b] != 0 || mesh.paint[c] != 0) continue
            if ((mesh.fade[a] + mesh.fade[b] + mesh.fade[c]) / 3f < 0.55f) continue
            if ((nrm[3 * a + 2] + nrm[3 * b + 2] + nrm[3 * c + 2]) / 3f < 0.28f) continue
            usable += t
        }

        // where not to go: round the eyes and the mouth
        val avoid = ArrayList<FloatArray>() // x, y, radius (in the plane of the face)
        for (name in listOf("eye_l", "eye_r", "lips_out")) {
            val ring = mesh.landmarks[name] ?: continue
            var x = 0f; var y = 0f
            for (i in ring) { x += v[3 * i]; y += v[3 * i + 1] }
            x /= ring.size; y /= ring.size
            var rad = 0f
            for (i in ring) rad = max(rad, sqrt((v[3 * i] - x) * (v[3 * i] - x) + (v[3 * i + 1] - y) * (v[3 * i + 1] - y)))
            avoid += floatArrayOf(x, y, rad * (if (name == "lips_out") 1.35f else 1.55f))
        }

        // a grid over the plane of the face, to find the triangle under a point
        val cell = 0.06f
        fun cellOf(x: Float) = Math.floor((x / cell).toDouble()).toInt()
        fun key(i: Int, j: Int) = ((i + 1024).toLong() shl 20) or (j + 1024).toLong()
        val grid = HashMap<Long, MutableList<Int>>()
        for (t in usable) {
            val a = f[3 * t]; val b = f[3 * t + 1]; val c = f[3 * t + 2]
            val x0 = min(v[3 * a], min(v[3 * b], v[3 * c])); val x1 = max(v[3 * a], max(v[3 * b], v[3 * c]))
            val y0 = min(v[3 * a + 1], min(v[3 * b + 1], v[3 * c + 1])); val y1 = max(v[3 * a + 1], max(v[3 * b + 1], v[3 * c + 1]))
            for (i in cellOf(x0)..cellOf(x1)) for (j in cellOf(y0)..cellOf(y1)) grid.getOrPut(key(i, j)) { ArrayList() } += t
        }

        val hitTri = IntArray(1); val hitU = FloatArray(1); val hitV = FloatArray(1)
        /** The triangle under (x, y), the front-most one; false when there is none. */
        fun locate(x: Float, y: Float): Boolean {
            var bestZ = -1e9f; var found = false
            val list = grid[key(cellOf(x), cellOf(y))] ?: return false
            for (t in list) {
                val a = f[3 * t]; val b = f[3 * t + 1]; val c = f[3 * t + 2]
                val ax = v[3 * a]; val ay = v[3 * a + 1]
                val d = (v[3 * b + 1] - v[3 * c + 1]) * (ax - v[3 * c]) + (v[3 * c] - v[3 * b]) * (ay - v[3 * c + 1])
                if (abs(d) < 1e-9f) continue
                val l1 = ((v[3 * b + 1] - v[3 * c + 1]) * (x - v[3 * c]) + (v[3 * c] - v[3 * b]) * (y - v[3 * c + 1])) / d
                val l2 = ((v[3 * c + 1] - ay) * (x - v[3 * c]) + (ax - v[3 * c]) * (y - v[3 * c + 1])) / d
                val l3 = 1f - l1 - l2
                if (l1 < 0f || l2 < 0f || l3 < 0f) continue
                val z = l1 * v[3 * a + 2] + l2 * v[3 * b + 2] + l3 * v[3 * c + 2]
                if (z > bestZ) { bestZ = z; found = true; hitTri[0] = t; hitU[0] = l2; hitV[0] = l3 }
            }
            return found
        }

        // the tracks: walks on a lattice, each step one lattice unit along one of eight directions
        val pitch = 0.036f
        val dirs = arrayOf(intArrayOf(1, 0), intArrayOf(1, 1), intArrayOf(0, 1), intArrayOf(-1, 1), intArrayOf(-1, 0), intArrayOf(-1, -1), intArrayOf(0, -1), intArrayOf(1, -1))
        val rnd = Random(2026)
        val taken = HashSet<Long>()
        val pTri = ArrayList<Int>(); val pU = ArrayList<Float>(); val pV = ArrayList<Float>()
        val sA = ArrayList<Int>(); val sB = ArrayList<Int>(); val sTrack = ArrayList<Int>(); val sAlong = ArrayList<Float>()
        val padList = ArrayList<Int>()
        var tracks = 0
        val kinds = ArrayList<Int>()

        fun free(ix: Int, iy: Int): Boolean {
            if (taken.contains(key(ix, iy))) return false
            val x = ix * pitch; val y = iy * pitch
            for (a in avoid) if ((x - a[0]) * (x - a[0]) + (y - a[1]) * (y - a[1]) < a[2] * a[2]) return false
            return true
        }

        for (attempt in 0 until 6000) {
            val sx = rnd.nextInt(-25, 26); val sy = rnd.nextInt(-27, 24)
            if (!free(sx, sy) || !locate(sx * pitch, sy * pitch)) continue
            var dir = rnd.nextInt(8)
            val target = rnd.nextInt(9, 34)
            val cells = ArrayList<IntArray>()
            cells += intArrayOf(sx, sy)
            var cx = sx; var cy = sy
            while (cells.size < target) {
                val r = rnd.nextFloat()
                if (cells.size > 2 && r < 0.26f) dir = (dir + (if (rnd.nextBoolean()) 1 else 7)) % 8                 // a turn of 45 degrees
                else if (cells.size > 2 && r < 0.31f) dir = (dir + (if (rnd.nextBoolean()) 2 else 6)) % 8           // or of 90
                val nx = cx + dirs[dir][0]; val ny = cy + dirs[dir][1]
                if (!free(nx, ny) || !locate(nx * pitch, ny * pitch)) break
                cells += intArrayOf(nx, ny); cx = nx; cy = ny
            }
            if (cells.size < 6) continue
            val first = pTri.size
            for (c in cells) {
                taken += key(c[0], c[1])
                locate(c[0] * pitch, c[1] * pitch)
                pTri += hitTri[0]; pU += hitU[0]; pV += hitV[0]
            }
            val n = cells.size
            for (k in 0 until n - 1) {
                sA += first + k; sB += first + k + 1; sTrack += tracks; sAlong += k / (n - 1f)
            }
            padList += first; padList += first + n - 1
            kinds += if (rnd.nextFloat() < 0.40f) 1 else 0
            tracks++
        }

        val n = pTri.size
        triA = IntArray(n) { f[3 * pTri[it]] }; triB = IntArray(n) { f[3 * pTri[it] + 1] }; triC = IntArray(n) { f[3 * pTri[it] + 2] }
        wu = FloatArray(n) { pU[it] }; wv = FloatArray(n) { pV[it] }
        fade = FloatArray(n) {
            val s = 1f - pU[it] - pV[it]
            s * mesh.fade[triA[it]] + pU[it] * mesh.fade[triB[it]] + pV[it] * mesh.fade[triC[it]]
        }
        segments = IntArray(sA.size * 2) { if (it % 2 == 0) sA[it / 2] else sB[it / 2] }
        segTrack = IntArray(sTrack.size) { sTrack[it] }
        segAlong = FloatArray(sAlong.size) { sAlong[it] }
        pads = padList.toIntArray()
        trackKind = kinds.toIntArray()
        trackCount = tracks
    }
}
