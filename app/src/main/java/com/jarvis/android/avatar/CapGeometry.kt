package com.jarvis.android.avatar

import kotlin.math.abs
import kotlin.math.max

/**
 * A cap for the head: a dome that is the top of the head's skin pushed out along the normals (so it follows the head as it moves), and a
 * visor along the front edge. Chosen once in the rest pose; the renderer builds the positions each frame from the posed head.
 *
 * The edge of the cap is a curve: level with the top of the ears at the sides, just above the brows at the front, lower at the back. The
 * vertices below that curve, up to a short distance, are lifted onto it, which gives a clean edge.
 */
internal class CapGeometry(mesh: HeadMesh) {
    /** Mesh vertex of each point of the dome, and how far up (in the rest pose) it is lifted onto the edge. */
    val verts: IntArray
    val lift: FloatArray
    val tris: IntArray

    /** The points of the front edge, from left to right: where the visor starts. Local indices, with their side position. */
    val visor: IntArray
    val visorX: FloatArray

    /** The point at the top of the head, for the button. */
    val top: Int

    /** For each triangle of the mesh: hidden under the cap (hair only). For each lock of the hair: the same. */
    val hiddenFace: BooleanArray
    val hiddenLock: BooleanArray
    val midX: Float

    companion object {
        /** How far the dome stands from the head. */
        const val OFFSET = 0.075f

        /** How far below the edge the points are taken, to be lifted onto it (a thick band gives a whole, clean edge). */
        const val BAND = 0.12f
        /** Longest reach of the visor, in head half-heights. */
        const val VISOR = 0.36f
        const val VISOR_HALF_WIDTH = 0.60f

        /** Height of the edge of the cap over the point (side, depth) of the rest pose. */
        fun rim(side: Float, z: Float): Float {
            val t = ((-z - 0.02f) / 0.53f).coerceIn(0f, 1f)
            return 0.40f - 0.32f * (t * t * (3f - 2f * t)) + 0.05f * (1f - t) * (1f - (abs(side) / 0.7f).coerceIn(0f, 1f))
        }
    }

    init {
        val v = mesh.verts
        val f = mesh.faces
        val nF = mesh.faceCount
        val nV = mesh.vertexCount
        midX = if (mesh.eyeCentre.size >= 6) 0.5f * (mesh.eyeCentre[0] + mesh.eyeCentre[3]) else 0f

        // the vertices of the head's skin that are high enough
        val onSkin = BooleanArray(nV)
        for (t in 0 until nF) if (mesh.faceGroup[t] in 0.5f..1.5f) for (k in 0..2) onSkin[f[3 * t + k]] = true
        val local = IntArray(nV) { -1 }
        val list = ArrayList<Int>(); val lifts = ArrayList<Float>()
        for (i in 0 until nV) {
            if (!onSkin[i] || mesh.paint[i] != 0 || mesh.fade[i] < 0.5f) continue
            val side = v[3 * i] - midX
            val rim = rim(side, v[3 * i + 2])
            if (v[3 * i + 1] < rim - BAND) continue
            // not over the ears: the cap sits above them
            val ex = (abs(side) - 0.72f) / 0.11f; val ey = (v[3 * i + 1] - 0.07f) / 0.22f; val ez = (v[3 * i + 2] + 0.14f) / 0.22f
            if (ex * ex + ey * ey + ez * ez < 1.6f) continue
            local[i] = list.size
            list += i
            lifts += max(0f, rim - v[3 * i + 1])
        }
        verts = list.toIntArray()
        lift = lifts.toFloatArray()

        val tri = ArrayList<Int>()
        for (t in 0 until nF) {
            if (mesh.faceGroup[t] !in 0.5f..1.5f) continue
            val a = local[f[3 * t]]; val b = local[f[3 * t + 1]]; val c = local[f[3 * t + 2]]
            if (a < 0 || b < 0 || c < 0) continue
            tri += a; tri += b; tri += c
        }
        tris = tri.toIntArray()

        var best = 0; var bestY = -1e9f
        for (j in verts.indices) if (v[3 * verts[j] + 1] > bestY) { bestY = v[3 * verts[j] + 1]; best = j }
        top = best

        // the front edge: the lifted points at the front, from left to right
        val edge = ArrayList<Int>()
        for (j in verts.indices) {
            val i = verts[j]
            if (lift[j] > BAND - 0.035f && v[3 * i + 2] > 0.10f && abs(v[3 * i] - midX) < VISOR_HALF_WIDTH) edge += j
        }
        edge.sortBy { v[3 * verts[it]] }
        visor = edge.toIntArray()
        visorX = FloatArray(visor.size) { v[3 * verts[visor[it]]] - midX }

        // the hair under the cap is not drawn
        hiddenFace = BooleanArray(nF)
        for (t in 0 until nF) {
            if (mesh.faceGroup[t] <= 1.5f) continue
            val a = f[3 * t]; val b = f[3 * t + 1]; val c = f[3 * t + 2]
            val cy = (v[3 * a + 1] + v[3 * b + 1] + v[3 * c + 1]) / 3f
            val cz = (v[3 * a + 2] + v[3 * b + 2] + v[3 * c + 2]) / 3f
            val cx = (v[3 * a] + v[3 * b] + v[3 * c]) / 3f - midX
            hiddenFace[t] = cy > rim(cx, cz) - 0.01f
        }
        hiddenLock = BooleanArray(mesh.lockCount) { l ->
            val root = mesh.lockFirst + l * 3 * mesh.lockRows + 1
            v[3 * root + 1] > rim(v[3 * root] - midX, v[3 * root + 2]) - 0.01f
        }
    }
}
