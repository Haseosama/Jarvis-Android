package com.jarvis.android.avatar

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt

/**
 * How finely the head is cut into triangles, as in Jarvis 2.0 (src/avatar/HeadMesh.js, POLYGON_LEVELS): Éco and Léger merge the
 * skin's vertices on a grid, Standard is the mesh as it is, Haute cuts every skin triangle in four along curved normals, Ultra the
 * hair too. The eyes, the lids, the lips, the landmarks and the hair's locks are never touched, so the animation is the same.
 * [webR0] is the spacing of the web's nodes on that level.
 */
internal enum class PolygonLevel(val id: String, val label: String, val webR0: Float) {
    ECO("eco", "Éco", 0.048f),
    LOW("low", "Léger", 0.038f),
    MEDIUM("medium", "Standard", NetworkWeb.R0),
    HIGH("high", "Haute définition", 0.028f),
    ULTRA("ultra", "Ultra", 0.024f);

    companion object {
        fun of(id: String?): PolygonLevel = entries.firstOrNull { it.id == id } ?: MEDIUM
    }
}

internal object PolygonMesh {
    /** The head at [level] (the one given is never modified; Standard returns it as it is). */
    fun apply(mesh: HeadMesh, level: PolygonLevel): HeadMesh = when (level) {
        PolygonLevel.ECO -> decimate(mesh, 0.046f)
        PolygonLevel.LOW -> decimate(mesh, 0.026f)
        PolygonLevel.MEDIUM -> mesh
        PolygonLevel.HIGH -> subdivide(mesh, includeHair = false)
        PolygonLevel.ULTRA -> subdivide(mesh, includeHair = true)
    }.copy(webR0 = level.webR0)

    /**
     * Each skin triangle (and, with [includeHair], each hair triangle) becomes four; the new middle points are pushed along the
     * vertex normals (a Phong curve) so the surface gets rounder, except on the lid and lip seams, which stay exact. The old vertices
     * keep their indices: the new ones are appended.
     */
    fun subdivide(mesh: HeadMesh, includeHair: Boolean): HeadMesh {
        val oldV = mesh.vertexCount
        val oldF = mesh.faceCount
        val v = mesh.verts; val nrm = mesh.normals; val f = mesh.faces; val fg = mesh.faceGroup; val paint = mesh.paint
        val sway = mesh.hairSway ?: FloatArray(oldV)
        val boundary = BooleanArray(oldV)
        for (k in 0 until mesh.eyelidRim.size / 3) { boundary[mesh.eyelidRim[3 * k]] = true; boundary[mesh.eyelidRim[3 * k + 1]] = true }
        for (vi in mesh.mouthUpper) boundary[vi] = true
        for (vi in mesh.mouthLower) boundary[vi] = true
        val split = BooleanArray(oldF)
        var subCount = 0
        for (t in 0 until oldF) {
            val a = f[3 * t]; val b = f[3 * t + 1]; val c = f[3 * t + 2]
            val group = if (includeHair) fg[t] <= 3.5f else fg[t] <= 1.5f
            val plain = includeHair || (paint[a] == 0 && paint[b] == 0 && paint[c] == 0)
            if (group && plain && (mesh.fade[a] + mesh.fade[b] + mesh.fade[c]) / 3f > 0.12f) { split[t] = true; subCount++ }
        }
        if (subCount == 0) return mesh

        val midpoints = HashMap<Long, Int>()
        val nv = FloatArrayBuilder(); val nn = FloatArrayBuilder()
        val nJaw = FloatArrayBuilder(); val nBrow = FloatArrayBuilder(); val nLips = FloatArrayBuilder(); val nFade = FloatArrayBuilder()
        val nLid = FloatArrayBuilder(); val nLipMask = FloatArrayBuilder(); val nSway = FloatArrayBuilder()
        val nPaint = ArrayList<Int>()
        fun mid(i: Int, j: Int): Int {
            val lo = minOf(i, j); val hi = maxOf(i, j)
            val key = lo.toLong() * 1_000_000L + hi
            midpoints[key]?.let { return it }
            val idx = oldV + nPaint.size
            midpoints[key] = idx
            val ax = v[3 * lo]; val ay = v[3 * lo + 1]; val az = v[3 * lo + 2]
            val bx = v[3 * hi]; val by = v[3 * hi + 1]; val bz = v[3 * hi + 2]
            val nax = nrm[3 * lo]; val nay = nrm[3 * lo + 1]; val naz = nrm[3 * lo + 2]
            val nbx = nrm[3 * hi]; val nby = nrm[3 * hi + 1]; val nbz = nrm[3 * hi + 2]
            var mx = 0.5f * (ax + bx); var my = 0.5f * (ay + by); var mz = 0.5f * (az + bz)
            if (!boundary[lo] && !boundary[hi] && mesh.lid[lo] == 0f && mesh.lid[hi] == 0f) {
                val dx = bx - ax; val dy = by - ay; val dz = bz - az
                val dotA = dx * nax + dy * nay + dz * naz
                val dotB = -dx * nbx - dy * nby - dz * nbz
                val alpha = 0.16f
                mx -= alpha * (dotA * nax + dotB * nbx)
                my -= alpha * (dotA * nay + dotB * nby)
                mz -= alpha * (dotA * naz + dotB * nbz)
            }
            var nx = nax + nbx; var ny = nay + nby; var nz = naz + nbz
            val nl = max(sqrt(nx * nx + ny * ny + nz * nz), 1e-9f)
            nx /= nl; ny /= nl; nz /= nl
            nv.add(mx, my, mz); nn.add(nx, ny, nz)
            nJaw.add(0.5f * (mesh.jaw[lo] + mesh.jaw[hi])); nBrow.add(0.5f * (mesh.brow[lo] + mesh.brow[hi]))
            nLips.add(0.5f * (mesh.lips[lo] + mesh.lips[hi])); nFade.add(0.5f * (mesh.fade[lo] + mesh.fade[hi]))
            nPaint += if (paint[lo] != 0) paint[lo] else paint[hi]
            nLid.add(0.5f * (mesh.lid[lo] + mesh.lid[hi])); nLipMask.add(0.5f * (mesh.lipMask[lo] + mesh.lipMask[hi]))
            nSway.add(0.5f * (sway[lo] + sway[hi]))
            return idx
        }
        val newF = oldF + 3 * subCount
        val faces = IntArray(3 * newF)
        val group = FloatArray(newF)
        var k = 0
        fun put(a: Int, b: Int, c: Int, g: Float) { faces[3 * k] = a; faces[3 * k + 1] = b; faces[3 * k + 2] = c; group[k++] = g }
        for (t in 0 until oldF) {
            val a = f[3 * t]; val b = f[3 * t + 1]; val c = f[3 * t + 2]; val g = fg[t]
            if (!split[t]) { put(a, b, c, g); continue }
            val ab = mid(a, b); val bc = mid(b, c); val ca = mid(c, a)
            put(a, ab, ca, g); put(b, bc, ab, g); put(c, ca, bc, g); put(ab, bc, ca, g)
        }
        return mesh.copy(
            verts = v + nv.toArray(), normals = nrm + nn.toArray(), jaw = mesh.jaw + nJaw.toArray(), brow = mesh.brow + nBrow.toArray(),
            lips = mesh.lips + nLips.toArray(), fade = mesh.fade + nFade.toArray(), paint = paint + nPaint.toIntArray(),
            lid = mesh.lid + nLid.toArray(), lipMask = mesh.lipMask + nLipMask.toArray(), hairSway = sway + nSway.toArray(),
            faces = faces, faceGroup = group,
        )
    }

    /**
     * Fewer triangles: every free vertex is merged with the first one found in its grid cell of [cellSize], and the triangles that
     * collapse are dropped. The eyes, the lids, the lips, the landmarks and the mouth line are pinned, so the face stays sharp.
     */
    fun decimate(mesh: HeadMesh, cellSize: Float): HeadMesh {
        if (cellSize <= 0f) return mesh
        val nV = mesh.vertexCount
        val v = mesh.verts
        val pinned = BooleanArray(nV)
        for (e in mesh.eyeFirst.indices) for (i in mesh.eyeFirst[e] until minOf(nV, mesh.eyeFirst[e] + mesh.eyeCount[e])) pinned[i] = true
        for (k in 0 until mesh.eyelidRim.size / 3) { pinned[mesh.eyelidRim[3 * k]] = true; pinned[mesh.eyelidRim[3 * k + 1]] = true }
        for (vi in mesh.mouthUpper) pinned[vi] = true
        for (vi in mesh.mouthLower) pinned[vi] = true
        for (ring in mesh.landmarks.values) for (vi in ring) pinned[vi] = true
        for (i in 0 until nV) if (mesh.lid[i] > 0.005f || mesh.lipMask[i] > 0.08f || mesh.lips[i] > 0.08f) pinned[i] = true
        val inv = 1f / cellSize
        val cells = HashMap<Long, Int>()
        val rep = IntArray(nV)
        for (i in 0 until nV) {
            if (pinned[i]) { rep[i] = i; continue }
            val gx = floor((v[3 * i] + 2f) * inv).toLong() and 0x3ff
            val gy = floor((v[3 * i + 1] + 2f) * inv).toLong() and 0x3ff
            val gz = floor((v[3 * i + 2] + 2f) * inv).toLong() and 0x3ff
            val key = ((gx * 1024 + gy) * 1024 + gz) * 2 + if (mesh.paint[i] == 0) 0 else 1
            rep[i] = cells.getOrPut(key) { i }
        }
        val faces = ArrayList<Int>(mesh.faces.size)
        val group = FloatArrayBuilder()
        for (t in 0 until mesh.faceCount) {
            val a = rep[mesh.faces[3 * t]]; val b = rep[mesh.faces[3 * t + 1]]; val c = rep[mesh.faces[3 * t + 2]]
            if (a == b || b == c || c == a) continue
            faces += a; faces += b; faces += c
            group.add(mesh.faceGroup[t])
        }
        return mesh.copy(faces = faces.toIntArray(), faceGroup = group.toArray())
    }

    private class FloatArrayBuilder {
        private var data = FloatArray(1024)
        private var size = 0
        fun add(vararg xs: Float) {
            if (size + xs.size > data.size) data = data.copyOf(max(data.size * 2, size + xs.size))
            for (x in xs) data[size++] = x
        }
        fun toArray(): FloatArray = data.copyOf(size)
    }
}
