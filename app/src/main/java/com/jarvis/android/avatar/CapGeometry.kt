package com.jarvis.android.avatar

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A cap for the head: a regular dome (rings of points around the head, from the edge of the cap up to the top) and a visor along the front
 * of the edge. The dome is not cut out of the head's triangles (that left holes and a ragged edge): it is a grid, computed once in the rest
 * pose from the outer hull of the skull, so it is whole by construction and its edge is a clean curve.
 *
 * Each point of the grid is tied to the three nearest points of the head's skin, by weights, and stands a little off it along their
 * normals: the renderer builds the positions each frame from the posed head, so the cap follows every movement.
 */
internal class CapGeometry(mesh: HeadMesh) {
    /** For each point of the dome: three mesh vertices, their weights, and the distance from the skin along the normal. */
    val bind: IntArray
    val weight: FloatArray
    val stand: FloatArray
    val tris: IntArray

    /** Ring [r] (0 is the edge, up to [rings] - 1) and column [c] of the dome: point `r * columns + c`. The last point is the top. */
    val rings = RINGS
    val columns = COLUMNS
    val top: Int get() = RINGS * COLUMNS

    /** The columns of the edge, from the left of the visor to its right (front of the cap, seen from the head). */
    val visor: IntArray

    /** For each triangle of the mesh: hidden under the cap (hair only). For each lock of the hair: the same. */
    val hiddenFace: BooleanArray
    val hiddenLock: BooleanArray
    val midX: Float

    companion object {
        const val RINGS = 14
        const val COLUMNS = 64

        /** How far the dome stands from the head's skin. */
        const val OFFSET = 0.055f

        /** Longest reach of the visor, in head half-heights, and the angle (from the front) it spreads over. */
        const val VISOR = 0.52f
        const val VISOR_SPAN = 0.95f

        /** Height of the edge of the cap over the point (side, depth) of the rest pose. */
        fun rim(side: Float, z: Float): Float {
            val t = ((-z - 0.02f) / 0.53f).coerceIn(0f, 1f)
            val sides = (abs(side) / 0.7f).coerceIn(0f, 1f)
            return 0.395f + 0.03f * sides - 0.32f * (t * t * (3f - 2f * t)) + 0.04f * (1f - t) * (1f - sides)
        }
    }

    init {
        val v = mesh.verts
        val nrm = mesh.normals
        val f = mesh.faces
        val nF = mesh.faceCount
        val nV = mesh.vertexCount
        midX = if (mesh.eyeCentre.size >= 6) 0.5f * (mesh.eyeCentre[0] + mesh.eyeCentre[3]) else 0f
        val cy = 0.10f; val cz = -0.10f          // the centre the directions are taken from

        // the skin of the head (not the hair, the eyes, the mouth, the neck), the ears left out
        val onSkin = BooleanArray(nV)
        for (t in 0 until nF) if (mesh.faceGroup[t] in 0.5f..1.5f) for (k in 0..2) onSkin[f[3 * t + k]] = true
        val skin = ArrayList<Int>()
        for (i in 0 until nV) {
            if (!onSkin[i] || mesh.paint[i] != 0 || mesh.fade[i] < 0.5f) continue
            val side = v[3 * i] - midX
            val ex = (abs(side) - 0.72f) / 0.11f; val ey = (v[3 * i + 1] - 0.07f) / 0.22f; val ez = (v[3 * i + 2] + 0.14f) / 0.22f
            if (ex * ex + ey * ey + ez * ez < 1.6f) continue
            if (v[3 * i + 1] < cy - 0.15f) continue
            skin += i
        }

        // the outer hull of the skull: the farthest skin point in each (azimuth, elevation) bin, then filled and smoothed
        val nb = 72; val eb = 26
        val e0 = -0.35f; val e1 = (Math.PI / 2).toFloat()
        val hull = FloatArray(nb * eb)
        for (i in skin) {
            val x = v[3 * i] - midX; val y = v[3 * i + 1] - cy; val z = v[3 * i + 2] - cz
            val r = sqrt(x * x + y * y + z * z)
            val az = atan2(x, z) + Math.PI.toFloat()
            val el = kotlin.math.asin((y / r).coerceIn(-1f, 1f))
            if (el < e0) continue
            val bi = ((az / (2 * Math.PI.toFloat())) * nb).toInt().coerceIn(0, nb - 1)
            val bj = (((el - e0) / (e1 - e0)) * eb).toInt().coerceIn(0, eb - 1)
            if (r > hull[bi * eb + bj]) hull[bi * eb + bj] = r
        }
        // fill the empty bins from their neighbours (the top pole has none of its own), then smooth
        for (pass in 0 until 12) {
            for (bi in 0 until nb) for (bj in 0 until eb) {
                if (hull[bi * eb + bj] > 0f) continue
                var sum = 0f; var cnt = 0
                for (di in -1..1) for (dj in -1..1) {
                    val ni = (bi + di + nb) % nb; val nj = bj + dj
                    if (nj < 0 || nj >= eb) continue
                    val h = hull[ni * eb + nj]
                    if (h > 0f) { sum += h; cnt++ }
                }
                if (cnt > 0) hull[bi * eb + bj] = sum / cnt
            }
        }
        val smooth = FloatArray(nb * eb)
        for (bi in 0 until nb) for (bj in 0 until eb) {
            var sum = 0f; var cnt = 0
            for (di in -2..2) for (dj in -1..1) {
                val nj = bj + dj
                if (nj < 0 || nj >= eb) continue
                sum += hull[((bi + di + nb) % nb) * eb + nj]; cnt++
            }
            smooth[bi * eb + bj] = sum / cnt
        }
        fun radius(az: Float, el: Float): Float {
            val fa = (az / (2 * Math.PI.toFloat())) * nb - 0.5f
            val fe = ((el - e0) / (e1 - e0)) * eb - 0.5f
            val i0 = Math.floor(fa.toDouble()).toInt(); val j0 = Math.floor(fe.toDouble()).toInt()
            val ta = fa - i0; val te = fe - j0
            fun at(i: Int, j: Int) = smooth[((i % nb + nb) % nb) * eb + j.coerceIn(0, eb - 1)]
            return (at(i0, j0) * (1 - ta) + at(i0 + 1, j0) * ta) * (1 - te) + (at(i0, j0 + 1) * (1 - ta) + at(i0 + 1, j0 + 1) * ta) * te
        }

        // the point of the hull in a direction
        fun hullPoint(az: Float, el: Float, out: FloatArray) {
            val r = radius(az, el)
            // az is measured from the back (+ pi), so that the front is at pi
            val a = az - Math.PI.toFloat()
            out[0] = midX + r * cos(el) * sin(a); out[1] = cy + r * sin(el); out[2] = cz + r * cos(el) * cos(a)
        }

        // the edge of the cap in each column: the elevation where the hull reaches the height of the edge
        val edgeEl = FloatArray(COLUMNS)
        val p = FloatArray(3)
        for (c in 0 until COLUMNS) {
            val az = (c + 0.5f) / COLUMNS * 2 * Math.PI.toFloat()
            var found = 0.55f
            var el = e0
            while (el < e1) {
                hullPoint(az, el, p)
                if (p[1] >= rim(p[0] - midX, p[2])) { found = el; break }
                el += 0.01f
            }
            edgeEl[c] = found
        }
        // a little smoothing round the head, so the edge is a curve and not a staircase
        val edgeSmooth = FloatArray(COLUMNS) { c -> (edgeEl[(c + COLUMNS - 1) % COLUMNS] + 2f * edgeEl[c] + edgeEl[(c + 1) % COLUMNS]) / 4f }

        // the points of the grid, tied to the skin
        val n = RINGS * COLUMNS + 1
        bind = IntArray(3 * n); weight = FloatArray(3 * n); stand = FloatArray(n)
        val skinArr = skin.toIntArray()
        val h = FloatArray(3)
        for (q in 0 until n) {
            val az: Float; val el: Float
            if (q == n - 1) { az = 0f; el = e1 } else {
                val r = q / COLUMNS; val c = q % COLUMNS
                az = (c + 0.5f) / COLUMNS * 2 * Math.PI.toFloat()
                el = edgeSmooth[c] + (e1 - edgeSmooth[c]) * Math.pow((r / RINGS.toDouble()), 0.9).toFloat()
            }
            hullPoint(az, el, h)
            // the three nearest skin points
            var b0 = -1; var b1 = -1; var b2 = -1
            var d0 = Float.MAX_VALUE; var d1 = Float.MAX_VALUE; var d2 = Float.MAX_VALUE
            for (i in skinArr) {
                val dx = v[3 * i] - h[0]; val dy = v[3 * i + 1] - h[1]; val dz = v[3 * i + 2] - h[2]
                val d = dx * dx + dy * dy + dz * dz
                if (d < d2) {
                    if (d < d1) {
                        if (d < d0) { b2 = b1; d2 = d1; b1 = b0; d1 = d0; b0 = i; d0 = d } else { b2 = b1; d2 = d1; b1 = i; d1 = d }
                    } else { b2 = i; d2 = d }
                }
            }
            val ws = floatArrayOf(1f / (sqrt(d0) + 0.01f), 1f / (sqrt(d1) + 0.01f), 1f / (sqrt(d2) + 0.01f))
            val sum = ws[0] + ws[1] + ws[2]
            bind[3 * q] = b0; bind[3 * q + 1] = b1; bind[3 * q + 2] = b2
            for (k in 0..2) weight[3 * q + k] = ws[k] / sum
            // the distance from the bound point to the hull along the average normal, plus the offset
            var px = 0f; var py = 0f; var pz = 0f; var nx = 0f; var ny = 0f; var nz = 0f
            for (k in 0..2) {
                val i = bind[3 * q + k]; val w = weight[3 * q + k]
                px += w * v[3 * i]; py += w * v[3 * i + 1]; pz += w * v[3 * i + 2]
                nx += w * nrm[3 * i]; ny += w * nrm[3 * i + 1]; nz += w * nrm[3 * i + 2]
            }
            val nl = max(sqrt(nx * nx + ny * ny + nz * nz), 1e-6f)
            val along = ((h[0] - px) * nx + (h[1] - py) * ny + (h[2] - pz) * nz) / nl
            stand[q] = (along + OFFSET).coerceIn(0.02f, 0.20f)
        }

        val tri = ArrayList<Int>()
        for (r in 0 until RINGS - 1) for (c in 0 until COLUMNS) {
            val c2 = (c + 1) % COLUMNS
            val a = r * COLUMNS + c; val b = r * COLUMNS + c2; val d = (r + 1) * COLUMNS + c; val e = (r + 1) * COLUMNS + c2
            tri += a; tri += b; tri += d
            tri += b; tri += e; tri += d
        }
        for (c in 0 until COLUMNS) { tri += (RINGS - 1) * COLUMNS + c; tri += (RINGS - 1) * COLUMNS + (c + 1) % COLUMNS; tri += RINGS * COLUMNS }
        tris = tri.toIntArray()

        // the visor: the columns at the front of the edge (the front is the middle of the grid, at azimuth pi)
        val vis = ArrayList<Int>()
        for (c in 0 until COLUMNS) {
            val az = (c + 0.5f) / COLUMNS * 2 * Math.PI.toFloat() - Math.PI.toFloat()
            if (abs(az) < VISOR_SPAN) vis += c
        }
        visor = vis.toIntArray()

        // the hair under the cap is not drawn
        hiddenFace = BooleanArray(nF)
        for (t in 0 until nF) {
            if (mesh.faceGroup[t] <= 1.5f) continue
            val a = f[3 * t]; val b = f[3 * t + 1]; val c = f[3 * t + 2]
            val y = (v[3 * a + 1] + v[3 * b + 1] + v[3 * c + 1]) / 3f
            val z = (v[3 * a + 2] + v[3 * b + 2] + v[3 * c + 2]) / 3f
            val x = (v[3 * a] + v[3 * b] + v[3 * c]) / 3f - midX
            hiddenFace[t] = y > rim(x, z) - 0.02f
        }
        hiddenLock = BooleanArray(mesh.lockCount) { l ->
            val root = mesh.lockFirst + l * 3 * mesh.lockRows + 1
            v[3 * root + 1] > rim(v[3 * root] - midX, v[3 * root + 2]) - 0.02f
        }
    }
}
