package com.jarvis.android.avatar

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * The Haseo face: the Classique head scan reshaped as in Jarvis 2.0's "Classique" avatar (src/avatar/HeadMesh.js, refineClassicFace):
 * leaner cheeks, a squarer jaw, a firmer brow ridge and chin, wider open eyes with larger globes, the left eye given the exact
 * opening of the right one. Only the positions move; colours, paint and the rig are untouched.
 */
internal object HaseoFace {
    private const val EYE_OPEN_WIDTH = 0.15f
    private const val EYE_OPEN_UP = 0.72f
    private const val EYE_OPEN_DOWN = 0.46f
    private const val EYE_GLOBE_SCALE = 1.16f
    private const val EYE_RELAX_PASSES = 36
    private const val EYE_RELAX_STRENGTH = 0.85f

    fun smooth(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** A new head with Haseo's proportions (the Classique mesh given is never modified). */
    fun refine(mesh: HeadMesh): HeadMesh {
        val src = mesh.verts
        val verts = src.copyOf()
        val ec = mesh.eyeCentre
        val centreX = if (ec.size >= 6) 0.5f * (ec[0] + ec[3]) else 0f
        val headVertices = minOf(if (mesh.nHead > 0) mesh.nHead else mesh.vertexCount, mesh.vertexCount)
        for (i in 0 until headVertices) {
            val x = src[3 * i]; val y = src[3 * i + 1]; val z = src[3 * i + 2]
            val jaw = mesh.jaw[i]; val brow = mesh.brow[i]
            if (mesh.lipMask[i] > 0.05f) continue
            val front = smooth(-0.08f, 0.24f, z)
            if (front <= 0f) continue
            val absX = abs(x - centreX)
            val cheekSide = smooth(0.12f, 0.26f, absX)
            val cheek = smooth(-0.35f, -0.12f, y) * (1 - smooth(0.28f, 0.48f, y)) * cheekSide * front
            val jawBand = smooth(-1.00f, -0.76f, y) * (1 - smooth(-0.46f, -0.30f, y)) * front * jaw
            val jawCorner = smooth(0.22f, 0.40f, absX) * (1 - smooth(0.56f, 0.72f, absX)) *
                smooth(-0.78f, -0.66f, y) * (1 - smooth(-0.38f, -0.26f, y)) * front * jaw
            val chinFront = smooth(-0.84f, -0.68f, y) * (1 - smooth(-0.55f, -0.48f, y)) * (1 - smooth(0.23f, 0.43f, absX)) * front * jaw
            val underChin = smooth(-0.99f, -0.86f, y) * (1 - smooth(-0.79f, -0.70f, y)) * front * jaw
            val browRidge = smooth(0.04f, 0.14f, y) * (1 - smooth(0.32f, 0.42f, y)) * front * brow
            // leaner cheeks, a distinct squarer jaw angle; the nose itself is left alone
            val widthScale = 1 - 0.060f * cheek + 0.100f * jawBand + 0.075f * jawCorner
            if (widthScale == 1f && chinFront == 0f && underChin == 0f && browRidge == 0f && cheek == 0f) continue
            verts[3 * i] = centreX + (x - centreX) * widthScale
            verts[3 * i + 1] = y + 0.045f * underChin - 0.014f * jawCorner
            verts[3 * i + 2] = z - 0.026f * cheek + 0.052f * browRidge + 0.045f * chinFront - 0.052f * underChin
        }
        val lid = openEyes(mesh, verts, headVertices)
        val normals = refitHeadNormals(mesh, verts, headVertices)
        return mesh.copy(verts = verts, normals = normals, lid = lid)
    }

    /**
     * Wider, taller lid openings (a rounder, more open gaze) with larger globes to fill them. The displacement is a smooth radial field
     * round each opening, so nothing tears. Returns the lid weights, scaled so a blink still closes the larger opening.
     */
    fun openEyes(mesh: HeadMesh, verts: FloatArray, headVertices: Int): FloatArray {
        val centres = mesh.eyeCentre
        val rim = mesh.eyelidRim
        val lid = mesh.lid.copyOf()
        if (centres.size < 6 || rim.isEmpty()) return lid
        val eyes = centres.size / 3
        val x0 = FloatArray(eyes) { Float.POSITIVE_INFINITY }; val x1 = FloatArray(eyes) { Float.NEGATIVE_INFINITY }
        val y0 = FloatArray(eyes) { Float.POSITIVE_INFINITY }; val y1 = FloatArray(eyes) { Float.NEGATIVE_INFINITY }
        for (k in 0 until rim.size / 3) for (vi in intArrayOf(rim[3 * k], rim[3 * k + 1])) {
            val x = mesh.verts[3 * vi]; val y = mesh.verts[3 * vi + 1]
            var best = 0
            for (e in 1 until eyes) if (abs(centres[3 * e] - x) < abs(centres[3 * best] - x)) best = e
            if (x < x0[best]) x0[best] = x
            if (x > x1[best]) x1[best] = x
            if (y < y0[best]) y0[best] = y
            if (y > y1[best]) y1[best] = y
        }
        val ox = FloatArray(eyes) { 0.5f * (x0[it] + x1[it]) }
        val oy = FloatArray(eyes) { 0.5f * (y0[it] + y1[it]) }
        val hw = FloatArray(eyes) { maxOf(0.5f * (x1[it] - x0[it]), 1e-3f) }
        for (i in 0 until headVertices) {
            val x = mesh.verts[3 * i]; val y = mesh.verts[3 * i + 1]; val z = mesh.verts[3 * i + 2]
            if (z < 0.1f) continue
            var best = 0
            for (e in 1 until eyes) if (abs(ox[e] - x) < abs(ox[best] - x)) best = e
            val dx = x - ox[best]; val dy = y - oy[best]
            val rho = hypot(dx / (hw[best] * 2f), dy / (hw[best] * 1.45f))
            val w = 1 - smooth(0.42f, 1.0f, rho)
            if (w <= 0f) continue
            val up = if (dy > 0) EYE_OPEN_UP else EYE_OPEN_DOWN
            verts[3 * i] = ox[best] + dx * (1 + EYE_OPEN_WIDTH * w)
            verts[3 * i + 1] = oy[best] + dy * (1 + up * w)
            if (lid[i] != 0f) lid[i] *= 1 + 0.9f * w
        }
        if (eyes == 2) matchEyeShape(mesh, verts, headVertices, centres)
        for (e in mesh.eyeFirst.indices) {
            val cx = centres[3 * e]; val cy = centres[3 * e + 1]; val cz = centres[3 * e + 2]
            val end = minOf(mesh.vertexCount, mesh.eyeFirst[e] + mesh.eyeCount[e])
            for (i in mesh.eyeFirst[e] until end) {
                verts[3 * i] = cx + (mesh.verts[3 * i] - cx) * EYE_GLOBE_SCALE
                verts[3 * i + 1] = cy + (mesh.verts[3 * i + 1] - cy) * EYE_GLOBE_SCALE
                verts[3 * i + 2] = cz + (mesh.verts[3 * i + 2] - cz) * EYE_GLOBE_SCALE
            }
        }
        return lid
    }

    private const val BINS = 14

    /** The upper and lower lid profile of one eye, in bins across its width from the inner corner. */
    private class EyeShape(val x0: Float, val x1: Float, val inwardPositive: Boolean, val low: FloatArray, val up: FloatArray) {
        val width = maxOf(x1 - x0, 1e-3f)
        fun u(x: Float) = if (inwardPositive) (x1 - x) / width else (x - x0) / width
        fun at(arr: FloatArray, u: Float): Float {
            val f = u.coerceIn(0f, 1f) * BINS - 0.5f
            val i0 = floor(f).toInt().coerceIn(0, BINS - 1)
            val i1 = minOf(BINS - 1, i0 + 1)
            val t = (f - i0).coerceIn(0f, 1f)
            return arr[i0] * (1 - t) + arr[i1] * t
        }
    }

    private fun analyse(verts: FloatArray, rim: IntArray, centres: FloatArray, e: Int, centreX: Float): EyeShape? {
        val lists = arrayOf(ArrayList<Int>(), ArrayList<Int>())
        var x0 = Float.POSITIVE_INFINITY; var x1 = Float.NEGATIVE_INFINITY
        for (k in 0 until rim.size / 3) for (vi in intArrayOf(rim[3 * k], rim[3 * k + 1])) {
            val x = verts[3 * vi]
            if (abs(centres[3 * e] - x) > abs(centres[3 * (1 - e)] - x)) continue
            lists[if (rim[3 * k + 2] == 1) 1 else 0] += vi
            if (x < x0) x0 = x
            if (x > x1) x1 = x
        }
        if (x0 > x1) return null
        val inward = centreX > centres[3 * e]
        val probe = EyeShape(x0, x1, inward, FloatArray(0), FloatArray(0))
        val profiles = lists.map { list ->
            val sum = FloatArray(BINS); val cnt = FloatArray(BINS)
            for (vi in list) {
                val b = floor(probe.u(verts[3 * vi]).coerceIn(0f, 0.9999f) * BINS).toInt()
                sum[b] += verts[3 * vi + 1] - centres[3 * e + 1]; cnt[b] += 1f
            }
            val v = FloatArray(BINS) { if (cnt[it] > 0) sum[it] / cnt[it] else Float.NaN }
            var last = -1
            for (b in 0 until BINS) {
                if (v[b].isNaN()) continue
                if (last >= 0) for (q in last + 1 until b) v[q] = v[last] + (v[b] - v[last]) * (q - last) / (b - last)
                else for (q in 0 until b) v[q] = v[b]
                last = b
            }
            if (last < 0) return null
            for (q in last + 1 until BINS) v[q] = v[last]
            v
        }
        return EyeShape(x0, x1, inward, profiles[0], profiles[1])
    }

    /**
     * Gives the viewer's left eye the exact opening of the right one, mirrored about the face's middle: same width, same lid profiles,
     * corners at mirrored places. The skin around follows through a smooth field, then is relaxed so no fold is left.
     */
    private fun matchEyeShape(mesh: HeadMesh, verts: FloatArray, headVertices: Int, centres: FloatArray) {
        val rim = mesh.eyelidRim
        val srcEye = if (centres[0] > centres[3]) 0 else 1
        val dst = 1 - srcEye
        val centreX = 0.5f * (centres[0] + centres[3])
        val s = analyse(verts, rim, centres, srcEye, centreX) ?: return
        val d = analyse(verts, rim, centres, dst, centreX) ?: return
        val innerT = if (d.inwardPositive) 2 * centreX - s.x0 else 2 * centreX - s.x1
        val cxD = centres[3 * dst]; val cyD = centres[3 * dst + 1]
        val ox = 0.5f * (d.x0 + d.x1)
        val hw = d.width * 0.5f
        val oyRel = 0.5f * (d.at(d.up, 0.5f) + d.at(d.low, 0.5f))
        val zone = ArrayList<Int>(); val zoneW = ArrayList<Float>()
        for (i in 0 until headVertices) {
            val x = verts[3 * i]; val y = verts[3 * i + 1]; val z = verts[3 * i + 2]
            if (z < 0.1f) continue
            if (abs(x - cxD) > abs(x - centres[3 * srcEye])) continue
            val rel = y - cyD
            val rho = hypot((x - ox) / (hw * 2f), (rel - oyRel) / (hw * 1.2f))
            val w = 1 - smooth(0.75f, 1.3f, rho)
            if (w <= 0f) continue
            val uRaw = d.u(x)
            val u = uRaw.coerceIn(0f, 1f)
            val yu = d.at(d.up, u); val yl = d.at(d.low, u)
            val a = ((rel - yl) / maxOf(yu - yl, 1e-4f)).coerceIn(0f, 1f)
            val dyU = s.at(s.up, u) - yu
            val dyL = s.at(s.low, u) - yl
            verts[3 * i + 1] = y + w * (a * dyU + (1 - a) * dyL)
            val xT = if (d.inwardPositive) innerT - uRaw * s.width else innerT + uRaw * s.width
            verts[3 * i] = x + w * (xT - x)
            zone += i; zoneW += w
        }
        if (zone.isEmpty()) return
        val pinned = BooleanArray(mesh.vertexCount)
        for (k in 0 until rim.size / 3) { pinned[rim[3 * k]] = true; pinned[rim[3 * k + 1]] = true }
        val inZone = IntArray(mesh.vertexCount) { -1 }
        zone.forEachIndexed { k, vi -> inZone[vi] = k }
        val neighbours = Array(zone.size) { LinkedHashSet<Int>() }
        val faces = mesh.faces
        for (t in 0 until faces.size / 3) {
            val a = faces[3 * t]; val b = faces[3 * t + 1]; val c = faces[3 * t + 2]
            if (a >= headVertices || b >= headVertices || c >= headVertices) continue
            for (p in intArrayOf(a, b, c)) {
                val k = inZone[p]
                if (k < 0) continue
                for (q in intArrayOf(a, b, c)) if (q != p) neighbours[k] += q
            }
        }
        val next = FloatArray(zone.size * 3)
        repeat(EYE_RELAX_PASSES) {
            for (k in zone.indices) {
                val vi = zone[k]
                next[3 * k] = verts[3 * vi]; next[3 * k + 1] = verts[3 * vi + 1]; next[3 * k + 2] = verts[3 * vi + 2]
                val nb = neighbours[k]
                if (pinned[vi] || nb.isEmpty() || mesh.lipMask[vi] > 0.05f) continue
                var ax = 0f; var ay = 0f; var az = 0f
                for (b in nb) { ax += verts[3 * b]; ay += verts[3 * b + 1]; az += verts[3 * b + 2] }
                val f = EYE_RELAX_STRENGTH * zoneW[k]
                next[3 * k] += (ax / nb.size - verts[3 * vi]) * f
                next[3 * k + 1] += (ay / nb.size - verts[3 * vi + 1]) * f
                next[3 * k + 2] += (az / nb.size - verts[3 * vi + 2]) * f
            }
            for (k in zone.indices) {
                verts[3 * zone[k]] = next[3 * k]; verts[3 * zone[k] + 1] = next[3 * k + 1]; verts[3 * zone[k] + 2] = next[3 * k + 2]
            }
        }
    }
}

/** Smooth normals of the head's skin recomputed after its vertices moved (the hair, the eyes and the mouth keep theirs). */
internal fun refitHeadNormals(mesh: HeadMesh, verts: FloatArray, headVertices: Int): FloatArray {
    val normals = mesh.normals.copyOf()
    val sums = DoubleArray(headVertices * 3)
    val f = mesh.faces
    for (t in 0 until mesh.faceCount) {
        if (mesh.faceGroup[t] > 1.5f) continue
        val a = f[3 * t]; val b = f[3 * t + 1]; val c = f[3 * t + 2]
        if (a >= headVertices || b >= headVertices || c >= headVertices) continue
        val abx = verts[3 * b] - verts[3 * a]; val aby = verts[3 * b + 1] - verts[3 * a + 1]; val abz = verts[3 * b + 2] - verts[3 * a + 2]
        val acx = verts[3 * c] - verts[3 * a]; val acy = verts[3 * c + 1] - verts[3 * a + 1]; val acz = verts[3 * c + 2] - verts[3 * a + 2]
        val nx = aby * acz - abz * acy; val ny = abz * acx - abx * acz; val nz = abx * acy - aby * acx
        for (i in intArrayOf(a, b, c)) { sums[3 * i] += nx.toDouble(); sums[3 * i + 1] += ny.toDouble(); sums[3 * i + 2] += nz.toDouble() }
    }
    for (i in 0 until headVertices) {
        var nx = sums[3 * i]; var ny = sums[3 * i + 1]; var nz = sums[3 * i + 2]
        val len = sqrt(nx * nx + ny * ny + nz * nz)
        if (len < 1e-9) continue
        if (nx * mesh.normals[3 * i] + ny * mesh.normals[3 * i + 1] + nz * mesh.normals[3 * i + 2] < 0) { nx = -nx; ny = -ny; nz = -nz }
        normals[3 * i] = (nx / len).toFloat(); normals[3 * i + 1] = (ny / len).toFloat(); normals[3 * i + 2] = (nz / len).toFloat()
    }
    return normals
}
