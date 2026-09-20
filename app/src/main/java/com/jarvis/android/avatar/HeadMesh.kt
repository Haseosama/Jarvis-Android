package com.jarvis.android.avatar

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The head, ready to animate: real measured face geometry (MediaPipe's canonical face model) with a cranium and a neck
 * built around it, subdivided once so the surface is smooth, plus a hair shell and hair strands. It is generated once
 * from Mark-LIV's mesh builder (see avatar/NOTICE.txt in the assets) and stored as a binary file.
 *
 * Vertices are laid out as: the head and neck (indices below [baseCount]), then the hair shell, then the strand points.
 * Coordinates: +x to the viewer's right, +y up, +z out of the face; crown at y = 1, chin at y = -1, eyes near y = 0.
 */
internal class HeadMesh(
    val verts: FloatArray,        // x, y, z per vertex
    val normals: FloatArray,      // outward vertex normals
    val jaw: FloatArray,          // 0..1 how much each vertex follows the jaw
    val brow: FloatArray,
    val lips: FloatArray,
    val fade: FloatArray,         // 1 on the head, fading down the neck; on hair, darker towards the roots
    val ao: FloatArray,           // ambient occlusion: 1 open skin, lower in eye sockets, nostrils, under the lip
    val faceGroup: FloatArray,    // per triangle: 0 neck (drawn first), 1 face mask, 1.2 cranium sweep, 2 hair
    val faces: IntArray,          // 3 vertex indices per triangle
    val landmarks: Map<String, IntArray>,
    val lipCentre: FloatArray,
    val baseCount: Int,           // vertices of the head and neck
    val faceVertexCount: Int,     // vertices of the face mask (they keep their original indices after subdivision)
    val strandCount: Int,
    val fringeCount: Int,         // the last strands are fringe locks hanging over the forehead (drawn thicker)
    val strandLength: Int,
    val circuitCount: Int,        // glowing circuit traces (cyber style); the last [brightCount] form the forehead chip
    val circuitLength: Int,
    val brightCount: Int,
    val strandBase: Int,          // index of the first strand point
    val hairFirst: Int,           // index of the first hair-shell vertex
    val hairVertexCount: Int,
    val crown: Float,
    val bottom: Float,
    val netNodes: IntArray = IntArray(0),  // vertices spread evenly over the head: the nodes of the network look
    val netEdges: IntArray = IntArray(0),  // pairs of positions in [netNodes] joined by a line
) {
    /** Index of the first circuit point: they come right after the strand points. */
    val circuitBase: Int get() = strandBase + strandCount * strandLength

    val vertexCount: Int get() = verts.size / 3
    val faceCount: Int get() = faces.size / 3

    /** True for a triangle of the cranium sweep that joins the face mask to the skull (group 1.2). */
    fun isSweepFace(t: Int) = faceGroup[t] in 1.1f..1.5f

    /** True for a triangle of the hair shell. */
    fun isHairFace(t: Int) = faceGroup[t] > 1.5f

    /** True for a vertex that belongs to the hair (shell or strand). */
    fun isHairVertex(i: Int) = i >= hairFirst

    companion object {
        private val NAMES = listOf("eye_l", "eye_r", "brow_l", "brow_r", "lips_out", "lips_in")

        fun parse(bytes: ByteArray): HeadMesh {
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            require(bytes.size > 60 && bytes[0] == 'J'.code.toByte() && bytes[1] == 'H'.code.toByte() &&
                bytes[2] == 'M'.code.toByte() && bytes[3] == '3'.code.toByte()) { "Not a head mesh file" }
            b.position(4)
            val nv = b.int
            val nf = b.int
            b.int // legacy edge count, always 0
            val nBase = b.int
            val nFace = b.int
            val nStrands = b.int
            val strandLen = b.int
            val strandBase = b.int
            val nHair = b.int
            b.int // number of hair triangles, implied by the face groups
            val crown = b.float
            val bottom = b.float
            val lipCentre = FloatArray(3) { b.float }
            fun floats(n: Int) = FloatArray(n).also { b.asFloatBuffer().get(it); b.position(b.position() + n * 4) }
            fun ints(n: Int) = IntArray(n).also { b.asIntBuffer().get(it); b.position(b.position() + n * 4) }
            val verts = floats(nv * 3)
            val normals = floats(nv * 3)
            val jaw = floats(nv)
            val brow = floats(nv)
            val lips = floats(nv)
            val fade = floats(nv)
            val ao = floats(nv)
            val group = floats(nf)
            val faces = ints(nf * 3)
            val rings = b.int
            require(rings == NAMES.size) { "Unexpected landmark rings: $rings" }
            val landmarks = LinkedHashMap<String, IntArray>()
            for (name in NAMES) landmarks[name] = ints(b.int)
            val fringe = b.int
            val circuits = b.int
            val circuitLen = b.int
            val bright = b.int
            val netNodes = ints(b.int)
            val netEdges = ints(b.int * 2)
            require(faces.all { it in 0 until nv }) { "Mesh indices out of range" }
            require(landmarks.values.all { ring -> ring.all { it in 0 until nv } }) { "Landmark index out of range" }
            require(strandBase + nStrands * strandLen + circuits * circuitLen == nv && nBase + nHair == strandBase) { "Inconsistent hair layout" }
            return HeadMesh(
                verts, normals, jaw, brow, lips, fade, ao, group, faces, landmarks, lipCentre,
                nBase, nFace, nStrands, fringe, strandLen, circuits, circuitLen, bright, strandBase, nBase, nHair, crown, bottom, netNodes, netEdges,
            )
        }
    }
}

/** Jaw hinge between the ears, and the largest jaw drop in radians (speech barely moves a real jaw). */
internal val JAW_PIVOT = floatArrayOf(0f, 0.06f, -0.34f)
internal const val JAW_MAX = 0.115f
