package com.jarvis.android.avatar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AvatarFacesTest {
    /** The faces made of a mesh; the cartoon one is drawn and only borrows a file to build its animation. */
    private val faces = AVATAR_FACES.filter { !it.cartoon }

    private val meshes: List<HeadMesh> by lazy {
        faces.map { HeadMesh.parse(File("src/main/assets/" + it.asset).readBytes()) }
    }

    @Test fun `every face is a complete head with the same rig`() {
        for ((i, mesh) in meshes.withIndex()) {
            val name = faces[i].label
            assertEquals(name, mapOf("eye_l" to 16, "eye_r" to 16, "brow_l" to 5, "brow_r" to 5, "lips_out" to 20, "lips_in" to 20), mesh.landmarks.mapValues { it.value.size })
            assertEquals(name, 2, mesh.eyeCount.size)
            assertTrue(name, mesh.crown > mesh.bottom)
            assertEquals(name, mesh.vertexCount * 3, mesh.verts.size)
            assertTrue("$name has hair", mesh.faceGroup.count { it > 1.5f } > 5_000)
            assertTrue("$name has locks", mesh.lockCount > 300 && mesh.lockRows >= 2)
        }
    }

    @Test fun `the eyes and the mouth stay where a face has them`() {
        for ((i, mesh) in meshes.withIndex()) {
            val name = faces[i].label
            for (e in 0..1) {
                val (x, y, z) = Triple(mesh.eyeCentre[3 * e], mesh.eyeCentre[3 * e + 1], mesh.eyeCentre[3 * e + 2])
                assertTrue("$name eye $e y=$y", y in -0.2f..0.25f)
                assertTrue("$name eye $e x=$x", kotlin.math.abs(x + 0.035f) in 0.15f..0.6f)
                assertTrue("$name eye $e z=$z", z > 0.0f)
            }
            assertTrue("$name lips y=${mesh.lipCentre[1]}", mesh.lipCentre[1] in -0.75f..-0.35f)
        }
    }

    @Test fun `the faces are different heads`() {
        assertEquals(faces.size, meshes.map { it.vertexCount }.toSet().size)
        // the nose sticks out by a different amount, the jaw is not the same width
        val noseTip = meshes.map { m -> (0 until m.vertexCount).maxOf { m.verts[3 * it + 2] } }
        assertEquals(faces.size, noseTip.map { "%.3f".format(it) }.toSet().size)
        fun jawWidth(m: HeadMesh): Float {
            // the skin only: the hair of a long style hangs across this height
            val skin = HashSet<Int>()
            for (t in 0 until m.faceCount) if (m.faceGroup[t] <= 1.5f) for (k in 0..2) skin += m.faces[3 * t + k]
            val near = skin.filter { m.verts[3 * it + 1] in -0.78f..-0.62f && m.verts[3 * it + 2] > 0.2f }
            return near.maxOf { m.verts[3 * it] } - near.minOf { m.verts[3 * it] }
        }
        val widths = meshes.map { jawWidth(it) }
        assertTrue("Léa's jaw is narrower than the original's, and Marc's wider: $widths", widths[1] < widths[0] && widths[2] > widths[0])
    }

    @Test fun `each face has its own eyebrow colour and an index outside the list is clamped`() {
        assertEquals(AVATAR_FACES.size, AVATAR_FACES.map { it.browColour }.toSet().size)
        assertEquals(AVATAR_FACES.first(), avatarFace(-3))
        assertEquals(AVATAR_FACES.last(), avatarFace(99))
        assertNotEquals(avatarFace(0).asset, avatarFace(1).asset)
    }

    @Test fun `no face is heavier than a quarter more than the original`() {
        val base = meshes[0].faceCount
        for ((i, mesh) in meshes.withIndex()) assertTrue("${faces[i].label}: ${mesh.faceCount} faces against $base", mesh.faceCount <= base * 1.25)
    }
}
