package com.jarvis.android.avatar

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The head, ready to animate: real measured face geometry (MediaPipe's canonical face model) with a cranium and a neck
 * built around it, plus the weights that drive the jaw, brows and lips. It is generated once from Mark-LIV's mesh
 * builder (see avatar/NOTICE.txt in the assets) and stored as a 64 KB binary file.
 *
 * Coordinates: +x to the viewer's right, +y up, +z out of the face; crown at y = 1, chin at y = -1, eyes near y = 0.
 */
internal class HeadMesh(
    val verts: FloatArray,        // x, y, z per vertex
    val normals: FloatArray,      // outward vertex normals
    val jaw: FloatArray,          // 0..1 how much each vertex follows the jaw
    val brow: FloatArray,
    val lips: FloatArray,
    val fade: FloatArray,         // 1 on the head, fading down the neck
    val faceGroup: FloatArray,    // per triangle: 1 for the head, 0 for the neck (the neck is drawn first)
    val faces: IntArray,          // 3 vertex indices per triangle
    val edges: IntArray,          // 2 vertex indices per wireframe line
    val landmarks: Map<String, IntArray>,
    val lipCentre: FloatArray,
    val nHead: Int,
    val nFace: Int,
    val crown: Float,
    val bottom: Float,
) {
    val vertexCount: Int get() = verts.size / 3
    val faceCount: Int get() = faces.size / 3
    val edgeCount: Int get() = edges.size / 2

    companion object {
        private val NAMES = listOf("eye_l", "eye_r", "brow_l", "brow_r", "lips_out", "lips_in")

        fun parse(bytes: ByteArray): HeadMesh {
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            require(bytes.size > 40 && bytes[0] == 'J'.code.toByte() && bytes[1] == 'H'.code.toByte() &&
                bytes[2] == 'M'.code.toByte() && bytes[3] == '1'.code.toByte()) { "Not a head mesh file" }
            b.position(4)
            val nv = b.int
            val nf = b.int
            val ne = b.int
            val nHead = b.int
            val nFace = b.int
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
            val group = floats(nf)
            val faces = ints(nf * 3)
            val edges = ints(ne * 2)
            val rings = b.int
            require(rings == NAMES.size) { "Unexpected landmark rings: $rings" }
            val landmarks = LinkedHashMap<String, IntArray>()
            for (name in NAMES) landmarks[name] = ints(b.int)
            require(faces.all { it in 0 until nv } && edges.all { it in 0 until nv }) { "Mesh indices out of range" }
            require(landmarks.values.all { ring -> ring.all { it in 0 until nv } }) { "Landmark index out of range" }
            return HeadMesh(verts, normals, jaw, brow, lips, fade, group, faces, edges, landmarks, lipCentre, nHead, nFace, crown, bottom)
        }
    }
}

/** Jaw hinge between the ears, and the largest jaw drop in radians (speech barely moves a real jaw). */
internal val JAW_PIVOT = floatArrayOf(0f, 0.06f, -0.34f)
internal const val JAW_MAX = 0.115f
